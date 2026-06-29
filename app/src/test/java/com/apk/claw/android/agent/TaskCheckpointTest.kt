package com.apk.claw.android.agent

import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TaskCheckpoint.repairDanglingToolCalls 单测。
 *
 * 验证崩溃恢复时对"悬空 tool_call"(AiMessage 发起了 tool 请求,但对应 tool result
 * 在崩溃前未持久化)的修复:为每个未配对的请求补占位结果,避免 provider 拒绝。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TaskCheckpointTest {

    private fun req(id: String, name: String): ToolExecutionRequest =
        ToolExecutionRequest.builder().id(id).name(name).arguments("{}").build()

    @Test
    fun `trailing dangling tool-call gets a placeholder result`() {
        val messages = listOf(
            SystemMessage.from("sys"),
            UserMessage.from("do it"),
            // 崩溃发生在 LLM 产出 tool 请求之后、工具执行(及结果持久化)之前
            AiMessage.from(listOf(req("call_1", "tap"))),
        )

        val repaired = TaskCheckpoint.repairDanglingToolCalls(messages)

        assertEquals("应补上一个占位结果", 4, repaired.size)
        val last = repaired.last()
        assertTrue("尾部应为补的 tool result", last is ToolExecutionResultMessage)
        last as ToolExecutionResultMessage
        assertEquals("占位结果须匹配请求 id", "call_1", last.id())
        assertEquals("tap", last.toolName())
    }

    @Test
    fun `properly paired tool-call is left unchanged`() {
        val messages = listOf(
            UserMessage.from("do it"),
            AiMessage.from(listOf(req("call_1", "tap"))),
            ToolExecutionResultMessage.from("call_1", "tap", "ok"),
            UserMessage.from("next"),
        )

        val repaired = TaskCheckpoint.repairDanglingToolCalls(messages)

        assertEquals("已配对不应补占位", messages.size, repaired.size)
        assertEquals(1, repaired.count { it is ToolExecutionResultMessage })
    }

    @Test
    fun `messages without tool-calls are unchanged`() {
        val messages = listOf(
            SystemMessage.from("sys"),
            UserMessage.from("hi"),
            AiMessage.from("plain final answer"),
        )

        val repaired = TaskCheckpoint.repairDanglingToolCalls(messages)

        assertEquals(messages.size, repaired.size)
        assertTrue("无 tool 请求不应产生 tool result", repaired.none { it is ToolExecutionResultMessage })
    }

    @Test
    fun `partial multi-tool-call repairs only the unmatched request`() {
        val messages = listOf(
            UserMessage.from("do it"),
            AiMessage.from(listOf(req("call_1", "tap"), req("call_2", "swipe"))),
            // 只持久化了 call_1 的结果,call_2 悬空
            ToolExecutionResultMessage.from("call_1", "tap", "ok"),
        )

        val repaired = TaskCheckpoint.repairDanglingToolCalls(messages)

        val results = repaired.filterIsInstance<ToolExecutionResultMessage>()
        assertEquals("call_2 应被补占位 → 共两个结果", 2, results.size)
        assertTrue("应含 call_2 的占位结果", results.any { it.id() == "call_2" })
    }
}
