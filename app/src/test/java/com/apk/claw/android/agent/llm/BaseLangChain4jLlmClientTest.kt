package com.apk.claw.android.agent.llm

import com.apk.claw.android.agent.AgentConfig
import com.apk.claw.android.agent.langchain.http.OkHttpClientBuilderAdapter
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.StreamingChatModel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BaseLangChain4jLlmClient 单元测试 —— VLM 视觉能力启发式判断。
 *
 * supportsVision 基于模型名做关键字匹配,决定是否注入截图。纯逻辑无 Android 依赖,
 * 通过最小测试子类验证(基类 abstract,lazy 的 chatModel 不会被触发)。
 */
class BaseLangChain4jLlmClientTest {

    /**
     * 最小测试子类:仅为了访问 supportsVision。createChatModel / createStreamingChatModel
     * 是 lazy 求值,supportsVision 不访问它们,因此永远不会被调用。
     */
    private class TestLlmClient(modelName: String) : BaseLangChain4jLlmClient(
        config = AgentConfig(apiKey = "key", baseUrl = "http://localhost", modelName = modelName),
        httpClientBuilder = OkHttpClientBuilderAdapter()
    ) {
        override fun createChatModel(): ChatModel =
            throw NotImplementedError("not used in supportsVision test")

        override fun createStreamingChatModel(): StreamingChatModel =
            throw NotImplementedError("not used in supportsVision test")
    }

    private fun supportsVision(modelName: String): Boolean =
        TestLlmClient(modelName).supportsVision

    // ==================== 支持视觉的模型族 ====================

    @Test
    fun `gpt-4o supports vision`() {
        assertTrue(supportsVision("gpt-4o"))
    }

    @Test
    fun `gpt-4o-mini supports vision`() {
        assertTrue(supportsVision("gpt-4o-mini"))
    }

    @Test
    fun `claude-3-5-sonnet supports vision`() {
        assertTrue(supportsVision("claude-3-5-sonnet"))
    }

    @Test
    fun `gemini-pro supports vision`() {
        assertTrue(supportsVision("gemini-pro"))
    }

    @Test
    fun `qwen2_5-vl supports vision`() {
        assertTrue(supportsVision("qwen2.5-vl"))
    }

    // ==================== 不支持视觉的模型 ====================

    @Test
    fun `deepseek-chat does not support vision`() {
        assertFalse(supportsVision("deepseek-chat"))
    }

    @Test
    fun `gpt-3_5-turbo does not support vision`() {
        assertFalse(supportsVision("gpt-3.5-turbo"))
    }

    @Test
    fun `empty model name does not support vision`() {
        assertFalse(supportsVision(""))
    }
}
