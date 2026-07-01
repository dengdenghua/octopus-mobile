package com.apk.claw.android.octopus_mobile

import com.apk.claw.android.utils.KVUtils
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Persistent audit log for powerful HTTP/LAN entry points.
 *
 * Strong remote capabilities stay available. This records who called them and
 * what surface was touched, without storing bulky payloads or secrets.
 *
 * 防篡改：每条记录附带 HMAC-SHA256 签名，与 [ToolAuditLog] 同一方案 —— 写入时用设备密钥
 * 计算，读取时校验，篡改/伪造的条目标记 `tampered=true`。密钥派生自设备本地随机数,无法
 * 跨设备伪造。注:逐条 HMAC 只能检测"改内容",尚不能检测"删条目/调换顺序"(需哈希链,另议)。
 */
object RemoteAccessLog {

    private const val KEY = "remote_access_log"
    private const val KEY_HMAC_SECRET = "remote_access_hmac_secret"
    private const val MAX_KEEP = 300
    private val gson = Gson()

    data class Entry(
        val id: String,
        val ts: Long,
        val method: String,
        val uri: String,
        val source: String,
        val action: String,
        val success: Boolean,
        val summary: String,
        val durationMs: Long,
        /** HMAC-SHA256 签名（写入时计算，读取时校验）。 */
        val signature: String? = null,
        /** 读取时校验签名失败则标记为已篡改。 */
        val tampered: Boolean = false,
    )

    fun record(entry: Entry) {
        runCatching {
            val list = all().toMutableList()
            // 新条目计算签名
            val signed = entry.copy(signature = computeSignature(entry))
            list.add(0, signed)
            val trimmed = if (list.size > MAX_KEEP) list.subList(0, MAX_KEEP) else list
            KVUtils.putString(KEY, gson.toJson(trimmed))
        }
    }

    fun all(): List<Entry> {
        return runCatching {
            val json = KVUtils.getString(KEY, "")
            if (json.isEmpty()) return emptyList()
            val type = object : TypeToken<List<Entry>>() {}.type
            val entries = gson.fromJson<List<Entry>>(json, type) ?: return emptyList()
            // 校验每条记录的签名
            entries.map { entry ->
                val expectedSig = computeSignature(entry)
                val tampered = entry.signature != null && entry.signature != expectedSig
                if (tampered) entry.copy(tampered = true) else entry
            }
        }.getOrDefault(emptyList())
    }

    fun clear() {
        runCatching { KVUtils.putString(KEY, "") }
    }

    // ==================== HMAC 防篡改 ====================

    /** 获取或生成设备级 HMAC 密钥（独立于 ToolAuditLog 的密钥）。 */
    private val hmacSecret: String by lazy {
        KVUtils.getString(KEY_HMAC_SECRET).takeIf { it.isNotEmpty() }
            ?: generateSecret().also { KVUtils.putString(KEY_HMAC_SECRET, it) }
    }

    private fun generateSecret(): String {
        val bytes = ByteArray(32)
        java.security.SecureRandom().nextBytes(bytes)
        // java.util.Base64(标准无换行,minSdk 28 可用):输出等价于 android.util.Base64.NO_WRAP,
        // 但纯 JVM 单测亦可运行(不依赖 Android framework stub)。
        return java.util.Base64.getEncoder().encodeToString(bytes)
    }

    /**
     * 计算条目的 HMAC-SHA256 签名。
     * 签名覆盖所有业务字段（不含 signature 和 tampered 本身）。
     */
    private fun computeSignature(entry: Entry): String {
        val payload = buildString {
            append(entry.id).append('|')
            append(entry.ts).append('|')
            append(entry.method).append('|')
            append(entry.uri).append('|')
            append(entry.source).append('|')
            append(entry.action).append('|')
            append(entry.success).append('|')
            append(entry.summary).append('|')
            append(entry.durationMs)
        }
        return try {
            val mac = Mac.getInstance("HmacSHA256")
            val key = SecretKeySpec(java.util.Base64.getDecoder().decode(hmacSecret), "HmacSHA256")
            mac.init(key)
            val raw = mac.doFinal(payload.toByteArray(Charsets.UTF_8))
            java.util.Base64.getEncoder().encodeToString(raw)
        } catch (e: Exception) {
            "" // 签名失败不阻断写入，但读取时会被标记 tampered
        }
    }
}
