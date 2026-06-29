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
        "launch_freeform",
        "resize_window",
        "browser_install_extension",
        "browser_evaluate",
        // install_app: 当前无对应已注册工具（孤儿技能已删），保留为前向兼容——
        // 若该能力以插件/技能形式重新出现，默认仍按高危闸门处理。见 ToolRiskPolicyCoverageTest。
        "install_app",
        "send_file",
    )

    val MEDIUM_RISK_TOOLS: Set<String> = setOf(
        "tap",
        "long_press",
        "swipe",
        "input_text",
        "clipboard",
        "open_app",
        "system_key",
        "media_player",
        "browse_files",
        "search_files",
        "read_sms",
        "read_calendar",
        "get_usage_stats",
    )

    /**
     * 显式声明为低风险（不审计、不过高危来源闸门）的已注册工具白名单。
     *
     * 作用：[ToolRiskPolicyCoverageTest] 断言「每个已注册工具都必须出现在 HIGH/MEDIUM/LOW 之一」，
     * 从而堵住「新增工具未分类 → [riskOf] 静默默认 LOW → 绕过审计与来源闸门」这条「因遗漏而不安全」的漂移路径。
     *
     * 重要：本集合**仅保持现状、不改变运行时行为**（这些工具此前即默认 LOW）。其中标注 ⚠ 的条目
     * 在风险上存疑（状态变更/外部写入/可重放），应由安全专项复核是否上调到 MEDIUM/HIGH——
     * 列于此处不代表「已认定安全」，只代表「当前分类为 LOW，且已被纳入漂移守护」。
     */
    val KNOWN_LOW_RISK_TOOLS: Set<String> = setOf(
        // 只读 / 观察类（确为低风险）
        "get_screen_info", "look_at_screen", "find_node_info", "get_installed_apps",
        "get_window_info", "take_screenshot", "browser_get_dom", "browser_screenshot",
        "current_time", "device_info", "echo_observe", "list_pm_projects",
        // 控制 / 无副作用
        "finish", "wait", "hello_world",
        // 滚动 / 检索（轻量、低危）
        "scroll_to_find", "search_app_in_store",
        // ⚠ 输入按键事件（与 system_key 同类，安全专项可考虑上调 MEDIUM）
        "dpad_up", "dpad_down", "dpad_left", "dpad_right", "dpad_center",
        "press_menu", "press_power", "volume_up", "volume_down",
        // ⚠ 状态变更 / 外部写入（安全专项复核）
        "create_pm_task", "echo_act", "echo_bind",
        "navigate", "tap_by_vision", "repeat_actions",
        // ⚠ 浏览器交互（同族的 browser_evaluate/browser_install_extension 已列 HIGH）
        "browser_navigate", "browser_click", "browser_type",
    )

    /** 已知未注册但有意保留在风险名单中的工具名（前向兼容），供漂移守护排除。 */
    val INTENTIONAL_UNREGISTERED: Set<String> = setOf("install_app")

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

    fun isSensitiveKey(key: String): Boolean {
        val lower = key.lowercase()
        return SENSITIVE_KEY_PARTS.any { lower.contains(it) }
    }
}
