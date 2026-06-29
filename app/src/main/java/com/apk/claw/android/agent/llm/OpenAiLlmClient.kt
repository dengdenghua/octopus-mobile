package com.apk.claw.android.agent.llm

import com.apk.claw.android.agent.AgentConfig
import com.apk.claw.android.agent.langchain.http.OkHttpClientBuilderAdapter
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.StreamingChatModel
import dev.langchain4j.model.openai.OpenAiChatModel
import dev.langchain4j.model.openai.OpenAiStreamingChatModel

/** OpenAI 兼容（含 DeepSeek / MiMo 等）provider。共享逻辑见 [BaseLangChain4jLlmClient]。 */
class OpenAiLlmClient(
    config: AgentConfig,
    httpClientBuilder: OkHttpClientBuilderAdapter
) : BaseLangChain4jLlmClient(config, httpClientBuilder) {

    override fun createChatModel(): ChatModel {
        val builder = OpenAiChatModel.builder()
            .httpClientBuilder(httpClientBuilder)
            .apiKey(config.apiKey)
            .modelName(config.modelName)
            .temperature(config.temperature)
        if (config.baseUrl.isNotEmpty()) builder.baseUrl(config.baseUrl)
        return builder.build()
    }

    override fun createStreamingChatModel(): StreamingChatModel {
        val builder = OpenAiStreamingChatModel.builder()
            .httpClientBuilder(httpClientBuilder)
            .apiKey(config.apiKey)
            .modelName(config.modelName)
            .temperature(config.temperature)
        if (config.baseUrl.isNotEmpty()) builder.baseUrl(config.baseUrl)
        return builder.build()
    }
}
