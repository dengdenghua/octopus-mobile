package com.apk.claw.android.agent.llm

import com.apk.claw.android.agent.AgentConfig
import com.apk.claw.android.agent.langchain.http.OkHttpClientBuilderAdapter
import dev.langchain4j.model.anthropic.AnthropicChatModel
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.StreamingChatModel

/** Anthropic provider。共享逻辑见 [BaseLangChain4jLlmClient]。 */
class AnthropicLlmClient(
    config: AgentConfig,
    httpClientBuilder: OkHttpClientBuilderAdapter
) : BaseLangChain4jLlmClient(config, httpClientBuilder) {

    override fun createChatModel(): ChatModel {
        val builder = AnthropicChatModel.builder()
            .httpClientBuilder(httpClientBuilder)
            .apiKey(config.apiKey)
            .modelName(config.modelName)
            .temperature(config.temperature)
        if (config.baseUrl.isNotEmpty()) builder.baseUrl(config.baseUrl)
        return builder.build()
    }

    override fun createStreamingChatModel(): StreamingChatModel {
        val builder = AnthropicStreamingChatModel.builder()
            .httpClientBuilder(httpClientBuilder)
            .apiKey(config.apiKey)
            .modelName(config.modelName)
            .temperature(config.temperature)
        if (config.baseUrl.isNotEmpty()) builder.baseUrl(config.baseUrl)
        return builder.build()
    }
}
