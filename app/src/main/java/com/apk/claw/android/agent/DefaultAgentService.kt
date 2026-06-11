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

    override fun executeTask(userPrompt: String, callback: AgentCallback) {
        if (running.get()) {
            callback.onError(0, IllegalStateException("Agent is already running a task"), 0)
            return
        }

        running.set(true)
        cancelled.set(false)

        executor?.submit {
            try {
                runAgentLoop(userPrompt, callback)
            } catch (e: Exception) {
                XLog.e(TAG, "Agent execution error", e)
                callback.onError(0, e, 0)
            } finally {
                running.set(false)
            }
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
                    var params: Map<String, Any>? = try {
                        GSON.fromJson(toolArgs, mapType)
                    } catch (e: Exception) {
                        HashMap()
                    }
                    if (params == null) params = HashMap()

                    val toolResult = ToolRegistry.getInstance().executeTool(toolName, params)
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
        } catch (_: Exception) { "CoPaw" }
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
                return if (config.streaming) {
                    val textBuilder = StringBuilder()
                    llmClient.chatStreaming(messages, toolSpecs, object : StreamingListener {
                        override fun onPartialText(token: String) {
                            textBuilder.append(token)
                            callback.onContent(iteration, token)
                        }
                        override fun onComplete(response: LlmResponse) {}
                        override fun onError(error: Throwable) {}
                    })
                } else {
                    llmClient.chat(messages, toolSpecs)
                }
            } catch (e: Exception) {
                lastException = e
                val msg = e.message ?: ""
                // Token 耗尽或认证失败不重试
                if (msg.contains("401") || msg.contains("403") || msg.contains("insufficient")) {
                    throw e
                }
                val delay = (Math.pow(2.0, attempt.toDouble()) * 1000).toLong()
                XLog.w(TAG, "LLM API call failed (attempt ${attempt + 1}/$MAX_API_RETRIES), retrying in ${delay}ms: $msg")
                try {
                    Thread.sleep(delay)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                }
            }
        }
        throw lastException!!
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
     */
    private fun compressHistoryForSend(messages: MutableList<ChatMessage>) {
        // 压缩前统计总字符数
        val charsBefore = messages.sumOf { msg ->
            when (msg) {
                is AiMessage -> (msg.text()?.length ?: 0) + (msg.toolExecutionRequests()?.sumOf { it.arguments()?.length ?: 0 } ?: 0)
                is ToolExecutionResultMessage -> msg.text().length
                is UserMessage -> msg.singleText().length
                is SystemMessage -> msg.text().length
                else -> 0
            }
        }
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
        val charsAfter = messages.sumOf { msg ->
            when (msg) {
                is AiMessage -> (msg.text()?.length ?: 0) + (msg.toolExecutionRequests()?.sumOf { it.arguments()?.length ?: 0 } ?: 0)
                is ToolExecutionResultMessage -> msg.text().length
                is UserMessage -> msg.singleText().length
                is SystemMessage -> msg.text().length
                else -> 0
            }
        }
        val saved = charsBefore - charsAfter
        if (saved > 0) {
            XLog.i(TAG, "上下文压缩: ${charsBefore}→${charsAfter}字符, 节省${saved}字符(${saved * 100 / charsBefore}%), 轮数=${aiIndices.size}")
        }

        // ── ContextCompressor 二次压缩层 ──
        // 如果现有压缩后总字符仍超过 maxChars，对保护区外的 UserMessage 做截断
        val charsAfterFirstPass = messages.sumOf { msg ->
            when (msg) {
                is AiMessage -> (msg.text()?.length ?: 0) + (msg.toolExecutionRequests()?.sumOf { it.arguments()?.length ?: 0 } ?: 0)
                is ToolExecutionResultMessage -> msg.text().length
                is UserMessage -> msg.singleText().length
                is SystemMessage -> msg.text().length
                else -> 0
            }
        }
        if (charsAfterFirstPass > contextCompressor.config.maxChars && aiIndices.size > KEEP_RECENT_ROUNDS) {
            applyContextCompressorTruncation(messages, aiIndices, KEEP_RECENT_ROUNDS)
            val charsAfterSecondPass = messages.sumOf { msg ->
                when (msg) {
                    is AiMessage -> (msg.text()?.length ?: 0) + (msg.toolExecutionRequests()?.sumOf { it.arguments()?.length ?: 0 } ?: 0)
                    is ToolExecutionResultMessage -> msg.text().length
                    is UserMessage -> msg.singleText().length
                    is SystemMessage -> msg.text().length
                    else -> 0
                }
            }
            val secondSaved = charsAfterFirstPass - charsAfterSecondPass
            if (secondSaved > 0) {
                val ratio = (charsAfterSecondPass.toFloat() / charsAfterFirstPass * 100).toInt()
                XLog.i(TAG, "ContextCompressor 二次压缩: ${charsAfterFirstPass}→${charsAfterSecondPass}字符 (${ratio}%)")
            }
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
        } catch (_: Exception) {
            if (resultJson.length > 80) resultJson.take(80) + "..." else resultJson
        }
    }

    // ==================== 主执行循环 ====================

    private fun runAgentLoop(userPrompt: String, callback: AgentCallback) {
        // 环境预检
        preCheck()?.let {
            callback.onError(0, RuntimeException(it), 0)
            return
        }

        // 构建 System Prompt（原始 + 设备上下文 + 动态教训 + 跨会话记忆）
        val fullSystemPrompt = config.systemPrompt + buildDeviceContext() + config.dynamicPromptSuffix + config.memoryPromptSuffix

        val messages = mutableListOf<ChatMessage>()
        messages.add(SystemMessage.from(fullSystemPrompt))
        messages.add(UserMessage.from(userPrompt))

        var iterations = 0
        var totalTokens = 0
        val maxIterations = config.maxIterations
        val loopHistory = LinkedList<RoundFingerprint>()
        var lastScreenHash = 0

        while (iterations < maxIterations && !cancelled.get()) {
            iterations++
            callback.onLoopStart(iterations)

            // 发送前分级压缩历史消息，节省 token
            compressHistoryForSend(messages)

            // LLM 调用（带重试）
            val llmResponse: LlmResponse
            try {
                llmResponse = chatWithRetry(messages, callback, iterations)
            } catch (e: Exception) {
                XLog.e(TAG, "LLM API call failed after retries", e)
                callback.onError(iterations, RuntimeException(ClawApplication.instance.getString(R.string.agent_api_call_failed, e.message)), totalTokens)
                return
            }

            // 累加 token 用量
            llmResponse.tokenUsage?.totalTokenCount()?.let { totalTokens += it }

            // 将 AI 消息添加到历史（需要构造 AiMessage）
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

            // 非流式模式下推送思考内容
            if (!config.streaming && !llmResponse.text.isNullOrEmpty()) {
                callback.onContent(iterations, llmResponse.text)
            }

            // 如果没有工具调用，Agent 认为完成了
            if (!llmResponse.hasToolExecutionRequests()) {
                callback.onComplete(iterations, llmResponse.text ?: ClawApplication.instance.getString(R.string.agent_task_completed), totalTokens)
                return
            }

            // 执行工具调用
            for (toolRequest in llmResponse.toolExecutionRequests) {
                if (cancelled.get()) {
                    callback.onComplete(iterations, ClawApplication.instance.getString(R.string.agent_task_cancel), totalTokens)
                    return
                }

                val toolName = toolRequest.name() ?: ""
                val displayName = ToolRegistry.getInstance().getDisplayName(toolName)
                val toolArgs = toolRequest.arguments() ?: "{}"
                callback.onToolCall(iterations, toolName, displayName, toolArgs)

                // 解析参数
                val mapType = object : TypeToken<Map<String, Any>>() {}.type
                var params: Map<String, Any>? = try {
                    GSON.fromJson(toolArgs, mapType)
                } catch (e: Exception) {
                    HashMap()
                }
                if (params == null) params = HashMap()

                val result = ToolRegistry.getInstance().executeTool(toolName, params)
                val paramsString = if (params.isEmpty()) "" else params.toString()
                callback.onToolResult(iterations, toolName, displayName, paramsString, result)

                // 检测到系统弹窗阻塞 → 截图让 VLM 分析弹窗内容，尝试自动处理
                if (!result.isSuccess && result.error == GetScreenInfoTool.SYSTEM_DIALOG_BLOCKED) {
                    XLog.w(TAG, "System dialog blocked, attempting VLM analysis")

                    // 如果未启用视觉理解，直接终止任务
                    if (!config.enableVision) {
                        XLog.w(TAG, "Vision disabled, notifying user and stopping task")
                        callback.onSystemDialogBlocked(iterations, totalTokens)
                        return
                    }

                    // 截图并让 VLM 分析弹窗内容
                    val visionHandled = handleSystemDialogWithVision(messages, callback, iterations)
                    if (!visionHandled) {
                        // VLM 也无法处理，终止任务
                        callback.onSystemDialogBlocked(iterations, totalTokens)
                        return
                    }
                    // VLM 分析后继续执行循环
                    continue
                }

                // finish 工具 → 任务完成
                if (toolName == "finish" && result.isSuccess) {
                    val finishData = result.data
                    callback.onComplete(iterations, finishData ?: ClawApplication.instance.getString(R.string.agent_task_completed), totalTokens)
                    return
                }

                // 记录指纹用于死循环检测
                if (toolName == "get_screen_info" && result.isSuccess && result.data != null) {
                    lastScreenHash = result.data.hashCode()
                } else if (toolName.isNotEmpty() && toolName != "get_screen_info") {
                    loopHistory.addLast(RoundFingerprint(lastScreenHash, "$toolName:$toolArgs"))
                    if (loopHistory.size > LOOP_DETECT_WINDOW) {
                        loopHistory.removeFirst()
                    }
                }

                // 添加工具结果到消息（排除 imageBase64 以节省 token）
                val resultForJson = mapOf(
                    "isSuccess" to result.isSuccess,
                    "data" to result.data,
                    "error" to result.error
                )
                val resultJson = GSON.toJson(resultForJson)
                messages.add(ToolExecutionResultMessage.from(toolRequest, resultJson))
                XLog.d(TAG, "displayName:$displayName toolName:$toolName")
            }

            // 死循环检测
            if (isStuckInLoop(loopHistory)) {
                XLog.w(TAG, "Dead loop detected at iteration $iterations")
                messages.add(
                    UserMessage.from(
                        "[系统提示] 检测到你连续多轮执行了相同的操作且屏幕没有变化，你可能陷入了死循环。" +
                        "请尝试完全不同的方法：按 system_key(key=\"back\") 回退、滑动页面寻找目标、或重新打开 App。" +
                        "如果确实无法完成任务，请调用 finish 说明原因。"
                    )
                )
                loopHistory.clear()
            }
            XLog.d(TAG, "轮数:$iterations all=$totalTokens 本轮=${llmResponse.tokenUsage?.totalTokenCount()}")
        }

        if (cancelled.get()) {
            callback.onComplete(iterations, ClawApplication.instance.getString(R.string.agent_task_cancel), totalTokens)
        } else {
            callback.onError(iterations, RuntimeException(ClawApplication.instance.getString(R.string.agent_max_iterations, maxIterations)), totalTokens)
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
