package com.apk.claw.android

import android.os.PowerManager
import androidx.lifecycle.ViewModel
import com.apk.claw.android.ClawApplication.Companion.appViewModelInstance
import com.apk.claw.android.agent.AgentConfig
import com.apk.claw.android.agent.llm.LlmClientFactory
import com.apk.claw.android.channel.Channel
import com.apk.claw.android.channel.ChannelManager
import com.apk.claw.android.channel.ChannelSetup
import com.apk.claw.android.service.ForegroundService
import com.apk.claw.android.floating.FloatingCircleManager
import com.apk.claw.android.octopus_mobile.*
import com.apk.claw.android.octopus_mobile.evolution.EvolutionEngine
import com.apk.claw.android.octopus_mobile.evolution.LessonStore
import com.apk.claw.android.octopus_mobile.evolution.TurnScorer
import com.apk.claw.android.octopus_mobile.memory.MemoryStore
import com.apk.claw.android.octopus_mobile.nerves.EventBus
import com.apk.claw.android.octopus_mobile.proactive.NotificationRelayService
import com.apk.claw.android.octopus_mobile.proactive.ProactiveRuleEngine
import com.apk.claw.android.octopus_mobile.safety.SafetyGate
import com.apk.claw.android.server.ConfigServerManager
import com.apk.claw.android.service.KeepAliveJobService
import com.apk.claw.android.tool.ToolRegistry
import com.apk.claw.android.ui.home.HomeActivity
import com.apk.claw.android.utils.KVUtils
import com.apk.claw.android.utils.XLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class AppViewModel : ViewModel() {

    companion object {
        private const val TAG = "AppViewModel"
    }

    private var wakeLock: PowerManager.WakeLock? = null

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

    /** 连接状态（供 UI 观察） */
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

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
        var baseUrl = KVUtils.getLlmBaseUrl().trim()
        if (baseUrl.isEmpty()) baseUrl = "https://api.deepseek.com/v1"
        // 从 LessonStore 注入已有教训到 dynamicPromptSuffix
        val promptSuffix = lessonStore?.buildPromptSection() ?: ""
        // 从 MemoryStore 注入跨会话记忆到 memoryPromptSuffix
        val memorySuffix = memoryStore?.buildPromptSection() ?: ""
        return AgentConfig.Builder()
            .apiKey(KVUtils.getLlmApiKey())
            .baseUrl(baseUrl)
            .modelName(KVUtils.getLlmModelName().ifBlank { "deepseek-chat" })
            .temperature(0.1)
            .maxIterations(60)
            .dynamicPromptSuffix(promptSuffix)
            .memoryPromptSuffix(memorySuffix)
            .build()
    }

    fun updateAgentConfig(): Boolean = taskOrchestrator.updateAgentConfig()

    fun afterInit() {
        acquireScreenWakeLock()
        ForegroundService.start(ClawApplication.instance)
        KeepAliveJobService.schedule(ClawApplication.instance)
        ConfigServerManager.autoStartIfNeeded(ClawApplication.instance)
        if (android.provider.Settings.canDrawOverlays(ClawApplication.instance)) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                appViewModelInstance.showFloatingCircle()
            }
        }
        channelSetup.setup()
        // 自动连接 Runtime（如果已配置且启用了自动连接）
        if (isRuntimeConfigured() && KVUtils.isOctopusAutoConnect()) {
            connectRuntime()
        }
    }

    // ── Octopus Mobile 连接管理 ──

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

            XLog.i(TAG, "Octopus Mobile components initialized (not connected)")
            XLog.i(TAG, "Safety subsystems active: SafetyGate=${ToolRegistry.safetyGate != null}, TurnScorer=${ToolRegistry.turnScorer != null}, EventBus=${ToolRegistry.eventBus != null}")
        } catch (e: Exception) {
            XLog.e(TAG, "initOctopusMobile failed: ${e.message}", e)
        }
    }

    /** Runtime URL 是否已配置 */
    fun isRuntimeConfigured(): Boolean = KVUtils.getOctopusRpcUrl().isNotEmpty()

    /** 连接到 Runtime */
    fun connectRuntime() {
        val client = octopusClient ?: run {
            XLog.w(TAG, "connectRuntime: client not initialized")
            return
        }
        if (_connectionState.value == ConnectionState.ONLINE ||
            _connectionState.value == ConnectionState.CONNECTING) {
            XLog.d(TAG, "Already connected or connecting")
            return
        }

        _connectionState.value = ConnectionState.CONNECTING
        client.onStateChanged = { state ->
            _connectionState.value = state
            if (state == ConnectionState.ONLINE) {
                startSubComponents()
            }
        }
        client.connect()
        XLog.i(TAG, "Connecting to Runtime: ${KVUtils.getOctopusRpcUrl()}")
    }

    /** 断开 Runtime */
    fun disconnectRuntime() {
        stopSubComponents()
        octopusClient?.disconnect()
        _connectionState.value = ConnectionState.OFFLINE
        XLog.i(TAG, "Disconnected from Runtime")
    }

    /** 启动子组件（ONLINE 时调用） */
    private fun startSubComponents() {
        toolDispatcher?.start()
        heartbeatReporter?.start(octopusScope)
        screenStreamer?.start()
        ScreenStreamer.registerListener(screenStreamer!!)
        dualConfigWriter?.initialSync()

        // 上传 SKILL.md
        octopusClient?.let { client ->
            val skillPairs = SkillExporter.exportAllFromRegistry()
            SkillExporter.uploadToRuntime(client, skillPairs)
        }
        XLog.i(TAG, "Sub-components started (Heartbeat/ToolDispatcher/ScreenStreamer/DualConfig)")
    }

    /** 停止子组件 */
    private fun stopSubComponents() {
        toolDispatcher?.stop()
        heartbeatReporter?.stop()
        screenStreamer?.stop()
        XLog.i(TAG, "Sub-components stopped")
    }


    /**
     * 获取亮屏锁，防止息屏后无障碍服务无法操作
     */
    private fun acquireScreenWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = ClawApplication.instance.getSystemService(android.content.Context.POWER_SERVICE) as? PowerManager
            ?: return
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "OctopusMobile::ScreenWakeLock"
        ).apply {
            acquire()
        }
        XLog.i(TAG, "亮屏锁已获取")
    }

    /**
     * 释放亮屏锁
     */
    private fun releaseScreenWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                XLog.i(TAG, "亮屏锁已释放")
            }
        }
        wakeLock = null
    }

    /**
     * 显示圆形悬浮窗
     */
    fun showFloatingCircle() {
        try {
            FloatingCircleManager.show(ClawApplication.instance)
            FloatingCircleManager.onFloatClick = {
                XLog.d(TAG, "Floating circle clicked")
                bringAppToForeground()
            }
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to show floating circle: ${e.message}")
        }
    }

    /**
     * 将应用带回前台
     */
    private fun bringAppToForeground() {
        val context = ClawApplication.instance
        val intent = android.content.Intent(context, HomeActivity::class.java).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        context.startActivity(intent)
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
