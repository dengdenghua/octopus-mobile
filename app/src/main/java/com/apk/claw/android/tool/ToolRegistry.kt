package com.apk.claw.android.tool

import com.apk.claw.android.tool.impl.*
import com.apk.claw.android.tool.impl.browser.*
import com.apk.claw.android.tool.impl.mobile.*
import com.apk.claw.android.tool.impl.tv.*
import com.apk.claw.android.octopus_mobile.safety.SafetyGate
import com.apk.claw.android.octopus_mobile.safety.ToolCallGuardrailController
import com.apk.claw.android.octopus_mobile.safety.GuardrailDecision
import com.apk.claw.android.octopus_mobile.safety.GuardrailAction
import com.apk.claw.android.octopus_mobile.safety.ToolRiskPolicy
import com.apk.claw.android.octopus_mobile.ToolAuditLog
import com.apk.claw.android.octopus_mobile.evolution.TurnScorer
import com.apk.claw.android.octopus_mobile.nerves.EventBus

object ToolRegistry {

    enum class DeviceType { TV, MOBILE }

    private val tools = LinkedHashMap<String, BaseTool>()
    private val pluginTools = mutableSetOf<String>()  // 插件注册的工具名称
    var deviceType: DeviceType = DeviceType.TV
        private set

    private var browserEngine: com.apk.claw.android.octopus_mobile.browser.BrowserEngine? = null

    /** 工具调用护栏（重复失败 / 无进展检测） */
    val guardrail = ToolCallGuardrailController()

    /** 安全门（PII/Secret 扫描 + LLM 法官） */
    var safetyGate: SafetyGate? = null

    /** 回合打分器（自进化 L1 层） */
    var turnScorer: TurnScorer? = null

    /** 事件总线（模块间解耦通知） */
    var eventBus: EventBus? = null

    // ── 来源信任闸门(R2/R3/R12) ──
    // 标记一段调用来自"不可信来源"：远程母体 WS 的 tool/execute、LAN HTTP debug execute、
    // 由不可信触发源(通知/短信/屏幕文本)自动触发的主动规则。这些路径不经过 LLM agent，
    // 也没有人工确认，历史上可直接驱动最高权限工具。
    private val untrustedDepth = ThreadLocal.withInitial { 0 }

    /** 在 block 内把当前线程的工具调用标记为"不可信来源"（同步执行，结束后恢复）。 */
    fun <T> withUntrustedSource(block: () -> T): T {
        untrustedDepth.set(untrustedDepth.get() + 1)
        return try {
            block()
        } finally {
            untrustedDepth.set((untrustedDepth.get() - 1).coerceAtLeast(0))
        }
    }

    fun isUntrustedSource(): Boolean = untrustedDepth.get() > 0

    /**
     * 不可信来源调用高危工具时的确认回调（供 UI 接入"逐次人工确认"）。
     * 返回 true=放行。未注册时回退到 [com.apk.claw.android.utils.KVUtils.isRemoteHighRiskAllowed]
     * （默认 false=拦截）。
     */
    var highRiskConfirmer: ((toolName: String, params: Map<String, Any>) -> Boolean)? = null

    @JvmStatic
    fun getInstance(): ToolRegistry = this

    fun registerAllTools(type: DeviceType = DeviceType.TV) {
        deviceType = type
        tools.clear()
        registerCommonTools()
        when (type) {
            DeviceType.TV -> registerTvTools()
            DeviceType.MOBILE -> registerMobileTools()
        }
        registerBrowserTools()
        registerSystemTools()
    }

    fun setBrowserEngine(engine: com.apk.claw.android.octopus_mobile.browser.BrowserEngine) {
        browserEngine = engine
        // 重新注册 browser tools（用新 engine）
        tools.keys.removeIf { it.startsWith("browser_") }
        registerBrowserTools()
    }

    /**
     * 清除浏览器引擎引用（Activity onDestroy 时调用，避免内存泄漏）。
     */
    fun clearBrowserEngine() {
        browserEngine = null
        tools.keys.removeIf { it.startsWith("browser_") }
    }

