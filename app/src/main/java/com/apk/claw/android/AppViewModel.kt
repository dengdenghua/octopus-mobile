package com.apk.claw.android

import androidx.lifecycle.ViewModel
import com.apk.claw.android.agent.AgentConfig
import com.apk.claw.android.agent.llm.LlmClientFactory
import com.apk.claw.android.channel.Channel
import com.apk.claw.android.channel.ChannelManager
import com.apk.claw.android.channel.ChannelSetup
import com.apk.claw.android.octopus_mobile.*
import com.apk.claw.android.octopus_mobile.evolution.EvolutionEngine
import com.apk.claw.android.octopus_mobile.evolution.LessonStore
import com.apk.claw.android.octopus_mobile.evolution.TurnScorer
import com.apk.claw.android.octopus_mobile.memory.MemoryStore
import com.apk.claw.android.octopus_mobile.nerves.EventBus
import com.apk.claw.android.octopus_mobile.proactive.NotificationRelayService
import com.apk.claw.android.octopus_mobile.proactive.ProactiveRuleEngine
import com.apk.claw.android.octopus_mobile.safety.SafetyGate
import com.apk.claw.android.octopus_mobile.safety.CircuitBreaker
import com.apk.claw.android.tool.ToolRegistry
import com.apk.claw.android.utils.KVUtils
import com.apk.claw.android.utils.XLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow

class AppViewModel : ViewModel() {

    companion object {
        private const val TAG = "AppViewModel"
    }

    private var _commonInitialized = false

    // ── Octopus Mobile 组件（Phase 1 接管生命周期） ──
    var octopusClient: OctopusMobileClient? = null
        private set
    var heartbeatReporter: HeartbeatReporter? = null
        private set
    var toolDispatcher: ToolCallDispatcher? = null
        private set
    var screenStreamer: ScreenStreamer? = null
        private set
    var dualConfigWriter: DualConfigWriter? = null
        private set
    var brainSelector: BrainModeSelector? = null
        private set
    var evolutionEngine: EvolutionEngine? = null
        private set

    /** 主动规则引擎（设备状态变化 → 主动行动） */
    var proactiveEngine: ProactiveRuleEngine? = null
        private set

    /** 教训持久化存储（自进化闭环核心组件） */
    var lessonStore: LessonStore? = null
        private set

    /** 跨会话记忆存储（用户偏好/事实/上下文） */
    var memoryStore: MemoryStore? = null
        private set

    private val octopusScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── 委托管理器 ──

    private var lifecycleManager: OctopusLifecycleManager? = null

    private var connectionManager: OctopusConnectionManager? = null

    /** 连接状态（供 UI 观察，委托给 ConnectionManager） */
    val connectionState: StateFlow<ConnectionState>
        get() = connectionManager?.connectionState
            ?: kotlinx.coroutines.flow.MutableStateFlow(ConnectionState.DISCONNECTED)

    val taskOrchestrator = TaskOrchestrator(
        agentConfigProvider = { getAgentConfig() },
        onTaskFinished = { /* 刷新 */ }
    )

    private val channelSetup = ChannelSetup(taskOrchestrator = taskOrchestrator)

    val inProgressTaskMessageId: String get() = taskOrchestrator.inProgressTaskMessageId
    val inProgressTaskChannel: Channel? get() = taskOrchestrator.inProgressTaskChannel

    fun init() {
        initCommon()
        initAgent()
    }

    fun initCommon() {
        if (_commonInitialized) return
        _commonInitialized = true
    }

    fun initAgent() {
        if (!KVUtils.hasLlmConfig()) return
        taskOrchestrator.initAgent()
    }

    fun getAgentConfig(): AgentConfig {
        // 路由：默认走平台中转(共享 MiMo key + 扣积分),会员且显式选择才用自己的模型;
        // 中转未配置/未登录时回退到本地 LLM 配置(不破坏现有行为)。
        val eff = com.apk.claw.android.account.LlmRouting.effective()
        var baseUrl = eff.baseUrl
        if (baseUrl.isEmpty()) baseUrl = "https://api.deepseek.com/v1"
        // 从 LessonStore 注入已有教训到 dynamicPromptSuffix
        val promptSuffix = lessonStore?.buildPromptSection() ?: ""
        // 从 MemoryStore 注入跨会话记忆到 memoryPromptSuffix
        val memorySuffix = memoryStore?.buildPromptSection() ?: ""
        return AgentConfig.Builder()
            .apiKey(eff.apiKey)
            .baseUrl(baseUrl)
            .modelName(eff.model.ifBlank { if (eff.platform) "mimo-v2-flash" else "deepseek-chat" })
            .temperature(0.1)
            .maxIterations(60)
            .dynamicPromptSuffix(promptSuffix)
            .memoryPromptSuffix(memorySuffix)
            .build()
    }

