package com.apk.claw.android.octopus_mobile

import com.apk.claw.android.tool.ToolRegistry
import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * ToolCallDispatcher 单元测试.
 *
 * 覆盖：
 *  - 同步执行本地工具（executeLocal）
 *  - 工具名前缀剥离（android.tap → tap）
 *  - 未知工具返回 ToolResult.error
 *  - 错误码映射（mapErrorToCode）
 *  - dispatch 异步触发（不阻塞）
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ToolCallDispatcherTest {

    private lateinit var client: CapturingOctopusMobileClient
    private lateinit var dispatcher: ToolCallDispatcher

    @Before
    fun setUp() {
        client = CapturingOctopusMobileClient("ws://test", "test-tentacle")
        ToolRegistry.registerAllTools(ToolRegistry.DeviceType.MOBILE)
        dispatcher = ToolCallDispatcher(client)
    }

    @After
    fun tearDown() {
        ToolRegistry.registerAllTools(ToolRegistry.DeviceType.MOBILE)  // reset
        com.apk.claw.android.octopus_mobile.ToolAuditLog.resetSecretCacheForTest()
        com.apk.claw.android.utils.KVUtils.resetForTest()
    }

    @Test
    fun `executeLocal strips android prefix and runs tool`() {
        val result = dispatcher.executeLocal("android.finish", mapOf("summary" to "done"))
        assertTrue(result.isSuccess)
    }

    @Test
    fun `executeLocal handles unknown tool`() {
        val result = dispatcher.executeLocal("android.nonexistent", emptyMap())
        assertFalse(result.isSuccess)
        assertTrue((result.error ?: "").contains("Unknown tool"))
    }

    @Test
    fun `executeLocal passes through short name without prefix`() {
        val result = dispatcher.executeLocal("finish", mapOf("summary" to "done"))
        assertTrue(result.isSuccess)
    }

    @Test
    fun `executeLocal filters null args`() {
        val result = dispatcher.executeLocal(
            "finish",
            mapOf("summary" to "done", "other" to null),
        )
        assertTrue(result.isSuccess)
    }

    @Test
    fun `start sets client onToolExecute callback`() {
        dispatcher.start()
        assertNotNull(client.onToolExecute)
        dispatcher.stop()
        assertNull(client.onToolExecute)
    }

    @Test
    fun `dispatch async sends success result for known tool`() = runBlocking {
        dispatcher.start()
        val call = ToolCall(id = "test-1", name = "android.finish", args = mapOf("summary" to "done"))
        dispatcher.dispatch(call)
        val captured = client.awaitToolResult()
        assertNotNull("expected a sendToolResult call", captured)
        assertEquals("test-1", captured!!.callId)
        assertTrue(captured.success)
    }

    @Test
    fun `dispatch async sends failure result for unknown tool`() = runBlocking {
        dispatcher.start()
        val call = ToolCall(id = "test-2", name = "android.nope", args = emptyMap())
        dispatcher.dispatch(call)
        val captured = client.awaitToolResult()
        assertNotNull(captured)
        assertEquals("test-2", captured!!.callId)
        assertFalse(captured.success)
        assertEquals(ErrorCodes.TOOL_NOT_FOUND, captured.errorCode)
    }

    @Test
    fun `truncates oversized result data`() {
        val data = dispatcher.truncateResultData("x".repeat(50_000))

        assertEquals(
            ToolCallDispatcher.MAX_RESULT_CHARS + ToolCallDispatcher.TRUNCATED_SUFFIX.length,
            data?.length,
        )
        assertTrue(data!!.endsWith(ToolCallDispatcher.TRUNCATED_SUFFIX))
    }

    @Test
    fun `stop rejects future dispatch work explicitly`() = runBlocking {
        ToolRegistry.register(SlowResultTool())
        dispatcher.start()
        dispatcher.stop()

        dispatcher.dispatch(ToolCall(id = "test-stopped", name = "slow_result", args = emptyMap()))

        val captured = client.awaitToolResult(timeoutMs = 250)
        assertNotNull(captured)
        assertEquals("test-stopped", captured!!.callId)
        assertFalse(captured.success)
        assertEquals(ErrorCodes.DEVICE_OFFLINE, captured.errorCode)
    }

    // ── Stub ─────────────────────────────────────────────────

    class CapturingOctopusMobileClient(
        runtimeUrl: String,
        tentacleId: String,
    ) : OctopusMobileClient(runtimeUrl, tentacleId) {
        private val results = LinkedBlockingQueue<CapturedToolResult>()

        fun awaitToolResult(timeoutMs: Long = 1_000): CapturedToolResult? {
            return results.poll(timeoutMs, TimeUnit.MILLISECONDS)
        }

        override fun sendToolResult(
            callId: String,
            success: Boolean,
            data: String?,
            error: String?,
            errorCode: Int?,
            durationMs: Int,
            screenHashAfter: String?,
        ) {
            results.offer(
                CapturedToolResult(
                    callId = callId,
                    success = success,
                    data = data,
                    error = error,
                    errorCode = errorCode ?: -32603,
                ),
            )
        }
    }

    class SlowResultTool : BaseTool() {
        override fun getName(): String = "slow_result"
        override fun getDisplayName(): String = "Slow Result"
        override fun getDescriptionEN(): String = "Returns slowly for dispatcher cancellation tests."
        override fun getDescriptionCN(): String = "返回较慢，用于调度器取消测试。"
        override fun getParameters(): List<ToolParameter> = emptyList()

        override fun execute(params: Map<String, Any>): ToolResult {
            Thread.sleep(1_000)
            return ToolResult.success("late")
        }
    }

    data class CapturedToolResult(
        val callId: String,
        val success: Boolean,
        val data: String?,
        val error: String?,
        val errorCode: Int,
    )
}
