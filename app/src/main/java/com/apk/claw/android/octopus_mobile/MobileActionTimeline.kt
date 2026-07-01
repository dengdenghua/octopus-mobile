package com.apk.claw.android.octopus_mobile

import com.apk.claw.android.octopus_mobile.safety.ToolRiskPolicy
import com.apk.claw.android.utils.KVUtils
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.security.MessageDigest
import java.util.UUID

/**
 * Lightweight process timeline for every local/remote mobile tool execution.
 *
 * ToolAuditLog only records medium/high-risk calls. This timeline records all
 * calls with redacted params/results so debugging, repair recipes, and future
 * experience recall can reason from one durable execution stream.
 */
object MobileActionTimeline {

    const val SCHEMA = "octopus.mobile_action_timeline.v1"

    private const val KEY = "mobile_action_timeline"
    private const val MAX_KEEP = 500
    private const val MAX_FIELD_CHARS = 800
    private val gson = Gson()

    data class Entry(
        val id: String,
        val schema: String = SCHEMA,
        val ts: Long,
        val toolName: String,
        val risk: String,
        val source: String,
        val params: String,
        val success: Boolean,
        val result: String,
        val blockedBy: String?,
        val durationMs: Long,
        val resultFingerprint: String,
        val hasImage: Boolean = false,
        val hasHtml: Boolean = false,
    )

    data class Summary(
        val schema: String = "$SCHEMA.summary",
        val total: Int,
        val success: Int,
        val failed: Int,
        val blocked: Int,
        val highRisk: Int,
        val mediumRisk: Int,
        val remote: Int,
        val topFailures: List<Pair<String, Int>>,
    ) {
        val successRate: Double
            get() = if (total == 0) 1.0 else success.toDouble() / total.toDouble()
    }

    fun record(
        toolName: String,
        params: Map<String, Any>,
        success: Boolean,
        resultText: String?,
        blockedBy: String?,
        durationMs: Long,
        source: String,
        hasImage: Boolean = false,
        hasHtml: Boolean = false,
    ) {
        // @Synchronized:防并发工具执行时 read-modify-write 竞态导致丢条目/链断裂。
        synchronized(MobileActionTimeline::class.java) {
            runCatching {
            val risk = ToolRiskPolicy.riskOf(toolName)
            val sanitizedResult = ToolRiskPolicy.summarizeResult(resultText, MAX_FIELD_CHARS)
            val entry = Entry(
                id = "mobile_action_${System.currentTimeMillis()}_${UUID.randomUUID()}",
                ts = System.currentTimeMillis(),
                toolName = toolName,
                risk = risk,
                source = source,
                params = ToolRiskPolicy.summarizeParams(params, MAX_FIELD_CHARS),
                success = success,
                result = sanitizedResult,
                blockedBy = blockedBy,
                durationMs = durationMs.coerceAtLeast(0),
                resultFingerprint = sha256("${toolName}|${success}|${blockedBy}|${sanitizedResult}"),
                hasImage = hasImage,
                hasHtml = hasHtml,
            )
            val list = all().toMutableList()
            list.add(0, entry)
            KVUtils.putString(KEY, gson.toJson(list.take(MAX_KEEP)))
        }
        }
    }

    fun all(): List<Entry> = runCatching {
        val json = KVUtils.getString(KEY, "")
        if (json.isEmpty()) return emptyList()
        val type = object : TypeToken<List<Entry>>() {}.type
        gson.fromJson<List<Entry>>(json, type) ?: emptyList()
    }.getOrDefault(emptyList())

    fun recent(limit: Int = 50): List<Entry> = all().take(limit.coerceIn(1, MAX_KEEP))

    fun summary(limit: Int = MAX_KEEP): Summary {
        val entries = recent(limit)
        val failures = entries
            .filter { !it.success }
            .groupingBy { "${it.toolName}:${it.blockedBy ?: "failed"}" }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(5)
            .map { it.key to it.value }
        return Summary(
            total = entries.size,
            success = entries.count { it.success },
            failed = entries.count { !it.success },
            blocked = entries.count { it.blockedBy != null },
            highRisk = entries.count { it.risk == ToolRiskPolicy.RISK_HIGH },
            mediumRisk = entries.count { it.risk == ToolRiskPolicy.RISK_MEDIUM },
            remote = entries.count { it.source == "remote" || it.source == "untrusted" },
            topFailures = failures,
        )
    }

    fun clear() {
        KVUtils.putString(KEY, "")
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
