package com.apk.claw.android.octopus_mobile

import com.apk.claw.android.utils.KVUtils
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Persistent audit log for medium/high-risk tool calls.
 *
 * This does not reduce capability. It records what strong tools did, with
 * sensitive parameters redacted before persistence.
 *
 * 防篡改：哈希链(逐条 HMAC-SHA256 + 前驱链接,见 [AuditChain])。写入时用设备密钥计算签名并
 * 锚接前一条,读取时校验;改内容 / 删条目 / 调换顺序都会把相关条目标记 `tampered=true`。
 * 密钥派生自设备本地随机数,无法跨设备伪造。局限见 [AuditChain]。
 */
object ToolAuditLog {

    private const val KEY = "tool_audit_log"
    private const val KEY_HMAC_SECRET = "tool_audit_hmac_secret"
    private const val KEY_HEAD_ANCHOR = "tool_audit_head_anchor"
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
            KVUtils.putString(KEY_HEAD_ANCHOR, signed.signature ?: "")
        }
    }

    /**
     * 记录一次高影响安全设置变更（如"允许远程来源执行高危工具""完全权限模式"开关）。
     * 复用同一审计日志的哈希链与 AuditLogActivity 展示,使这些开关的开/关形成可追溯、
     * 防篡改的痕迹（谁/何时翻动）。
     */
    fun recordSecuritySetting(settingName: String, enabled: Boolean) {
        record(
            Entry(
                id = java.util.UUID.randomUUID().toString(),
                ts = System.currentTimeMillis(),
                toolName = "设置 · $settingName",
                risk = "high",
                params = "enabled=$enabled",
                success = true,
                result = if (enabled) "已开启" else "已关闭",
                blockedBy = null,
                durationMs = 0,
            )
        )
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

    /** 获取或生成设备级 HMAC 密钥。 */
    private val hmacSecret: String by lazy {
        KVUtils.getString(KEY_HMAC_SECRET).takeIf { it.isNotEmpty() }
            ?: AuditChain.generateSecret().also { KVUtils.putString(KEY_HMAC_SECRET, it) }
    }

    /** 业务字段拼接串（不含 prevHash/signature/tampered），作为签名 payload。 */
    private fun payloadOf(e: Entry): String = buildString {
        append(e.id).append('|')
        append(e.ts).append('|')
        append(e.toolName).append('|')
        append(e.risk).append('|')
        append(e.params).append('|')
        append(e.success).append('|')
        append(e.result).append('|')
        append(e.blockedBy).append('|')
        append(e.durationMs)
    }
}
