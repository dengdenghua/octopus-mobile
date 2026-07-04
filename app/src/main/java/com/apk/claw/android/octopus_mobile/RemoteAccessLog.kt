package com.apk.claw.android.octopus_mobile

import com.apk.claw.android.utils.KVUtils
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Persistent audit log for powerful HTTP/LAN entry points.
 *
 * Strong remote capabilities stay available. This records who called them and
 * what surface was touched, without storing bulky payloads or secrets.
 *
 * 防篡改：哈希链(逐条 HMAC-SHA256 + 前驱链接,见 [AuditChain]),与 [ToolAuditLog] 同一方案。
 * 检测改内容 / 删条目 / 调换顺序;改过或断链的条目标记 `tampered=true`。密钥为设备本地随机数,
 * 无法跨设备伪造。局限见 [AuditChain]。
 */
object RemoteAccessLog {

    private const val KEY = "remote_access_log"
    private const val KEY_HMAC_SECRET = "remote_access_hmac_secret"
    private const val KEY_HEAD_ANCHOR = "remote_access_head_anchor"
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
        /** 前驱(时间更早)条目的签名;首条为 [AuditChain.GENESIS]。旧记录为 null。 */
        val prevHash: String? = null,
        /** HMAC-SHA256 链式签名（写入时计算，读取时校验）。 */
        val signature: String? = null,
        /** 读取时校验失败(改内容 / 断链)则标记为已篡改。 */
        val tampered: Boolean = false,
    )

    @Synchronized
    fun record(entry: Entry) {
        runCatching {
            val list = rawEntries().toMutableList()
            val prevHash = list.firstOrNull()?.signature ?: AuditChain.GENESIS
            val signed = entry.copy(
                prevHash = prevHash,
                signature = AuditChain.sign(hmacSecret, payloadOf(entry), prevHash),
            )
            list.add(0, signed)
            val trimmed = if (list.size > MAX_KEEP) list.subList(0, MAX_KEEP) else list
            KVUtils.putString(KEY, gson.toJson(trimmed))
            // 持久化最新签名,用于检测"最新条被删"
            KVUtils.putString(KEY_HEAD_ANCHOR, signed.signature ?: "")
        }
    }

    fun all(): List<Entry> {
        return runCatching {
            val entries = rawEntries()
            if (entries.isEmpty()) return emptyList()
            val flags = AuditChain.verify(
                secret = hmacSecret,
                payloads = entries.map { payloadOf(it) },
                sigs = entries.map { it.signature },
                prevHashes = entries.map { it.prevHash },
                headAnchor = KVUtils.getString(KEY_HEAD_ANCHOR, "").takeIf { it.isNotEmpty() },
            )
            entries.mapIndexed { i, e -> if (flags[i]) e.copy(tampered = true) else e }
        }.getOrDefault(emptyList())
    }

    fun clear() {
        runCatching {
            KVUtils.putString(KEY, "")
            KVUtils.putString(KEY_HEAD_ANCHOR, "")
        }
    }

    /** 读取原始存储(不校验),供 record 取前驱签名与追加。 */
    private fun rawEntries(): List<Entry> {
        val json = KVUtils.getString(KEY, "")
        if (json.isEmpty()) return emptyList()
        val type = object : TypeToken<List<Entry>>() {}.type
        return gson.fromJson<List<Entry>>(json, type) ?: emptyList()
    }

    /** 设备级 HMAC 密钥(独立于 ToolAuditLog 的密钥)。首次访问时生成并持久化。 */
    @Volatile private var hmacSecretCache: String? = null

    private val hmacSecret: String
        get() {
            hmacSecretCache?.let { return it }
            val s = KVUtils.getString(KEY_HMAC_SECRET).takeIf { it.isNotEmpty() }
                ?: AuditChain.generateSecret().also { KVUtils.putString(KEY_HMAC_SECRET, it) }
            hmacSecretCache = s
            return s
        }

    /**
     * 测试专用:重置 HMAC 密钥缓存,使下次访问重新从 KVUtils 读取/生成。
     * 用于解决单测间 [resetForTest] 清空 KVUtils 后,lazy 缓存仍持有旧密钥导致验签不一致。
     */
    @androidx.annotation.VisibleForTesting
    fun resetSecretCacheForTest() {
        hmacSecretCache = null
    }

    /** 业务字段拼接串（不含 prevHash/signature/tampered），作为签名 payload。 */
    private fun payloadOf(e: Entry): String = buildString {
        append(e.id).append('|')
        append(e.ts).append('|')
        append(e.method).append('|')
        append(e.uri).append('|')
        append(e.source).append('|')
        append(e.action).append('|')
        append(e.success).append('|')
        append(e.summary).append('|')
        append(e.durationMs)
    }
}
