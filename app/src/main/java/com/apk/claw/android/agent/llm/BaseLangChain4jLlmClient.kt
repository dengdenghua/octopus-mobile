package com.apk.claw.android.agent.llm

import com.apk.claw.android.agent.AgentConfig
import com.apk.claw.android.agent.CancellationToken
import com.apk.claw.android.agent.langchain.http.OkHttpClientBuilderAdapter
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.StreamingChatModel
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.response.ChatResponse
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/**
 * LangChain4j LLM 客户端的公共骨架。
 *
 * OpenAI 与 Anthropic 两个 provider 仅在底层 [ChatModel] / [StreamingChatModel] 的构造方式上不同；
 * 请求构建、流式 latch 等待、响应转换等逻辑完全一致。此前两份实现近乎逐字重复（~95%），
 * 任何一处修改都需同步两份、极易漂移。这里把共享逻辑统一到基类，provider 子类只需提供模型构造。
 */
abstract class BaseLangChain4jLlmClient(
    protected val config: AgentConfig,
    protected val httpClientBuilder: OkHttpClientBuilderAdapter,
) : LlmClient {

    companion object {
        /**
         * 流式响应整体超时(ms)。
         *
         * 防止 LLM 流式连接建立后服务端半挂(不发 onComplete/onError、连接不关),
         * 导致 executor 线程永久阻塞、Agent 卡死无法接受新任务。
         * 略高于 OkHttp 的 readTimeout(60s),给慢速 token 留足余量。
         */
        private const val STREAM_TIMEOUT_MS = 120_000L
    }

    private val chatModel: ChatModel by lazy { createChatModel() }
    private val streamingChatModel: StreamingChatModel by lazy { createStreamingChatModel() }

    /** 构造非流式模型（provider 专属：已配置好 apiKey / model / temperature / baseUrl）。 */
    protected abstract fun createChatModel(): ChatModel

    /** 构造流式模型（provider 专属）。 */
    protected abstract fun createStreamingChatModel(): StreamingChatModel

    /**
     * 基于模型名启发式判断是否支持视觉（图片输入）。
     * 覆盖主流多模态模型族；未知模型默认 false（安全降级为纯文本，不注入截屏）。
     */
    override val supportsVision: Boolean
        get() {
            val name = config.modelName.lowercase()
            return name.contains("gpt-4o") ||
                name.contains("gpt-4-turbo") ||
                name.contains("gpt-4-vision") ||
                name.contains("gpt-4.1") ||
                name.contains("o1") ||
                name.contains("o3") ||
                name.contains("o4") ||
                name.contains("claude-3") ||
                name.contains("claude-4") ||
                name.contains("gemini") ||
                name.contains("qwen-vl") ||
                name.contains("qwen2-vl") ||
                name.contains("qwen2.5-vl") ||
                name.contains("qwen3-vl") ||
                name.contains("glm-4v") ||
                name.contains("step-1v") ||
                name.contains("step-1.5v") ||
                name.contains("multimodal") ||
                name.contains("vision")
        }

    final override fun chat(messages: List<ChatMessage>, toolSpecs: List<ToolSpecification>): LlmResponse {
        val request = ChatRequest.builder()
            .messages(messages)
            .toolSpecifications(toolSpecs)
            .build()
        val response = chatModel.chat(request)
        return response.toLlmResponse()
    }

    final override fun chatStreaming(
        messages: List<ChatMessage>,
        toolSpecs: List<ToolSpecification>,
        listener: StreamingListener,
        cancelToken: CancellationToken?
    ): LlmResponse {
        val request = ChatRequest.builder()
            .messages(messages)
            .toolSpecifications(toolSpecs)
            .build()

        val latch = CountDownLatch(1)
        val resultRef = AtomicReference<LlmResponse>()
        val errorRef = AtomicReference<Throwable>()

        streamingChatModel.chat(request, object : StreamingChatResponseHandler {
            override fun onPartialResponse(token: String) {
                // 取消检查:流式响应期间主动中断,避免等流自然结束或网络超时。
                // langchain4j 的流式调用不响应 Thread.interrupt(),只能从回调内部抛异常跳出。
                if (cancelToken?.isCancelled() == true) {
                    throw InterruptedException(cancelToken.getReason() ?: "Task cancelled")
                }
                listener.onPartialText(token)
            }

            override fun onCompleteResponse(response: ChatResponse) {
                val llmResponse = response.toLlmResponse()
                resultRef.set(llmResponse)
                listener.onComplete(llmResponse)
                latch.countDown()
            }

            override fun onError(error: Throwable) {
                errorRef.set(error)
                listener.onError(error)
                latch.countDown()
            }
        })

        // P0:加超时,防止 LLM 流式连接半挂(连接不关、不发 onComplete/onError)导致
        // executor 线程永久阻塞、Agent 卡死无法接受新任务、cancelToken 无法中断。
        if (!latch.await(STREAM_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw TimeoutException("LLM streaming timed out after ${STREAM_TIMEOUT_MS}ms")
        }
        errorRef.get()?.let { throw it }
        return resultRef.get()
    }
}

internal fun ChatResponse.toLlmResponse(): LlmResponse {
    val aiMessage = aiMessage()
    return LlmResponse(
        text = aiMessage.text(),
        toolExecutionRequests = aiMessage.toolExecutionRequests() ?: emptyList(),
        tokenUsage = tokenUsage()
    )
}
