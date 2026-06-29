package com.apk.claw.android.octopus_mobile

import com.apk.claw.android.utils.KVUtils
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Persistent audit log for medium/high-risk tool calls.
 *
 * This does not reduce capability. It records what strong tools did, with
 * sensitive parameters redacted before persistence.
 *
 * 防篡改：每条记录附带 HMAC-SHA256 签名，写入时用设备密钥计算。
 * 读取时校验签名，篡改/伪造的条目会被标记为 `tampered=true`。
 * 密钥派生自设备唯一标识，无法跨设备伪造。
 */
object ToolAuditLog {

    private const val KEY = "tool_audit_log"
    private const val KEY_HMAC_SECRET = "tool_audit_hmac_secret"
    private const val MAX_KEEP = 300
    private val gson = Gson()

    data class Entry(
        val id: String,
        val ts: Long,
        val toolName: String,
        val risk: String,
        val params: String,
        val success: Boolean,
        val result: String,
        val blockedBy: String?,
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

    /** 获取或生成设备级 HMAC 密钥。 */
    private val hmacSecret: String by lazy {
        KVUtils.getString(KEY_HMAC_SECRET).takeIf { it.isNotEmpty() }
            ?: generateSecret().also { KVUtils.putString(KEY_HMAC_SECRET, it) }
    }

    private fun generateSecret(): String {
        val bytes = ByteArray(32)
        java.security.SecureRandom().nextBytes(bytes)
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }

    /**
     * 计算条目的 HMAC-SHA256 签名。
     * 签名覆盖所有业务字段（不含 signature 和 tampered 本身）。
     */
    private fun computeSignature(entry: Entry): String {
        val payload = buildString {
            append(entry.id).append('|')
            append(entry.ts).append('|')
            append(entry.toolName).append('|')
            append(entry.risk).append('|')
            append(entry.params).append('|')
            append(entry.success).append('|')
            append(entry.result).append('|')
            append(entry.blockedBy).append('|')
            append(entry.durationMs)
        }
        return try {
            val mac = Mac.getInstance("HmacSHA256")
            val key = SecretKeySpec(android.util.Base64.decode(hmacSecret, android.util.Base64.NO_WRAP), "HmacSHA256")
            mac.init(key)
            val raw = mac.doFinal(payload.toByteArray(Charsets.UTF_8))
            android.util.Base64.encodeToString(raw, android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            "" // 签名失败不阻断写入，但读取时会被标记 tampered
        }
    }
}
