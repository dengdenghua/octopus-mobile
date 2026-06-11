package com.apk.claw.android.octopus_mobile

import android.content.Context
import android.util.Log
import com.apk.claw.android.octopus_mobile.browser.BrowserEngine
import com.apk.claw.android.octopus_mobile.browser.BrowserEngineFactory
import com.apk.claw.android.tool.ToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * 方案 F 决策层切换器 (BrainModeSelector).
 *
 * 两层决策：
 *  1. **远程/本地模式**：母体可达 → EXECUTOR_ONLY，否则 → LOCAL_FALLBACK
 *  2. **浏览器/手机意图**：用户说"打开网页"→自动切浏览器引擎，"打开 App"→手机自动化
 *
 * 切换是无缝的 —— 用户感觉不到.
 *
 * 关键设计（方案 F）：
 *  - **单一 SKILL.md 源**：30 个 SKILL.md 既喂本地 LLM，也喂远程母体
 *  - **零框架依赖**：本地 LLM 客户端 + ReAct 循环都是自写 < 600 行
 *  - **30s 健康检查**：自动检测母体可达性，挂了切本地
 *  - **工具名前缀剥离**：`android.tap` → `tap`（对齐 ToolRegistry 短名）
 *  - **意图自动分类**：基于关键词规则，零 LLM 调用，μs 级
 */
