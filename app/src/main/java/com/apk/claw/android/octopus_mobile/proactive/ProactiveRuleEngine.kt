package com.apk.claw.android.octopus_mobile.proactive

import android.util.Log
import com.apk.claw.android.octopus_mobile.safety.ApprovalFlow
import com.apk.claw.android.octopus_mobile.safety.ToolRiskPolicy
import com.apk.claw.android.tool.ToolRegistry
import com.apk.claw.android.tool.ToolResult
import com.apk.claw.android.utils.KVUtils
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 主动规则引擎 —— 检测设备状态变化并自动触发动作.
 *
 * 与 ReflexRouter（用户输入 → 快速响应）不同，
 * ProactiveRuleEngine 是（设备状态变化 → 主动行动）.
 *
 * 规则示例：
 *  - 收到验证码短信 → 自动复制到剪贴板
 *  - 闹钟响了 → 自动关闭
 *  - 电量低于 20% → 通知用户
 *  - 特定 App 弹窗 → 自动关闭
 *
 * 触发源：
 *  - ScreenStreamer 的屏幕变化事件
 *  - 通知监听（NotificationListenerService）
 *  - 短信接收
 *  - 定时检查
 */
class ProactiveRuleEngine(
    private val toolRegistry: ToolRegistry = ToolRegistry
) {
    companion object {
        private const val TAG = "ProactiveEngine"
        private const val KEY_RULES = "proactive_rules"
        private const val KEY_ENABLED = "proactive_enabled"

        /** 全局开关的静态读写,供 UI(无引擎实例)切换主动规则引擎启用状态。 */
        fun isGloballyEnabled(): Boolean = KVUtils.getBoolean(KEY_ENABLED, false)
        fun setGloballyEnabled(enabled: Boolean) = KVUtils.putBoolean(KEY_ENABLED, enabled)
        private val GSON = Gson()
    }

    data class ProactiveRule(
        val id: String,
        val name: String,
        val trigger: Trigger,
        val action: Action,
        val enabled: Boolean = true,
        val cooldownMs: Long = 60_000,  // 冷却时间，避免频繁触发
        val lastTriggeredAt: Long = 0,
    )

    data class Trigger(
        val type: TriggerType,
        val pattern: String,       // 匹配模式（正则或关键词）
        val packageName: String? = null,  // 限定 App
    )

    enum class TriggerType {
        SCREEN_CHANGE,    // 屏幕内容变化
        NOTIFICATION,     // 收到通知
        SMS_RECEIVED,     // 收到短信
        BATTERY_LOW,      // 电量低
        APP_FOREGROUND,   // App 切到前台
    }

    data class Action(
        val type: ActionType,
        val toolName: String,      // 要执行的工具
        val toolParams: Map<String, Any> = emptyMap(),
        val notifyUser: Boolean = true,  // 是否通知用户
    )

    enum class ActionType {
        EXECUTE_TOOL,     // 直接执行工具
        NOTIFY_USER,      // 只通知用户
        EXECUTE_AND_NOTIFY,  // 执行并通知
    }

    data class TriggerResult(
        val ruleId: String,
        val ruleName: String,
        val actionTaken: Boolean,
        val toolResult: ToolResult?,
        val message: String,
    )

    private val rules = mutableListOf<ProactiveRule>()

    init {
        // 加载持久化规则
        loadRules()
        // 添加内置规则
        addBuiltinRules()
    }

    /** 处理屏幕变化事件 */
    fun onScreenChanged(currentApp: String, screenText: String): List<TriggerResult> {
        if (!isEnabled()) return emptyList()
        val results = mutableListOf<TriggerResult>()
        for (rule in rules.filter { it.enabled && it.trigger.type == TriggerType.SCREEN_CHANGE }) {
            if (rule.trigger.packageName != null && rule.trigger.packageName != currentApp) continue
            if (!matchPattern(rule.trigger.pattern, screenText)) continue
            if (!checkCooldown(rule)) continue

            val result = executeRule(rule)
            results.add(result)
        }
        return results
    }

    /** 处理通知事件 */
    fun onNotification(packageName: String, title: String, text: String): List<TriggerResult> {
        if (!isEnabled()) return emptyList()
        val results = mutableListOf<TriggerResult>()
        val combined = "$title $text"
        for (rule in rules.filter { it.enabled && it.trigger.type == TriggerType.NOTIFICATION }) {
            if (rule.trigger.packageName != null && rule.trigger.packageName != packageName) continue
            if (!matchPattern(rule.trigger.pattern, combined)) continue
            if (!checkCooldown(rule)) continue

            val result = executeRule(rule)
            results.add(result)
        }
        return results
    }

    /** 处理短信事件 */
    fun onSmsReceived(sender: String, body: String): List<TriggerResult> {
        if (!isEnabled()) return emptyList()
        val results = mutableListOf<TriggerResult>()
        val combined = "$sender: $body"
        for (rule in rules.filter { it.enabled && it.trigger.type == TriggerType.SMS_RECEIVED }) {
            if (!matchPattern(rule.trigger.pattern, combined)) continue
            if (!checkCooldown(rule)) continue

            // 验证码通知规则(R12 安全收敛):
            // 原行为是自动复制到剪贴板(EXECUTE clipboard set),但其他 App 可读剪贴板 → 验证码外泄面。
            // 改为仅通知用户验证码内容,由用户手动复制。
            if (rule.id == "sms_code_copy") {
                val code = extractVerificationCode(body)
                if (code == null) {
                    results.add(TriggerResult(
                        ruleId = rule.id, ruleName = rule.name, actionTaken = false,
                        toolResult = null, message = "ℹ ${rule.name}: 未从短信中识别到验证码",
                    ))
                    continue
                }
                // 更新冷却时间
                val idx = rules.indexOfFirst { it.id == rule.id }
                if (idx >= 0) {
                    rules[idx] = rule.copy(lastTriggeredAt = System.currentTimeMillis())
                }
                results.add(TriggerResult(
                    ruleId = rule.id,
                    ruleName = rule.name,
                    actionTaken = false,
                    toolResult = null,
                    message = "📋 收到验证码: $code (请手动复制)",
                ))
                continue
            }

            results.add(executeRule(rule))
        }
        return results
    }

    /** 处理电量低事件 */
    fun onBatteryLow(level: Int): List<TriggerResult> {
        if (!isEnabled()) return emptyList()
        val results = mutableListOf<TriggerResult>()
        for (rule in rules.filter { it.enabled && it.trigger.type == TriggerType.BATTERY_LOW }) {
            if (!checkCooldown(rule)) continue
            val result = executeRule(rule)
            results.add(result)
        }
        return results
    }

    /** 处理 App 前台切换 */
    fun onAppForeground(packageName: String): List<TriggerResult> {
        if (!isEnabled()) return emptyList()
        val results = mutableListOf<TriggerResult>()
        for (rule in rules.filter { it.enabled && it.trigger.type == TriggerType.APP_FOREGROUND }) {
            if (rule.trigger.packageName != null && rule.trigger.packageName != packageName) continue
            if (!matchPattern(rule.trigger.packageName ?: rule.trigger.pattern, packageName)) continue
            if (!checkCooldown(rule)) continue
            val result = executeRule(rule)
            results.add(result)
        }
        return results
    }

    fun addRule(rule: ProactiveRule) {
        if (rules.none { it.id == rule.id }) {
            rules.add(rule)
            saveRules()
        }
    }

    fun removeRule(ruleId: String) {
        rules.removeIf { it.id == ruleId }
        saveRules()
    }

    fun getRules(): List<ProactiveRule> = rules.toList()

    fun setEnabled(enabled: Boolean) {
        KVUtils.putBoolean(KEY_ENABLED, enabled)
    }

    fun isEnabled(): Boolean = KVUtils.getBoolean(KEY_ENABLED, false)

    // ── 内部方法 ──

    private fun executeRule(rule: ProactiveRule, paramOverrides: Map<String, Any>? = null): TriggerResult {
        // 更新冷却时间
        val idx = rules.indexOfFirst { it.id == rule.id }
        if (idx >= 0) {
            rules[idx] = rule.copy(lastTriggeredAt = System.currentTimeMillis())
        }

        return when (rule.action.type) {
            ActionType.EXECUTE_TOOL, ActionType.EXECUTE_AND_NOTIFY -> {
                // 安全(R12)：主动规则由不可信触发源(通知/短信/屏幕文本)自动触发。
                // 风险闸门分层：
                //  - HIGH 风险：非高级模式直接拒绝（send_sms / send_intent / file_ops 等）
                //  - MEDIUM 风险：非高级模式走人工确认（tap / swipe / input_text / clipboard 等）
                //  - LOW 风险：自动放行（只读/观察类）
                //  「高级自动化模式」开启时全部放行（专用自动化设备满血）。
                val risk = ToolRiskPolicy.riskOf(rule.action.toolName)
                val advancedMode = KVUtils.isAdvancedAutomationMode()
                val params = if (paramOverrides != null) rule.action.toolParams + paramOverrides else rule.action.toolParams

                when {
                    risk == ToolRiskPolicy.RISK_HIGH && !advancedMode -> {
                        Log.w(TAG, "[Proactive] 拒绝自动执行高危工具: ${rule.action.toolName} (规则: ${rule.name})")
                        TriggerResult(
                            ruleId = rule.id,
                            ruleName = rule.name,
                            actionTaken = false,
                            toolResult = null,
                            message = "⚠ ${rule.name}: 高危工具「${rule.action.toolName}」不允许由主动规则自动执行"
                        )
                    }
                    risk == ToolRiskPolicy.RISK_MEDIUM && !advancedMode -> {
                        // 中危工具(tap/swipe/input_text/clipboard/open_app 等)由不可信触发源自动触发时,
                        // 弹窗让设备前用户确认;无人值守(30s 超时)则拒绝。开启高级自动化模式可跳过。
                        Log.i(TAG, "[Proactive] 中危工具请求确认: ${rule.action.toolName} (规则: ${rule.name})")
                        val riskDesc = "中危工具 · 主动规则「${rule.name}」自动触发"
                        val approved = ApprovalFlow.requestApproval(
                            ToolRegistry.appContext, rule.action.toolName, params, riskDesc
                        )
                        if (!approved) {
                            TriggerResult(
                                ruleId = rule.id,
                                ruleName = rule.name,
                                actionTaken = false,
                                toolResult = null,
                                message = "⚠ ${rule.name}: 中危工具「${rule.action.toolName}」未被确认,已跳过"
                            )
                        } else {
                            val result = ToolRegistry.withUntrustedSource {
                                toolRegistry.executeTool(rule.action.toolName, params)
                            }
                            TriggerResult(
                                ruleId = rule.id,
                                ruleName = rule.name,
                                actionTaken = true,
                                toolResult = result,
                                message = if (result.isSuccess) "✓ ${rule.name}" else "✗ ${rule.name}: ${result.error}"
                            )
                        }
                    }
                    else -> {
                        // LOW 风险 或 高级自动化模式 → 直接执行(仍走不可信来源闸门审计)
                        val result = ToolRegistry.withUntrustedSource {
                            toolRegistry.executeTool(rule.action.toolName, params)
                        }
                        TriggerResult(
                            ruleId = rule.id,
                            ruleName = rule.name,
                            actionTaken = true,
                            toolResult = result,
                            message = if (result.isSuccess) "✓ ${rule.name}" else "✗ ${rule.name}: ${result.error}"
                        )
                    }
                }
            }
            ActionType.NOTIFY_USER -> {
                TriggerResult(
                    ruleId = rule.id,
                    ruleName = rule.name,
                    actionTaken = false,
                    toolResult = null,
                    message = "ℹ ${rule.name}"
                )
            }
        }
    }

    private fun checkCooldown(rule: ProactiveRule): Boolean {
        if (rule.lastTriggeredAt == 0L) return true
        return System.currentTimeMillis() - rule.lastTriggeredAt >= rule.cooldownMs
    }

    private fun matchPattern(pattern: String, text: String): Boolean {
        return try {
            Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(text)
        } catch (e: Exception) {
            text.contains(pattern, ignoreCase = true)
        }
    }

    /**
     * 从短信正文提取验证码:优先取"验证码/code/otp"等上下文附近的 4-8 位数字,
     * 退化为正文中第一个独立的 4-8 位纯数字串;识别不到返回 null。
     */
    internal fun extractVerificationCode(body: String): String? {
        Regex("(?:验证码|校验码|动态码|verification\\s*code|code|otp)\\D{0,8}(\\d{4,8})", RegexOption.IGNORE_CASE)
            .find(body)?.groupValues?.getOrNull(1)?.let { return it }
        return Regex("(?<![0-9])(\\d{4,8})(?![0-9])").find(body)?.groupValues?.getOrNull(1)
    }

    private fun addBuiltinRules() {
        // 验证码短信通知(R12 安全收敛):
        // 原行为是自动复制到剪贴板(EXECUTE clipboard set),但其他 App 可读剪贴板 → 验证码外泄面。
        // 改为仅通知用户验证码内容,由用户手动复制。onSmsReceived 中对此 id 做特殊处理。
        if (rules.none { it.id == "sms_code_copy" }) {
            rules.add(ProactiveRule(
                id = "sms_code_copy",
                name = "验证码短信通知",
                trigger = Trigger(TriggerType.SMS_RECEIVED, "验证码|验证码|code|Code|COD"),
                action = Action(ActionType.NOTIFY_USER, "",
                    emptyMap(), notifyUser = true),
                cooldownMs = 30_000,
            ))
        }
        // 电量低通知
        if (rules.none { it.id == "battery_low" }) {
            rules.add(ProactiveRule(
                id = "battery_low",
                name = "电量低提醒",
                trigger = Trigger(TriggerType.BATTERY_LOW, ""),
                action = Action(ActionType.NOTIFY_USER, "", notifyUser = true),
                cooldownMs = 300_000,
            ))
        }
        saveRules()
    }

    private fun loadRules() {
        val json = KVUtils.getString(KEY_RULES, "")
        if (json.isEmpty()) return
        try {
            val type = object : TypeToken<List<ProactiveRule>>() {}.type
            val loaded: List<ProactiveRule>? = GSON.fromJson(json, type)
            loaded?.let { rules.addAll(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load rules", e)
        }
    }

    private fun saveRules() {
        KVUtils.putString(KEY_RULES, GSON.toJson(rules))
    }
}
