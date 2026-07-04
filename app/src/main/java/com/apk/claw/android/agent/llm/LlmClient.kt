package com.apk.claw.android.agent.llm

import com.apk.claw.android.agent.CancellationToken
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.data.message.ChatMessage

interface LlmClient {
    /** Whether this model supports vision (image content in messages). */
    val supportsVision: Boolean
        get() = false

    /** Blocking call. Returns the complete AI response. */
    fun chat(messages: List<ChatMessage>, toolSpecs: List<ToolSpecification>): LlmResponse

    /**
     * Streaming call. Invokes listener callbacks as tokens arrive. Blocks until stream completes.
     *
     * @param cancelToken 可选取消令牌:在流式响应期间检查,主动中断在途流式调用,
     *                    避免 LLM 连接半挂导致 executor 线程永久阻塞或取消不生效。
     */
    fun chatStreaming(
        messages: List<ChatMessage>,
        toolSpecs: List<ToolSpecification>,
        listener: StreamingListener,
        cancelToken: CancellationToken? = null
    ): LlmResponse
}