    private fun registerCommonTools() {
        register(GetScreenInfoTool())
        register(LookAtScreenTool())
        register(FindNodeInfoTool())
        register(InputTextTool())
        register(SystemKeyTool())
        register(OpenAppTool())
        register(GetInstalledAppsTool())
        register(TakeScreenshotTool())
        register(WaitTool())
        register(RepeatActionsTool())
        register(ClipboardTool())
        register(SendFileTool())
        register(FinishTool())
        // 企业版 PM 编程接入(D①):未配置 octopus.pm.url 时工具会返回错误而非崩溃。
        register(CreatePmTaskTool())
        register(ListPmProjectsTool())
    }

    private fun registerTvTools() {
        register(DpadUpTool())
        register(DpadDownTool())
        register(DpadLeftTool())
        register(DpadRightTool())
        register(DpadCenterTool())
        register(VolumeUpTool())
        register(VolumeDownTool())
        register(PressMenuTool())
        register(PressPowerTool())
    }

    private fun registerMobileTools() {
        register(TapTool())
        register(LongPressTool())
        register(SwipeTool())
        register(ScrollToFindTool())
        register(SearchAppInStoreTool())
        // Path A: 移动端专属系统工具
        register(ReadSmsTool())
        register(SendSmsTool())
    }

    /** Path A: 系统级工具（Intent 通信 + 个人上下文），TV 和 MOBILE 均可用 */
    private fun registerSystemTools() {
        register(SendIntentTool())
        register(ReadCalendarTool())
        register(GetUsageStatsTool())

        // Extendroid 式多窗口工具（需要 Shizuku）
        register(LaunchFreeformTool())
        register(ResizeWindowTool())
        register(GetWindowInfoTool())

        // AI NAS 文件管理工具（需要 Shizuku）
        register(BrowseFilesTool())
        register(SearchFilesTool())
        register(FileOpsTool())
        register(AppBackupTool())

        // UI 导航知识图谱工具(明确指 impl/NavigateTool.kt,
        // 跟 impl/browser/BrowserTools.kt 里的 NavigateTool 重名)
        register(com.apk.claw.android.tool.impl.NavigateTool())

        // 媒体播放器工具（mpv 引擎）
        register(MediaTools())

        // 100 行扩展示例工具（EXTENDING.md）
        HelloWorldTools.registerAll()
    }

    private fun registerBrowserTools() {
        val engine = browserEngine ?: return  // 没有 engine 就不注册 browser tools
        register(com.apk.claw.android.tool.impl.browser.NavigateTool(engine))
        register(GetDomTool(engine))
        register(BrowserClickTool(engine))
        register(BrowserTypeTool(engine))
        register(BrowserScreenshotTool(engine))
        register(BrowserEvaluateTool(engine))
        register(InstallExtensionTool(engine))
    }

    fun register(tool: BaseTool) {
        tools[tool.getName()] = tool
    }

    /**
     * 注册插件工具（带标记）。
     */
    fun registerPluginTool(tool: BaseTool) {
        val name = tool.getName()
        tools[name] = tool
        pluginTools.add(name)
    }

    /**
     * 注销指定名称的工具。
     */
    fun unregister(name: String) {
        tools.remove(name)
        pluginTools.remove(name)
    }

    /**
     * 检查指定工具是否由插件提供。
     */
    fun isPluginTool(name: String): Boolean = pluginTools.contains(name)

    fun getTool(name: String): BaseTool? = tools[name]

    fun getDisplayName(name: String): String = tools[name]?.getDisplayName() ?: name

    fun getAllTools(): List<BaseTool> = tools.values.toList()

    /** 工具是否启用(用户可在「技能」页停用非核心工具,Agent 工具规格据此过滤)。 */
    fun isToolEnabled(name: String): Boolean =
        name !in com.apk.claw.android.utils.KVUtils.getDisabledTools()