    fun updateAgentConfig(): Boolean = taskOrchestrator.updateAgentConfig()

    fun afterInit() {
        lifecycleManager?.onAppInitialized(
            channelSetup = channelSetup,
            autoConnectAction = {
                if (connectionManager?.isConfigured() == true) {
                    connectionManager?.connect()
                }
            }
        )
    }

    // ── Octopus Mobile 连接管理（委托给 ConnectionManager） ──

    /** 初始化 Octopus Mobile 组件（由 ClawApplication 调用） */
    fun initOctopusMobile() {
        try {
            val runtimeUrl = KVUtils.getOctopusRpcUrl().ifEmpty { "ws://10.0.2.2:8765" }
            val tentacleId = "${android.os.Build.BRAND}_${android.os.Build.MODEL}"
                .replace(" ", "_").lowercase()

            octopusClient = OctopusMobileClient(
                runtimeUrl = runtimeUrl,
                tentacleId = tentacleId,
                authToken = KVUtils.getOctopusAuthToken()
            )

            val llmConfig = if (KVUtils.hasLlmConfig()) {
                LlmConfig(
                    apiUrl = "${KVUtils.getLlmBaseUrl().trimEnd('/')}/chat/completions",
                    apiKey = KVUtils.getLlmApiKey(),
                    model = KVUtils.getLlmModelName(),
                    temperature = 0.2,
                    maxTokens = 2048
                )
            } else null

            brainSelector = BrainModeSelector(
                context = ClawApplication.instance,
                rpcClient = octopusClient!!,
                toolRegistry = ToolRegistry.getInstance(),
                llmConfig = llmConfig
            )
            brainSelector!!.onModeChanged = { mode ->
                XLog.i(TAG, "BrainMode switched: $mode")
            }
            brainSelector!!.start(octopusScope, checkIntervalMs = 30_000)

            toolDispatcher = ToolCallDispatcher(octopusClient!!)
            heartbeatReporter = HeartbeatReporter(ClawApplication.instance, octopusClient!!, intervalMs = 30_000L)

            // ── 主动规则引擎初始化 ──
            proactiveEngine = ProactiveRuleEngine()
            NotificationRelayService.proactiveEngine = proactiveEngine

            screenStreamer = ScreenStreamer(octopusClient!!, heartbeatReporter!!, proactiveEngine = proactiveEngine)
            dualConfigWriter = DualConfigWriter(ClawApplication.instance, octopusClient!!, tentacleId)

            // ── 安全子系统初始化（接入 ToolRegistry 执行路径）──
            val safetyGate = SafetyGate()
            ToolRegistry.safetyGate = safetyGate

            val turnScorer = TurnScorer(ClawApplication.instance.filesDir)
            ToolRegistry.turnScorer = turnScorer

            // 断路器：60s 窗口内失败 10 次或调用 60 次则熔断 30s，防止 LLM/工具异常拖垮系统
            ToolRegistry.circuitBreaker = CircuitBreaker(
                windowSeconds = 60.0,
                maxErrorsPerWindow = 10,
                maxCallsPerWindow = 60,
                cooldownSeconds = 30.0,
            )

            // EventBus 接入：护栏拦截 → 发布事件
            val eventBus = ClawApplication.instance.eventBus
            ToolRegistry.eventBus = eventBus
            ToolRegistry.guardrail.onBlock = { tool, reason ->
                eventBus.publish(EventBus.ToolBlockedEvent(tool, reason, "guardrail"))
            }

            // EventBus 订阅：工具被拦截 → TurnScorer 记录失败
            eventBus.subscribe(EventBus.ToolBlockedEvent::class.java) { event ->
                turnScorer.record(event.toolName, success = false, reason = event.reason)
            }

            // BrainModeSelector 传给 TaskOrchestrator
            taskOrchestrator.brainSelector = brainSelector

            // ── EvolutionEngine 自进化引擎 ──
            // B1 层（TurnScorer）已接入；B2/B3 层需要 LLM 调用
            val evoLlmCall: ((String) -> String)? = if (KVUtils.hasLlmConfig()) {
                val evoClient = LlmClientFactory.create(getAgentConfig())
                val fn: (String) -> String = { prompt ->
                    val msgs = listOf(
                        dev.langchain4j.data.message.SystemMessage.from("You are a meta-evaluator for an AI agent. Be terse. Output ONLY JSON."),
                        dev.langchain4j.data.message.UserMessage.from(prompt)
                    )
                    evoClient.chat(msgs, emptyList()).text ?: ""
                }
                fn
            } else null
            // 初始化 LessonStore 并传给 EvolutionEngine 和 TaskOrchestrator
            lessonStore = LessonStore(ClawApplication.instance)
            evolutionEngine = EvolutionEngine(turnScorer, evoLlmCall, lessonStore)
            taskOrchestrator.evolutionEngine = evolutionEngine
            taskOrchestrator.lessonStore = lessonStore
            // 初始化 MemoryStore 并传给 TaskOrchestrator
            memoryStore = MemoryStore(ClawApplication.instance)
            taskOrchestrator.memoryStore = memoryStore
            XLog.i(TAG, "EvolutionEngine initialized: B1=active, B2=${if (evoLlmCall != null) "active" else "degraded"}, B3=${if (evoLlmCall != null) "available" else "unavailable"}, LessonStore=active")
            XLog.i(TAG, "MemoryStore initialized: memories=${memoryStore!!.getMemories().size}")

            // ── 创建委托管理器 ──
            lifecycleManager = OctopusLifecycleManager(ClawApplication.instance)
            connectionManager = OctopusConnectionManager(
                octopusClient = octopusClient!!,
                toolDispatcher = toolDispatcher,
                heartbeatReporter = heartbeatReporter,
                screenStreamer = screenStreamer,
                dualConfigWriter = dualConfigWriter,
                coroutineScope = octopusScope
            )

            XLog.i(TAG, "Octopus Mobile components initialized (not connected)")
            XLog.i(TAG, "Safety subsystems active: SafetyGate=${ToolRegistry.safetyGate != null}, TurnScorer=${ToolRegistry.turnScorer != null}, EventBus=${ToolRegistry.eventBus != null}")
        } catch (e: Exception) {
            XLog.e(TAG, "initOctopusMobile failed: ${e.message}", e)
        }
    }

