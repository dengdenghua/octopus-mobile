package com.apk.claw.android.octopus_mobile

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 审计日志哈希链工具 —— 逐条 HMAC + 前驱链接,检测"改内容 + 删条目 + 调换顺序"。
 * [ToolAuditLog] / [RemoteAccessLog] 共用,把易错的链式加密收在一处。
 *
 * 方案:每条 `signature = HMAC(payload | prevHash)`,`prevHash` = 前一条(时间更早)的 signature。
 *  - **改内容** → 自身签名不匹配(tampered)。
 *  - **中间删条 / 调序** → 相邻两条的 prevHash↔signature 链接断裂(tampered)。
 *  - **删最新条** → 持久化的 headAnchor(最新签名)与当前首条不符 → 标记首条 tampered。
 *  - **删最旧条(裁剪边界)** → 无法与正常 trim 区分,不检测(已知局限)。
 *
 * 兼容旧记录:`prevHash == null` 的历史条目按旧方案 `HMAC(payload)` 校验,不参与链接 —— 升级后
 * 旧数据不会被误判为篡改;首条新记录的 prevHash 会锚到当时最新旧条目的签名,链自然接续。
 *
 * 局限:密钥与日志同存本地(KVUtils),能读取密钥的本地攻击者可重算整条链;要防这类攻击需
 * 硬件不可导出密钥(Android Keystore),属另一项工作。本工具面向"能写/删日志但读不到密钥"的
 * 威胁(如通过某个写入面注入/删改,却无法读 MMKV 密钥)。
 */
internal object AuditChain {

    /** 空链锚点(首条记录的 prevHash)。 */
    const val GENESIS = "GENESIS"

    /** 新方案:签名覆盖 payload 与前驱哈希。 */
    fun sign(secret: String, payload: String, prevHash: String): String =
        hmac(secret, "$payload|$prevHash")

    /** 旧方案(无 prevHash),仅用于兼容校验升级前写入的历史条目。 */
    fun signLegacy(secret: String, payload: String): String = hmac(secret, payload)

    fun generateSecret(): String {
        val bytes = ByteArray(32)
        java.security.SecureRandom().nextBytes(bytes)
        return java.util.Base64.getEncoder().encodeToString(bytes)
    }

    private fun hmac(secret: String, data: String): String {
        return try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(java.util.Base64.getDecoder().decode(secret), "HmacSHA256"))
            val raw = mac.doFinal(data.toByteArray(Charsets.UTF_8))
            java.util.Base64.getEncoder().encodeToString(raw)
        } catch (e: Exception) {
            "" // 计算失败不阻断写入,但读取校验会因不匹配标记 tampered
        }
    }

    /**
     * 校验整条链(**newest-first** 顺序)。返回与输入等长的"是否篡改"布尔数组。
     *
     * @param payloads 各条业务字段拼接串(newest-first)
     * @param sigs 各条已存签名
     * @param prevHashes 各条已存 prevHash(旧条目为 null)
     * @param headAnchor 持久化的最新签名(可空)——用于检测"最新条被删"
     */
    fun verify(
        secret: String,
        payloads: List<String>,
        sigs: List<String?>,
        prevHashes: List<String?>,
        headAnchor: String?,
    ): BooleanArray {
        val n = payloads.size
        val tampered = BooleanArray(n)
        for (i in 0 until n) {
            val sig = sigs[i]
            val prev = prevHashes[i]
            // 自身签名完整性(旧条目 prevHash==null 走旧方案)
            val expected = if (prev == null) signLegacy(secret, payloads[i]) else sign(secret, payloads[i], prev)
            val ownValid = sig != null && sig == expected
            // 链接:本条(较新)的 prevHash 应等于更早一条(i+1)的签名。
            // 仅对"链式(prev!=null)且存在前驱(i<n-1)"的条目检查;最旧一条的前驱可能已被 trim。
            val linkBroken = prev != null && i < n - 1 && prev != sigs[i + 1]
            tampered[i] = !ownValid || linkBroken
        }
        // 最新条删除检测:持久化 headAnchor 应等于当前首条签名
        if (headAnchor != null && headAnchor.isNotEmpty() && n > 0 && sigs[0] != headAnchor) {
            tampered[0] = true
        }
        return tampered
    }
}