class BrainModeSelector(
    private val context: Context,
    private val rpcClient: OctopusMobileClient,
    private val toolRegistry: ToolRegistry,
    private val llmConfig: LlmConfig?
) {
    private val tag = "BrainModeSelector"

    /** 当前模式（线程安全） */
    private val currentMode = AtomicReference(BrainMode.EXECUTOR_ONLY)

    /** 当前活跃领域（浏览器 / 手机 / 混合） */
    private val currentDomain = AtomicReference(AutomationDomain.MOBILE)

    /** 缓存加载的 30 SKILL.md（首次加载后不变） */
    private val skillsCache: List<SkillSpec> by lazy {
        SkillManifest.loadFromAssets(context, "skills/mobile")
    }

    /** 模式变化回调（用于 UI 展示） */
    var onModeChanged: ((BrainMode) -> Unit)? = null

    /** 领域变化回调（用于 UI 展示当前是浏览器还是手机） */
    var onDomainChanged: ((AutomationDomain) -> Unit)? = null

    /** 后台健康检查协程 */
    private var healthCheckJob: Job? = null

    /** 启动健康检查循环. */
    fun start(scope: CoroutineScope, checkIntervalMs: Long = 30_000) {
        healthCheckJob?.cancel()
        healthCheckJob = scope.launch(Dispatchers.IO) {
            while (true) {
                checkAndSwitch()
                delay(checkIntervalMs)
            }
        }
    }

    /** 主动检测一次（外部触发，比如心跳超时时） */
    fun checkAndSwitch() {
        val rpcReachable = rpcClient.currentState() == ConnectionState.ONLINE
        // 母体不可达 → 切本地（即使没有 LLM 配置，也切到 LOCAL_FALLBACK 以触发"无 LLM 配置"的提示路径）
        val newMode = if (!rpcReachable) BrainMode.LOCAL_FALLBACK else BrainMode.EXECUTOR_ONLY
        if (currentMode.get() != newMode) {
            val old = currentMode.getAndSet(newMode)
            Log.i(tag, "mode switched: $old -> $newMode")
            onModeChanged?.invoke(newMode)
        }
    }

    /** 主动切换. */
    fun forceMode(mode: BrainMode) {
        if (currentMode.get() != mode) {
            val old = currentMode.getAndSet(mode)
            Log.i(tag, "force mode: $old -> $mode")
            onModeChanged?.invoke(mode)
        }
    }

    fun currentMode(): BrainMode = currentMode.get()

    /** 当前活跃领域 */
    fun currentDomain(): AutomationDomain = currentDomain.get()

    /** 当前加载的技能数（调试用） */
    fun skillCount(): Int = skillsCache.size

    /**
     * 决策入口：跑一个用户任务.
     *
     * 流程：
     *  1. 意图分类（浏览器 / 手机 / 混合）
     *  2. 根据分类自动设置 BrowserEngine（浏览器任务时）
     *  3. 根据 currentMode() 选 Local 或 Remote 决策
     */
    suspend fun decide(task: String): TaskResult {
        // 1. 意图分类
        val intent = IntentClassifier.classify(task)
        val targetDomain = when (intent.primary) {
            IntentClassifier.IntentType.BROWSER -> AutomationDomain.BROWSER
            IntentClassifier.IntentType.MOBILE -> AutomationDomain.MOBILE
            IntentClassifier.IntentType.MIXED -> AutomationDomain.MIXED
            IntentClassifier.IntentType.AMBIGUOUS -> currentDomain.get()  // 保持当前
        }

        // 2. 领域切换（如有变化）
        switchDomain(targetDomain, intent)

        // 3. 按模式决策
        return when (currentMode.get()) {
            BrainMode.EXECUTOR_ONLY -> decideRemotely(task, intent)
            BrainMode.LOCAL_FALLBACK -> decideLocally(task, intent)
        }
    }

    /**
     * 切换活跃领域，自动配置 BrowserEngine.
     */
    private fun switchDomain(domain: AutomationDomain, intent: IntentClassifier.ClassificationResult) {
        val oldDomain = currentDomain.get()
        if (oldDomain == domain) return

        currentDomain.set(domain)
        Log.i(tag, "domain switched: $oldDomain -> $domain (confidence=${intent.confidence})")
        onDomainChanged?.invoke(domain)

        // 浏览器领域：自动设置最佳引擎
        if (domain == AutomationDomain.BROWSER || domain == AutomationDomain.MIXED) {
            val engine = BrowserEngineFactory.selectBest(context)
            toolRegistry.setBrowserEngine(engine)
            Log.i(tag, "browser engine set: ${engine.name} (antiBot=${engine.antiBotScore})")
        }

        // 手机领域：清除浏览器引擎（browser tools 不再注册）
        if (domain == AutomationDomain.MOBILE) {
            // 不清除 engine，只是不注册 browser tools
            // 如果用户后续切回浏览器，再重新设置
        }
    }

    /** 强制切换领域（外部调用，如 UI 手动切换） */
    fun forceDomain(domain: AutomationDomain) {
        switchDomain(domain, IntentClassifier.ClassificationResult(domain.toIntentType(), 1.0))
    }

    /**
     * Phase 1 实现：通过 RPC 让母体决策.
     *
     * 母体下发 tool_call 列表 → OctopusMobileClient 接收 → 路由到 executeLocalTool.
     * 如果远程失败，自动降级到 LOCAL_FALLBACK（如果有 LLM 配置）.
     */
    private suspend fun decideRemotely(task: String, intent: IntentClassifier.ClassificationResult): TaskResult {
        return try {
            val result = rpcClient.executeRemoteTask(task, intent)
            when (result) {
                is RemoteTaskResult.Success -> TaskResult.Done(
                    summary = result.response,
                    totalSteps = result.steps,
                    totalUsage = result.usage
                )
                is RemoteTaskResult.Failure -> TaskResult.MaxStepsReached(
                    totalSteps = 0,
                    lastResponse = "[REMOTE] ${result.error}",
                    totalUsage = TokenUsage(0, 0, 0)
                )
            }
        } catch (e: Exception) {
            Log.e(tag, "decideRemotely failed", e)
            // 远程失败 → 自动降级到本地
            if (llmConfig != null) {
                Log.w(tag, "Remote failed, falling back to LOCAL_FALLBACK")
                forceMode(BrainMode.LOCAL_FALLBACK)
                decideLocally(task, intent)
            } else {
                TaskResult.MaxStepsReached(
                    totalSteps = 0,
                    lastResponse = "[REMOTE] Failed: ${e.message}. No LLM config for fallback.",
                    totalUsage = TokenUsage(0, 0, 0)
                )
            }
        }
    }

    private suspend fun decideLocally(task: String, intent: IntentClassifier.ClassificationResult): TaskResult {
        val cfg = llmConfig
            ?: return TaskResult.MaxStepsReached(
                totalSteps = 0,
                lastResponse = "No LLM config for LOCAL_FALLBACK mode",
                totalUsage = TokenUsage(0, 0, 0)
            )

        // 根据意图过滤技能：浏览器任务只给浏览器技能，手机任务给手机技能
        val filteredSkills = when (intent.primary) {
            IntentClassifier.IntentType.BROWSER -> skillsCache.filter { it.id.startsWith("android.browser.") }
            IntentClassifier.IntentType.MOBILE -> skillsCache.filter { !it.id.startsWith("android.browser.") }
            else -> skillsCache  // MIXED / AMBIGUOUS 给全部
        }

        val llm = LightweightLlmClient(cfg)
        val react = LightweightReAct(
            llmClient = llm,
            toolExecutor = { call -> executeLocalTool(call) }
        )
        return react.run(task, filteredSkills)
    }

    /**
     * 执行本地工具调用 —— 桥接 LightweightReAct ↔ Octopus Mobile ToolRegistry.
     *
     * 关键点：
     *  - LLM 看到的工具名是 `android.tap`（全名）
     *  - Octopus Mobile ToolRegistry 用的是 `tap`（短名）
     *  - 需要做前缀剥离
     */
    suspend fun executeLocalTool(call: ToolCall): ToolExecutionResult {
        val shortName = stripAndroidPrefix(call.name)
        // ToolRegistry.executeTool requires Map<String, Any> (non-null values).
        // ToolCall.args allows nulls, so filter them out before dispatch.
        val nonNullArgs: Map<String, Any> = call.args.filterValues { it != null }.mapValues { it.value!! }
        val result = toolRegistry.executeTool(shortName, nonNullArgs)
        return if (result.isSuccess) {
            ToolExecutionResult.Success(
                toolCallId = call.id,
                display = result.data ?: "(no data)",
                data = result.data
            )
        } else {
            ToolExecutionResult.Failure(
                toolCallId = call.id,
                display = result.error ?: "Unknown error",
                errorCode = -32603,
                errorMessage = result.error ?: "Unknown"
            )
        }
    }

    /** 把 `android.browser_navigate` 剥成 `browser_navigate`；无前缀原样返回. */
    private fun stripAndroidPrefix(name: String): String {
        return if (name.startsWith("android.")) name.removePrefix("android.") else name
    }

    fun stop() {
        healthCheckJob?.cancel()
    }
}

/** 决策模式. */
enum class BrainMode {
    /** 远程决策（默认）：通过 RPC 让母体决策 */
    EXECUTOR_ONLY,

    /** 本地决策（降级）：用轻量 LLM 客户端跑 ReAct 循环 */
    LOCAL_FALLBACK
}

/** 自动化领域. */
enum class AutomationDomain {
    /** 浏览器自动化（Web 页面操作） */
    BROWSER,

    /** 手机自动化（原生 App 操作） */
    MOBILE,

    /** 混合（可能同时涉及两者） */
    MIXED;

    fun toIntentType(): IntentClassifier.IntentType = when (this) {
        BROWSER -> IntentClassifier.IntentType.BROWSER
        MOBILE -> IntentClassifier.IntentType.MOBILE
        MIXED -> IntentClassifier.IntentType.MIXED
    }
}