    /** Runtime URL 是否已配置 */
    fun isRuntimeConfigured(): Boolean = connectionManager?.isConfigured() == true

    /** 连接到 Runtime */
    fun connectRuntime() {
        val client = octopusClient ?: run {
            XLog.w(TAG, "connectRuntime: client not initialized")
            return
        }
        connectionManager?.connect()
            ?: XLog.w(TAG, "connectRuntime: connectionManager not initialized")
    }

    /** 断开 Runtime */
    fun disconnectRuntime() {
        connectionManager?.disconnect()
            ?: XLog.w(TAG, "disconnectRuntime: connectionManager not initialized")
    }

    /**
     * 显示圆形悬浮窗
     */
    fun showFloatingCircle() {
        lifecycleManager?.showFloatingCircle()
            ?: XLog.w(TAG, "showFloatingCircle: lifecycleManager not initialized")
    }

    fun isTaskRunning(): Boolean = taskOrchestrator.isTaskRunning()

    fun cancelCurrentTask() = taskOrchestrator.cancelCurrentTask()

    fun startNewTask(channel: Channel, task: String, messageID: String) =
        taskOrchestrator.startNewTask(channel, task, messageID)

    private fun trySendScreenshot(channel: Channel, filePath: String, messageID: String) {
        try {
            val file = java.io.File(filePath)
            if (!file.exists()) {
                XLog.w(TAG, "截图文件不存在: $filePath")
                return
            }
            val imageBytes = file.readBytes()
            ChannelManager.sendImage(channel, imageBytes, messageID)
        } catch (e: Exception) {
            XLog.e(TAG, "发送截图失败", e)
        }
    }
}