    fun executeTool(name: String, params: Map<String, Any>): ToolResult {
        val tool = tools[name] ?: return ToolResult.error("Unknown tool: $name")
        val auditStartMs = System.currentTimeMillis()
        val auditRisk = ToolRiskPolicy.riskOf(name)
        val auditParams = if (ToolRiskPolicy.shouldAudit(name)) {
            ToolRiskPolicy.summarizeParams(params)
        } else {
            ""
        }

        fun audited(result: ToolResult, blockedBy: String? = null): ToolResult {
            if (ToolRiskPolicy.shouldAudit(name)) {
                val resultText = if (result.isSuccess) result.data else result.error
                val duration = System.currentTimeMillis() - auditStartMs
                ToolAuditLog.record(
                    ToolAuditLog.Entry(
                        id = "tool_${auditStartMs}_${name}",
                        ts = auditStartMs,
                        toolName = name,
                        risk = auditRisk,
                        params = auditParams,
                        success = result.isSuccess,
                        result = ToolRiskPolicy.summarizeResult(resultText),
                        blockedBy = blockedBy,
                        durationMs = duration,
                    )
                )
                eventBus?.publish(EventBus.ToolAuditEvent(name, auditRisk, result.isSuccess, blockedBy, duration))
            }
            return result
        }

        if (!isToolEnabled(name)) {
            eventBus?.publish(EventBus.ToolBlockedEvent(name, "tool_disabled", "settings"))
            return audited(ToolResult.error("工具已停用: $name"), blockedBy = "settings")
        }

        // ── 高危工具来源闸门(R2/R12)：远程/自动来源调用 HIGH_RISK 工具需确认，默认拦截 ──
        // 「高级自动化模式」开启时完全放行（专用自动化设备满血）。
        if (!com.apk.claw.android.utils.KVUtils.isAdvancedAutomationMode()
            && isUntrustedSource() && ToolRiskPolicy.riskOf(name) == ToolRiskPolicy.RISK_HIGH) {
            val approved = highRiskConfirmer?.invoke(name, params)
                ?: com.apk.claw.android.utils.KVUtils.isRemoteHighRiskAllowed()
            if (!approved) {
                eventBus?.publish(EventBus.ToolBlockedEvent(name, "high_risk_untrusted", "policy"))
                return audited(
                    ToolResult.error("高危工具「$name」来自远程或自动触发来源，已被安全策略拦截（需用户确认，或在设置中显式允许远程高危调用）。"),
                    blockedBy = "policy",
                )
            }
        }

        // ── 安全门检查（PII/Secret 扫描）──
        safetyGate?.let { gate ->
            val verdict = gate.checkToolCall(name, params)
            if (verdict.isBlocked) {
                eventBus?.publish(EventBus.ToolBlockedEvent(name, verdict.reason, "safety"))
                return audited(ToolResult.error("安全拦截: ${verdict.reason}"), blockedBy = "safety")
            }
        }

        // ── 护栏预检（只读，重复失败 / 同工具失败达阈值则拦截）──
        val preCheck = guardrail.precheck(name, params)
        if (preCheck.shouldHalt) {
            eventBus?.publish(EventBus.ToolBlockedEvent(name, preCheck.message, "guardrail"))
            return audited(ToolResult.error("护栏拦截: ${preCheck.message}"), blockedBy = "guardrail")
        }

        // ── 执行工具 ──
        val result = try {
            tool.executeWithWaitAfter(params)
        } catch (e: Exception) {
            ToolResult.error("Tool execution failed: ${e.message}")
        }

        // ── 护栏观察结果（每次调用只在此处记录一次，避免重复计数）──
        val finalResult: ToolResult = if (!result.isSuccess) {
            val failCheck = guardrail.observe(name, params, result.data ?: result.error, failed = true)
            if (failCheck.action == GuardrailAction.WARN) {
                // 警告但不阻止后续执行：保持失败语义，仅把警告附加到错误信息
                ToolResult.error("${result.error} [⚠️ ${failCheck.message}]")
            } else {
                result
            }
        } else {
            guardrail.observe(name, params, result.data, failed = false)
            result
        }

        // ── 自进化打分（无论是否 WARN 都记录）──
        turnScorer?.record(name, success = finalResult.isSuccess, reason = finalResult.data ?: finalResult.error ?: "")

        return audited(finalResult)
    }
}
