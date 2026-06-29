package com.apk.claw.android.agent.llm

import com.apk.claw.android.agent.AgentConfig
import com.apk.claw.android.agent.langchain.http.OkHttpClientBuilderAdapter
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.StreamingChatModel
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.response.ChatResponse
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler
import java.util.concurrent.CountDownLatch
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

    private val chatModel: ChatModel by lazy { createChatModel() }
    private val streamingChatModel: StreamingChatModel by lazy { createStreamingChatModel() }

    /** 构造非流式模型（provider 专属：已配置好 apiKey / model / temperature / baseUrl）。 */
    protected abstract fun createChatModel(): ChatModel

    /** 构造流式模型（provider 专属）。 */
    protected abstract fun createStreamingChatModel(): StreamingChatModel

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
        listener: StreamingListener
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

        latch.await()
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
