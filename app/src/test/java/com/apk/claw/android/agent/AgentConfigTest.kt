package com.apk.claw.android.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentConfigTest {

    // ==================== data class 默认值与 Builder 默认值一致 ====================

    @Test
    fun `data class default maxIterations is 60`() {
        val config = AgentConfig(apiKey = "key", baseUrl = "http://localhost")
        assertEquals(60, config.maxIterations)
    }

    @Test
    fun `Builder default maxIterations is 60`() {
        val config = AgentConfig.Builder()
            .apiKey("key")
            .baseUrl("http://localhost")
            .build()
        assertEquals(60, config.maxIterations)
    }

    @Test
    fun `Builder defaults match data class defaults`() {
        val dataClassConfig = AgentConfig(apiKey = "key", baseUrl = "http://localhost")
        val builderConfig = AgentConfig.Builder()
            .apiKey("key")
            .baseUrl("http://localhost")
            .build()

        assertEquals(dataClassConfig.maxIterations, builderConfig.maxIterations)
        assertEquals(dataClassConfig.temperature, builderConfig.temperature, 0.0)
        assertEquals(dataClassConfig.provider, builderConfig.provider)
        assertEquals(dataClassConfig.streaming, builderConfig.streaming)
        assertEquals(dataClassConfig.systemPrompt, builderConfig.systemPrompt)
        assertEquals(dataClassConfig.modelName, builderConfig.modelName)
        assertEquals(dataClassConfig.dynamicPromptSuffix, builderConfig.dynamicPromptSuffix)
        assertEquals(dataClassConfig.memoryPromptSuffix, builderConfig.memoryPromptSuffix)
        assertEquals(dataClassConfig.enableVision, builderConfig.enableVision)
    }

    // ==================== Builder 必填校验 ====================

    @Test
    fun `Builder throws when apiKey is empty`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            AgentConfig.Builder()
                .apiKey("")
                .baseUrl("http://localhost")
                .build()
        }
        assertTrue("Expected message to mention API key", exception.message?.contains("API key") == true)
    }

    @Test
    fun `Builder throws when apiKey not set`() {
        assertThrows(IllegalArgumentException::class.java) {
            AgentConfig.Builder()
                .baseUrl("http://localhost")
                .build()
        }
    }

    // ==================== Builder 链式调用 ====================

    @Test
    fun `Builder supports chained calls`() {
        val config = AgentConfig.Builder()
            .apiKey("my-key")
            .baseUrl("https://api.example.com")
            .modelName("gpt-4")
            .maxIterations(100)
            .temperature(0.5)
            .provider(LlmProvider.ANTHROPIC)
            .streaming(true)
            .systemPrompt("custom prompt")
            .dynamicPromptSuffix("suffix")
            .memoryPromptSuffix("memory")
            .enableVision(false)
            .build()

        assertEquals("my-key", config.apiKey)
        assertEquals("https://api.example.com", config.baseUrl)
        assertEquals("gpt-4", config.modelName)
        assertEquals(100, config.maxIterations)
        assertEquals(0.5, config.temperature, 0.0)
        assertEquals(LlmProvider.ANTHROPIC, config.provider)
        assertTrue(config.streaming)
        assertEquals("custom prompt", config.systemPrompt)
        assertEquals("suffix", config.dynamicPromptSuffix)
        assertEquals("memory", config.memoryPromptSuffix)
        assertFalse(config.enableVision)
    }

    // ==================== 默认 systemPrompt 非空 ====================

    @Test
    fun `default systemPrompt is non-empty`() {
        val config = AgentConfig(apiKey = "key", baseUrl = "http://localhost")
        assertNotNull(config.systemPrompt)
        assertTrue(config.systemPrompt.isNotEmpty())
    }

    @Test
    fun `Builder default systemPrompt equals DEFAULT_SYSTEM_PROMPT`() {
        val config = AgentConfig.Builder()
            .apiKey("key")
            .baseUrl("http://localhost")
            .build()
        assertEquals(AgentConfig.DEFAULT_SYSTEM_PROMPT, config.systemPrompt)
    }
}
