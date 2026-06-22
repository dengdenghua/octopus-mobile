package com.apk.claw.android.agent

import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import android.graphics.Bitmap
import android.util.Base64
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.R
import com.apk.claw.android.agent.langchain.LangChain4jToolBridge
import com.apk.claw.android.agent.llm.LlmClient
import com.apk.claw.android.agent.llm.LlmClientFactory
import com.apk.claw.android.agent.llm.LlmResponse
import com.apk.claw.android.agent.llm.StreamingListener
import com.apk.claw.android.octopus_mobile.memory.ContextCompressor
import com.apk.claw.android.service.ClawAccessibilityService
import com.apk.claw.android.tool.ToolRegistry
import com.apk.claw.android.tool.impl.GetScreenInfoTool
import com.apk.claw.android.tool.ToolResult
import com.apk.claw.android.utils.XLog
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.ImageContent
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.TextContent
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.agent.tool.ToolExecutionRequest
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.LinkedList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class DefaultAgentService : AgentService {

    companion object {
        private const val TAG = "AgentService"
        private val GSON = Gson()

        /** LLM API 调用失败时的最大重试次数 */
        private const val MAX_API_RETRIES = 3
        /** 死循环检测：滑动窗口大小 */
        private const val LOOP_DETECT_WINDOW = 4
        /** 死循环检测：连续触发 N 次后强制 finish，避免无限消耗迭代 */
        private const val MAX_LOOP_WARNINGS = 3

        /** base64 图片最大宽度，超过则等比缩放 */
        private const val VISION_MAX_WIDTH = 720
        /** JPEG 压缩质量，用于 VLM 图片 */
        private const val VISION_JPEG_QUALITY = 50

        /** 是否将网络请求/响应原始数据输出到沙盒缓存文件，方便调试 */
        @JvmField
        var FILE_LOGGING_ENABLED = false
        @JvmField
        var FILE_LOGGING_CACHE_DIR: File? = null
    }

    private lateinit var config: AgentConfig
    private lateinit var llmClient: LlmClient
    private lateinit var toolSpecs: List<dev.langchain4j.agent.tool.ToolSpecification>
    private var executor: ExecutorService? = null
    private val running = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)

    override fun initialize(config: AgentConfig) {
        this.config = config
        this.llmClient = LlmClientFactory.create(config)
        this.toolSpecs = LangChain4jToolBridge.buildToolSpecifications()
        this.executor = Executors.newSingleThreadExecutor()
        XLog.i(TAG, "Agent initialized: provider=${config.provider}, model=${config.modelName}, streaming=${config.streaming}")
    }

    override fun updateConfig(config: AgentConfig) {
        if (running.get()) {
            cancel()
            XLog.w(TAG, "Task was running during config update, cancelled")
        }
        executor?.shutdownNow()
        initialize(config)
        XLog.i(TAG, "Agent config updated, new model: ${config.modelName}")
    }

    /** 本次运行是否来自不可信来源（LAN 网页控制台 / 聊天渠道）。 */
    @Volatile
    private var untrustedRun = false

    /** 不可信来源运行时，把工具调用包进来源闸门：高危工具默认拦截，满血/远程放行时通过。 */
    private fun execTool(toolName: String, params: Map<String, Any>): com.apk.claw.android.tool.ToolResult {
        val reg = ToolRegistry.getInstance()
        return if (untrustedRun) ToolRegistry.withUntrustedSource { reg.executeTool(toolName, params) }
        else reg.executeTool(toolName, params)
    }

    override fun executeTask(userPrompt: String, callback: AgentCallback, untrusted: Boolean) {
        if (running.get()) {
            callback.onError(0, IllegalStateException("Agent is already running a task"), 0)
            return
        }
        // 未 initialize() 就调用会让 executor 为 null；用 ?.submit 会静默吞掉任务，
        // 导致 running 永远卡 true。这里显式拦截并回报错误。
        val exec = executor
        if (exec == null) {
            callback.onError(0, IllegalStateException("Agent not initialized — call initialize() first"), 0)
            return
        }

        running.set(true)
        cancelled.set(false)
        untrustedRun = untrusted

        try {
            exec.submit {
                try {
                    runAgentLoop(userPrompt, callback)
                } catch (e: Exception) {
                    XLog.e(TAG, "Agent execution error", e)
                    callback.onError(0, e, 0)
                } finally {
                    running.set(false)
                }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            // executor 已 shutdown（如配置更新竞态）：复位状态并回报，避免卡死。
            running.set(false)
            callback.onError(0, e, 0)
        }
    }

    // ==================== VLM 视觉理解 ====================

    /**
     * 系统弹窗阻断时，截图让 VLM 分析弹窗内容.
     *
     * @param messages 当前对话历史
     * @return true 表示 VLM 已分析并添加了指导消息，可以继续执行；false 表示无法处理
     */
    private fun handleSystemDialogWithVision(
        messages: MutableList<ChatMessage>,
        callback: AgentCallback,
        iterations: Int
    ): Boolean {
        // 1. 截图
        val service = ClawAccessibilityService.getInstance()
        if (service == null) {
            XLog.w(TAG, "Accessibility service not available for VLM screenshot")
            return false
        }

        val bitmap: Bitmap = service.takeScreenshot(5000) ?: run {
            XLog.w(TAG, "Failed to take screenshot for VLM analysis")
            return false
        }

        try {
            // 2. 缩放并压缩为 JPEG base64
            var scaledBitmap = bitmap
            if (bitmap.width > VISION_MAX_WIDTH) {
                val scale = VISION_MAX_WIDTH.toFloat() / bitmap.width
                val newHeight = Math.round(bitmap.height * scale)
                scaledBitmap = Bitmap.createScaledBitmap(bitmap, VISION_MAX_WIDTH, newHeight, true)
                if (scaledBitmap !== bitmap) bitmap.recycle()
            }

            val baos = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, VISION_JPEG_QUALITY, baos)
            if (scaledBitmap !== bitmap) scaledBitmap.recycle()
            val base64Str = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)

            // 3. 构建包含图片的 UserMessage，让 LLM 分析弹窗
            val visionMessage = UserMessage.from(
                TextContent.from(
                    "[系统提示] 检测到系统弹窗阻断了操作。请分析截图中的弹窗内容，判断弹窗类型并决定如何处理。" +
                    "如果是可以关闭的弹窗（广告、权限请求、更新提示、协议等），请在下一轮操作中关闭它。" +
                    "如果是需要用户介入的弹窗（登录、付费等），请调用 finish 说明原因。" +
                    "如果无法判断弹窗内容，也请调用 finish 说明。"
                ),
                ImageContent.from("data:image/jpeg;base64,$base64Str")
            )
            messages.add(visionMessage)

            // 4. 调用 LLM 分析弹窗
            val llmResponse: LlmResponse
            try {
                llmResponse = chatWithRetry(messages, callback, iterations)
            } catch (e: Exception) {
                XLog.e(TAG, "VLM analysis failed", e)
                return false
            }

            // 5. 将 LLM 的分析结果添加到消息历史
            val aiMessage = if (llmResponse.hasToolExecutionRequests()) {
                if (llmResponse.text.isNullOrEmpty()) {
                    AiMessage.from(llmResponse.toolExecutionRequests)
                } else {
                    AiMessage.from(llmResponse.text, llmResponse.toolExecutionRequests)
                }
            } else {
                AiMessage.from(llmResponse.text ?: "")
            }
            messages.add(aiMessage)

            // 推送 VLM 分析内容
            if (!config.streaming && !llmResponse.text.isNullOrEmpty()) {
                callback.onContent(iterations, llmResponse.text)
            }

            // 如果 LLM 决定调用工具（如点击关闭按钮），说明它可以处理
            if (llmResponse.hasToolExecutionRequests()) {
                XLog.i(TAG, "VLM decided to handle the dialog with tool calls")
                // 执行 LLM 决定的工具调用
                for (toolRequest in llmResponse.toolExecutionRequests) {
                    if (cancelled.get()) return false

                    val toolName = toolRequest.name() ?: ""
                    val displayName = ToolRegistry.getInstance().getDisplayName(toolName)
                    val toolArgs = toolRequest.arguments() ?: "{}"
                    callback.onToolCall(iterations, toolName, displayName, toolArgs)

                    val mapType = object : TypeToken<Map<String, Any>>() {}.type
                    val params: Map<String, Any> = try {
                        GSON.fromJson(toolArgs, mapType) ?: emptyMap()
                    } catch (e: Exception) {
                        XLog.w(TAG, "VLM tool args parse failed for $toolName: ${e.message}")
                        emptyMap()
                    }

                    val toolResult = execTool(toolName, params)
                    val paramsString = if (params.isEmpty()) "" else params.toString()
                    callback.onToolResult(iterations, toolName, displayName, paramsString, toolResult)

                    // 添加工具结果到消息（排除 imageBase64 以节省 token）
                    val vlmResultForJson = mapOf(
                        "isSuccess" to toolResult.isSuccess,
                        "data" to toolResult.data,
                        "error" to toolResult.error
                    )
                    val resultJson = GSON.toJson(vlmResultForJson)
                    messages.add(ToolExecutionResultMessage.from(toolRequest, resultJson))

                    // 如果 LLM 调用了 finish，说明它认为无法处理
                    if (toolName == "finish") {
                        XLog.i(TAG, "VLM decided to finish task")
                        return false
                    }
                }
                return true
            }

            // LLM 没有调用工具，只返回了文本分析，继续让主循环处理
            XLog.i(TAG, "VLM returned text analysis without tool calls")
            return true

        } catch (e: Exception) {
            XLog.e(TAG, "Error during VLM analysis", e)
            if (!bitmap.isRecycled) bitmap.recycle()
            return false
        }
    }

    // ==================== 环境预检 ====================

    private fun preCheck(): String? {
        if (ClawAccessibilityService.getInstance() == null) {
            return ClawApplication.instance.getString(R.string.agent_accessibility_not_enabled)
        }
        return null
    }

    // ==================== 设备上下文 ====================

    private fun buildDeviceContext(): String {
        val app = ClawApplication.instance
        val sb = StringBuilder()
        sb.append("\n\n## 设备信息\n")
        sb.append("- 品牌: ").append(Build.BRAND).append("\n")
        sb.append("- 型号: ").append(Build.MODEL).append("\n")
        sb.append("- Android 版本: ").append(Build.VERSION.RELEASE)
            .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")

        try {
            val wm = app
                .getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            sb.append("- 屏幕分辨率: ").append(dm.widthPixels).append("x").append(dm.heightPixels).append("\n")
        } catch (e: Exception) {
            XLog.w(TAG, "Failed to get display metrics", e)
        }

        sb.append("- 已注册工具数: ").append(ToolRegistry.getAllTools().size).append("\n")

        val appName = try {
            val appInfo = app.packageManager.getApplicationInfo(app.packageName, 0)
            app.packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            XLog.w(TAG, "Failed to get application label", e)
            "CoPaw"
        }
        sb.append("\n## 本应用信息\n")
        sb.append("- 应用名: ").append(appName).append("\n")
        sb.append("- 包名: ").append(app.packageName).append("\n")
        sb.append("- 当用户提到'自己/本应用/这个应用'时，指的就是上述应用\n")

        return sb.toString()
    }

    // ==================== LLM 调用（带重试） ====================

    private fun chatWithRetry(messages: List<ChatMessage>, callback: AgentCallback, iteration: Int): LlmResponse {
        var lastException: Exception? = null
        for (attempt in 0 until MAX_API_RETRIES) {
            if (cancelled.get()) throw RuntimeException(ClawApplication.instance.getString(R.string.agent_task_cancelled))
            try {
                val response = if (config.streaming) {
                    llmClient.chatStreaming(messages, toolSpecs, object : StreamingListener {
                        override fun onPartialText(token: String) {
                            callback.onContent(iteration, token)
                        }
                        override fun onComplete(response: LlmResponse) {}
                        override fun onError(error: Throwable) {}
                    })
                } else {
                    llmClient.chat(messages, toolSpecs)
                }
                // 网关上游 5xx 常表现为 HTTP 200、但流式 body 是 {"error":...}，被解析层吞成"空回复"
                // （无正文、无工具调用）。把它当作可重试的瞬时错误，复用下方指数退避，而不是误判为"任务已完成"。
                if (response.text.isNullOrEmpty() && !response.hasToolExecutionRequests()) {
                    throw RuntimeException(ClawApplication.instance.getString(R.string.agent_empty_response))
                }
                return response
            } catch (e: Exception) {
                lastException = e
                // 认证失败 / 额度不足 / 权限拒绝：不重试，立即抛出
                if (isAuthOrQuotaError(e)) {
                    throw e
                }
                val delay = (Math.pow(2.0, attempt.toDouble()) * 1000).toLong()
                // 加入 jitter，避免多客户端同步重试触发服务端限流雪崩
                val jitter = (Math.random() * 300).toLong()
                XLog.w(TAG, "LLM API call failed (attempt ${attempt + 1}/$MAX_API_RETRIES), retrying in ${delay + jitter}ms: ${e.message}")
                try {
                    Thread.sleep(delay + jitter)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                }
            }
        }
        throw lastException!!
    }

    /**
     * 判断异常是否为认证失败 / 额度不足 / 权限拒绝（不重试）.
     *
     * 优先基于 LangChain4j 的 [dev.langchain4j.exception.HttpException.statusCode]，
     * 兜底用更严格的正则匹配（避免 "request id 40123" 这类误判）。
     */
    private fun isAuthOrQuotaError(e: Throwable): Boolean {
        // 1. 优先基于 HttpException 的状态码判断（最可靠）
        if (e is dev.langchain4j.exception.HttpException) {
            val code = e.statusCode()
            return code == 401 || code == 403
        }
        // 2. 兜底：用更严格的正则匹配 HTTP 状态码，避免 "40123" 这类数字误判
        val msg = e.message ?: return false
        // 匹配 "HTTP 401"、"status=403"、"code: 401" 等明确模式，或 "insufficient_quota" 等明确文案
        val authPattern = Regex("""(?:HTTP|status|code)\D*(401|403)\b""", RegexOption.IGNORE_CASE)
        return authPattern.containsMatchIn(msg) ||
               msg.contains("insufficient_quota", ignoreCase = true) ||
               msg.contains("invalid_api_key", ignoreCase = true)
    }

    // ==================== 死循环检测 ====================

    private data class RoundFingerprint(val screenHash: Int, val toolCall: String)

    private fun isStuckInLoop(history: LinkedList<RoundFingerprint>): Boolean {
        if (history.size < LOOP_DETECT_WINDOW) return false
        val first = history.first()
        return history.all { it == first }
    }

    // ==================== 上下文压缩 ====================

    /** ContextCompressor 实例，提供分层压缩策略 */
    private val contextCompressor = ContextCompressor()

    /** 保护区：最近 N 轮完整保留 */
    private val KEEP_RECENT_ROUNDS = 3

    /** 大输出观察类工具 → 压缩后占位符 */
    private val OBSERVATION_PLACEHOLDERS = mapOf(
        "get_screen_info" to "[屏幕信息已省略]",
        "take_screenshot" to "[截图结果已省略]",
        "find_node_info" to "[节点查找结果已省略]",
        "get_installed_apps" to "[应用列表已省略]",
        "scroll_to_find" to "[滚动查找结果已省略]"
    )

    /**
     * 发送前压缩历史消息，节省 input token：
     * - get_screen_info：全局只保留最新一条完整结果
     * - 保护区（最近 KEEP_RECENT_ROUNDS 轮）：完整保留
     * - 保护区外：AI thinking 不动，tool result 压缩为一行摘要
     *
     * 注：ContextCompressor.compress() 未被调用，仅复用其 config（maxChars/chunkTruncateChars）。
     * 如需启用完整的"older 消息汇总为 [Context Summary]"策略，可替换为 compressor.compress()。
     */
    private fun compressHistoryForSend(messages: MutableList<ChatMessage>) {
        // 压缩前统计总字符数
        val charsBefore = countMessagesChars(messages)
        val msgCountBefore = messages.size

        // 0. get_screen_info 特殊处理：无视分级，全局只保留最新一条完整结果
        val screenPlaceholder = OBSERVATION_PLACEHOLDERS["get_screen_info"]!!
        val lastScreenIdx = messages.indexOfLast {
            it is ToolExecutionResultMessage && it.toolName() == "get_screen_info"
        }
        for (i in messages.indices) {
            val msg = messages[i]
            if (msg is ToolExecutionResultMessage
                && msg.toolName() == "get_screen_info"
                && i != lastScreenIdx
                && msg.text() != screenPlaceholder
            ) {
                messages[i] = ToolExecutionResultMessage.from(msg.id(), msg.toolName(), screenPlaceholder)
            }
        }

        // 1. 找出所有 AiMessage 的索引，每个代表一轮
        val aiIndices = messages.indices.filter { messages[it] is AiMessage }
        if (aiIndices.size <= KEEP_RECENT_ROUNDS) return

        val totalRounds = aiIndices.size

        for (roundIdx in aiIndices.indices) {
            val roundFromEnd = totalRounds - roundIdx
            if (roundFromEnd <= KEEP_RECENT_ROUNDS) break // 保护区

            val aiIndex = aiIndices[roundIdx]

            // 收集本轮的 ToolExecutionResultMessage 索引
            var j = aiIndex + 1
            while (j < messages.size && messages[j] is ToolExecutionResultMessage) {
                compressToolResultMessage(messages, j)
                j++
            }
        }

        // 压缩后统计
        val charsAfter = countMessagesChars(messages)
        val saved = charsBefore - charsAfter
        if (saved > 0) {
            XLog.i(TAG, "上下文压缩: ${charsBefore}→${charsAfter}字符, 节省${saved}字符(${saved * 100 / charsBefore}%), 轮数=${aiIndices.size}")
        }

        // ── ContextCompressor 二次压缩层 ──
        // 如果现有压缩后总字符仍超过 maxChars，对保护区外的 AiMessage 文本做截断
        if (charsAfter > contextCompressor.config.maxChars && aiIndices.size > KEEP_RECENT_ROUNDS) {
            applyContextCompressorTruncation(messages, aiIndices, KEEP_RECENT_ROUNDS)
            val charsAfterSecondPass = countMessagesChars(messages)
            val secondSaved = charsAfter - charsAfterSecondPass
            if (secondSaved > 0) {
                val ratio = (charsAfterSecondPass.toFloat() / charsAfter * 100).toInt()
                XLog.i(TAG, "ContextCompressor 二次压缩: ${charsAfter}→${charsAfterSecondPass}字符 (${ratio}%)")
            }
        }
    }

    /** 统计消息列表总字符数（抽取自重复代码） */
    private fun countMessagesChars(messages: List<ChatMessage>): Int =
        messages.sumOf { msg ->
            when (msg) {
                is AiMessage -> (msg.text()?.length ?: 0) + (msg.toolExecutionRequests()?.sumOf { it.arguments()?.length ?: 0 } ?: 0)
                is ToolExecutionResultMessage -> msg.text().length
                is UserMessage -> msg.singleText().length
                is SystemMessage -> msg.text().length
                else -> 0
            }
        }

    /**
     * ContextCompressor 二次压缩：对保护区外的 UserMessage 文本做截断.
     * 这是对现有 tool result 压缩的补充层。
     */
    private fun applyContextCompressorTruncation(
        messages: MutableList<ChatMessage>,
        aiIndices: List<Int>,
        keepRecent: Int
    ) {
        val totalRounds = aiIndices.size
        val truncLimit = contextCompressor.config.chunkTruncateChars

        for (roundIdx in aiIndices.indices) {
            val roundFromEnd = totalRounds - roundIdx
            if (roundFromEnd <= keepRecent) break // 保护区不动

            val aiIndex = aiIndices[roundIdx]
            // 截断本轮 AiMessage 的文本部分（不动 toolExecutionRequests）
            val aiMsg = messages[aiIndex] as AiMessage
            val aiText = aiMsg.text() ?: ""
            if (aiText.length > truncLimit) {
                val truncated = aiText.take(truncLimit) + "...[compressed]"
                messages[aiIndex] = if (aiMsg.toolExecutionRequests()?.isNotEmpty() == true) {
                    AiMessage.from(truncated, aiMsg.toolExecutionRequests())
                } else {
                    AiMessage.from(truncated)
                }
            }
        }
    }

    /** 压缩 Tool Result：观察类工具用占位符，其他工具截取摘要 */
    private fun compressToolResultMessage(messages: MutableList<ChatMessage>, index: Int) {
        val msg = messages[index] as ToolExecutionResultMessage
        val text = msg.text()
        if (text.length <= 100) return // 已足够简短，无需压缩

        val placeholder = OBSERVATION_PLACEHOLDERS[msg.toolName()]
        if (placeholder != null) {
            messages[index] = ToolExecutionResultMessage.from(msg.id(), msg.toolName(), placeholder)
            return
        }

        // 其他工具：解析 JSON 提取摘要
        val compressed = summarizeToolResult(text)
        messages[index] = ToolExecutionResultMessage.from(msg.id(), msg.toolName(), compressed)
    }

    /** 将 ToolResult JSON 压缩为一行摘要 */
    private fun summarizeToolResult(resultJson: String): String {
        return try {
            val mapType = object : TypeToken<Map<String, Any?>>() {}.type
            val map: Map<String, Any?> = GSON.fromJson(resultJson, mapType)
            val isSuccess = map["isSuccess"] as? Boolean ?: false
            if (isSuccess) {
                val data = map["data"]?.toString() ?: "ok"
                "✓ " + if (data.length > 80) data.take(80) + "..." else data
            } else {
                val error = map["error"]?.toString() ?: "failed"
                "✗ " + if (error.length > 80) error.take(80) + "..." else error
            }
        } catch (e: Exception) {
            XLog.w(TAG, "summarizeToolResult failed", e)
            if (resultJson.length > 80) resultJson.take(80) + "..." else resultJson
        }
    }

    // ==================== 主执行循环 ====================

    private class AgentLoopState(
        val messages: MutableList<ChatMessage>,
        val maxIterations: Int,
    ) {
        var iterations = 0
        var totalTokens = 0
        var loopWarningCount = 0
        val loopHistory = LinkedList<RoundFingerprint>()
        var lastScreenHash = 0
    }

    private enum class IterationOutcome { CONTINUE, TERMINATE }
    private enum class ToolHandleResult { CONTINUE, SKIP_REMAINING, TERMINATE }

    private fun AgentLoopState.shouldContinue(): Boolean =
        iterations < maxIterations && !cancelled.get()

    private fun runAgentLoop(userPrompt: String, callback: AgentCallback) {
        preCheck()?.let {
            callback.onError(0, RuntimeException(it), 0)
            return
        }

        val state = AgentLoopState(buildInitialMessages(userPrompt), config.maxIterations)
        while (state.shouldContinue()) {
            state.iterations++
            callback.onLoopStart(state.iterations)
            if (state.runSingleIteration(callback) == IterationOutcome.TERMINATE) break
        }
        finishLoop(state, callback)
    }

    private fun buildInitialMessages(userPrompt: String): MutableList<ChatMessage> {
        val fullSystemPrompt = config.systemPrompt + buildDeviceContext() + config.dynamicPromptSuffix + config.memoryPromptSuffix
        return mutableListOf(
            SystemMessage.from(fullSystemPrompt),
            UserMessage.from(userPrompt),
        )
    }

    private fun AgentLoopState.runSingleIteration(callback: AgentCallback): IterationOutcome {
        val llmResponse = callLlm(callback) ?: return IterationOutcome.TERMINATE
        if (handleLlmResponse(llmResponse, callback)) return IterationOutcome.TERMINATE

        var skipRemaining = false
        for (toolRequest in llmResponse.toolExecutionRequests) {
            if (cancelled.get()) {
                callback.onComplete(iterations, ClawApplication.instance.getString(R.string.agent_task_cancel), totalTokens)
                return IterationOutcome.TERMINATE
            }
            when (executeSingleTool(toolRequest, callback)) {
                ToolHandleResult.TERMINATE -> return IterationOutcome.TERMINATE
                ToolHandleResult.SKIP_REMAINING -> { skipRemaining = true; break }
                ToolHandleResult.CONTINUE -> { }
            }
        }

        if (skipRemaining) return IterationOutcome.CONTINUE
        return if (handleLoopDetection(callback)) IterationOutcome.TERMINATE else IterationOutcome.CONTINUE
    }

    private fun AgentLoopState.callLlm(callback: AgentCallback): LlmResponse? {
        compressHistoryForSend(messages)
        return try {
            chatWithRetry(messages, callback, iterations)
        } catch (e: Exception) {
            XLog.e(TAG, "LLM API call failed after retries", e)
            callback.onError(
                iterations,
                RuntimeException(ClawApplication.instance.getString(R.string.agent_api_call_failed, e.message)),
                totalTokens
            )
            null
        }
    }

    private fun AgentLoopState.handleLlmResponse(llmResponse: LlmResponse, callback: AgentCallback): Boolean {
        llmResponse.tokenUsage?.totalTokenCount()?.let { totalTokens += it }

        val aiMessage = if (llmResponse.hasToolExecutionRequests()) {
            if (llmResponse.text.isNullOrEmpty()) {
                AiMessage.from(llmResponse.toolExecutionRequests)
            } else {
                AiMessage.from(llmResponse.text, llmResponse.toolExecutionRequests)
            }
        } else {
            AiMessage.from(llmResponse.text ?: "")
        }
        messages.add(aiMessage)

        if (!config.streaming && !llmResponse.text.isNullOrEmpty()) {
            callback.onContent(iterations, llmResponse.text)
        }

        if (!llmResponse.hasToolExecutionRequests()) {
            callback.onComplete(iterations, llmResponse.text ?: ClawApplication.instance.getString(R.string.agent_task_completed), totalTokens)
            return true
        }
        return false
    }

    private fun AgentLoopState.executeSingleTool(toolRequest: ToolExecutionRequest, callback: AgentCallback): ToolHandleResult {
        val toolName = toolRequest.name() ?: ""
        val displayName = ToolRegistry.getInstance().getDisplayName(toolName)
        val toolArgs = toolRequest.arguments() ?: "{}"
        callback.onToolCall(iterations, toolName, displayName, toolArgs)

        val params = parseToolArgs(toolName, toolArgs) ?: run {
            val errorResult = ToolResult.error("参数解析失败（详见日志）。原始参数: $toolArgs")
            appendToolResult(toolRequest, errorResult)
            callback.onToolResult(iterations, toolName, displayName, toolArgs, errorResult)
            return ToolHandleResult.CONTINUE
        }

        val result = execTool(toolName, params)
        val paramsString = if (params.isEmpty()) "" else params.toString()
        callback.onToolResult(iterations, toolName, displayName, paramsString, result)

        when (handleDialogResult(result, callback)) {
            DialogHandleResult.TERMINATE -> return ToolHandleResult.TERMINATE
            DialogHandleResult.SKIP_REMAINING -> return ToolHandleResult.SKIP_REMAINING
            DialogHandleResult.NONE -> { }
        }

        if (toolName == "finish" && result.isSuccess) {
            callback.onComplete(iterations, result.data ?: ClawApplication.instance.getString(R.string.agent_task_completed), totalTokens)
            return ToolHandleResult.TERMINATE
        }

        recordFingerprint(toolName, toolArgs, result)
        appendToolResult(toolRequest, result)
        return ToolHandleResult.CONTINUE
    }

    private fun AgentLoopState.parseToolArgs(toolName: String, toolArgs: String): Map<String, Any>? {
        val mapType = object : TypeToken<Map<String, Any>>() {}.type
        return try {
            GSON.fromJson(toolArgs, mapType) ?: emptyMap()
        } catch (e: Exception) {
            XLog.w(TAG, "Tool args parse failed for $toolName: ${e.message}, args=$toolArgs")
            null
        }
    }

    private fun AgentLoopState.appendToolResult(toolRequest: ToolExecutionRequest, result: ToolResult) {
        val resultForJson = mapOf(
            "isSuccess" to result.isSuccess,
            "data" to result.data,
            "error" to result.error
        )
        messages.add(ToolExecutionResultMessage.from(toolRequest, GSON.toJson(resultForJson)))
    }

    private enum class DialogHandleResult { NONE, SKIP_REMAINING, TERMINATE }

    private fun AgentLoopState.handleDialogResult(result: ToolResult, callback: AgentCallback): DialogHandleResult {
        if (result.isSuccess || result.error != GetScreenInfoTool.SYSTEM_DIALOG_BLOCKED) return DialogHandleResult.NONE

        XLog.w(TAG, "System dialog blocked, attempting VLM analysis")
        if (!config.enableVision) {
            XLog.w(TAG, "Vision disabled, notifying user and stopping task")
            callback.onSystemDialogBlocked(iterations, totalTokens)
            return DialogHandleResult.TERMINATE
        }

        val visionHandled = handleSystemDialogWithVision(messages, callback, iterations)
        if (!visionHandled) {
            callback.onSystemDialogBlocked(iterations, totalTokens)
            return DialogHandleResult.TERMINATE
        }
        return DialogHandleResult.SKIP_REMAINING
    }

    private fun AgentLoopState.recordFingerprint(toolName: String, toolArgs: String, result: ToolResult) {
        if (toolName == "get_screen_info" && result.isSuccess && result.data != null) {
            lastScreenHash = result.data.hashCode()
            loopHistory.addLast(RoundFingerprint(lastScreenHash, "observe:get_screen_info"))
            if (loopHistory.size > LOOP_DETECT_WINDOW) loopHistory.removeFirst()
        } else if (toolName.isNotEmpty() && toolName != "get_screen_info") {
            loopHistory.addLast(RoundFingerprint(lastScreenHash, "$toolName:$toolArgs"))
            if (loopHistory.size > LOOP_DETECT_WINDOW) loopHistory.removeFirst()
        }
    }

    private fun AgentLoopState.handleLoopDetection(callback: AgentCallback): Boolean {
        if (!isStuckInLoop(loopHistory)) {
            loopWarningCount = 0
            return false
        }

        loopWarningCount++
        XLog.w(TAG, "Dead loop detected at iteration $iterations (warning $loopWarningCount/$MAX_LOOP_WARNINGS)")

        if (loopWarningCount >= MAX_LOOP_WARNINGS) {
            XLog.w(TAG, "Max loop warnings reached, forcing finish")
            callback.onComplete(
                iterations,
                ClawApplication.instance.getString(R.string.agent_task_completed) + "（检测到死循环，已自动停止）",
                totalTokens
            )
            return true
        }

        messages.add(
            UserMessage.from(
                "[系统提示] 检测到你连续多轮执行了相同的操作且屏幕没有变化，你可能陷入了死循环。" +
                "请尝试完全不同的方法：按 system_key(key=\"back\") 回退、滑动页面寻找目标、或重新打开 App。" +
                "如果确实无法完成任务，请调用 finish 说明原因。" +
                "（警告：再检测到 ${MAX_LOOP_WARNINGS - loopWarningCount} 次死循环将强制结束任务）"
            )
        )
        loopHistory.clear()
        return false
    }

    private fun finishLoop(state: AgentLoopState, callback: AgentCallback) {
        when {
            cancelled.get() ->
                callback.onComplete(state.iterations, ClawApplication.instance.getString(R.string.agent_task_cancel), state.totalTokens)
            // 仅在真正耗尽迭代次数时才报"已达最大迭代次数"。
            // 正常结束（onComplete）或调用失败（onError）已在循环内发出对应消息，
            // 这里不能再无条件追加，否则每次都叠加一条自相矛盾的"任务已完成 + 已达最大迭代次数"。
            state.iterations >= state.maxIterations ->
                callback.onError(
                    state.iterations,
                    RuntimeException(ClawApplication.instance.getString(R.string.agent_max_iterations, state.maxIterations)),
                    state.totalTokens
                )
        }
    }

    override fun cancel() {
        cancelled.set(true)
    }

    override fun shutdown() {
        cancel()
        executor?.shutdownNow()
    }

    override fun isRunning(): Boolean = running.get()
}
