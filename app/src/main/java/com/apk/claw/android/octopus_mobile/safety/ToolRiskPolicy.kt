package com.apk.claw.android.octopus_mobile.safety

/**
 * Central policy for tool risk labels and audit-safe summaries.
 *
 * Strong permissions stay available. This only classifies and redacts metadata
 * so powerful tool calls can be reviewed later.
 */
object ToolRiskPolicy {

    const val RISK_LOW = "low"
    const val RISK_MEDIUM = "medium"
    const val RISK_HIGH = "high"

    val HIGH_RISK_TOOLS: Set<String> = setOf(
        "send_sms",
        "send_intent",
        "file_ops",
        "backup_app",
        "app_backup",
        "launch_freeform",
        "resize_window",
        "browser_install_extension",
        "browser_evaluate",
        "install_app",
        "send_file",
    )

    val MEDIUM_RISK_TOOLS: Set<String> = setOf(
        "tap",
        "long_press",
        "swipe",
        "input_text",
        "clipboard",
        "set_clipboard",
        "open_app",
        "system_key",
        "media_player",
        "browse_files",
        "search_files",
        "read_sms",
        "read_calendar",
        "get_usage_stats",
    )

    private val SENSITIVE_KEY_PARTS = listOf(
        "key", "token", "secret", "password", "passwd", "pwd",
        "authorization", "cookie", "credential", "api"
    )

    fun riskOf(toolName: String): String = when (toolName) {
        in HIGH_RISK_TOOLS -> RISK_HIGH
        in MEDIUM_RISK_TOOLS -> RISK_MEDIUM
        else -> RISK_LOW
    }

    fun shouldAudit(toolName: String): Boolean = riskOf(toolName) != RISK_LOW

    fun summarizeParams(params: Map<String, Any>, maxValueChars: Int = 160): String {
        if (params.isEmpty()) return "{}"
        return params.entries
            .sortedBy { it.key }
            .joinToString(prefix = "{", postfix = "}") { (key, value) ->
                val redacted = if (isSensitiveKey(key)) "<redacted>" else value.toString()
                "$key=${redacted.take(maxValueChars)}"
            }
            .take(1000)
    }

    fun summarizeResult(result: String?, maxChars: Int = 240): String =
        result?.replace('\n', ' ')?.take(maxChars).orEmpty()

    private fun isSensitiveKey(key: String): Boolean {
        val lower = key.lowercase()
        return SENSITIVE_KEY_PARTS.any { lower.contains(it) }
    }
}
