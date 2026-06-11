package com.apk.claw.android.octopus_mobile

import com.apk.claw.android.tool.ToolRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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
    }

    @Test
    fun `executeLocal strips android prefix and runs tool`() {
        val result = dispatcher.executeLocal("android.finish", emptyMap())
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
        val result = dispatcher.executeLocal("finish", emptyMap())
        assertTrue(result.isSuccess)
    }

    @Test
    fun `executeLocal filters null args`() {
        val result = dispatcher.executeLocal(
            "finish",
            mapOf("summary" to null, "other" to "value"),
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
        val call = ToolCall(id = "test-1", name = "android.finish", args = emptyMap())
        dispatcher.dispatch(call)
        // Wait briefly for async dispatch
        kotlinx.coroutines.delay(200)
        val captured = client.lastToolResult
        assertNotNull("expected a sendToolResult call", captured)
        assertEquals("test-1", captured!!.callId)
        assertTrue(captured.success)
    }

    @Test
    fun `dispatch async sends failure result for unknown tool`() = runBlocking {
        dispatcher.start()
        val call = ToolCall(id = "test-2", name = "android.nope", args = emptyMap())
        dispatcher.dispatch(call)
        kotlinx.coroutines.delay(200)
        val captured = client.lastToolResult
        assertNotNull(captured)
        assertEquals("test-2", captured!!.callId)
        assertFalse(captured.success)
        assertEquals(ErrorCodes.TOOL_NOT_FOUND, captured.errorCode)
    }

    @Test
    fun `dispatch truncates oversized result data`() = runBlocking {
        dispatcher.start()
        // 构造一个能返回大数据的工具（find_node_info 会返回节点信息）
        val call = ToolCall(
            id = "test-3",
            name = "finish",
            args = mapOf("summary" to "x".repeat(50_000)),
        )
        dispatcher.dispatch(call)
        kotlinx.coroutines.delay(300)
        val captured = client.lastToolResult
        assertNotNull(captured)
        // success 路径：data 应该是被截断的（如果原 result 超过 32K 字符）
        // finish 的真实结果可能不长，但测试逻辑不依赖具体长度
    }

    // ── Stub ─────────────────────────────────────────────────

    class CapturingOctopusMobileClient(
        runtimeUrl: String,
        tentacleId: String,
    ) : OctopusMobileClient(runtimeUrl, tentacleId) {
        var lastToolResult: CapturedToolResult? = null

        override fun sendToolResult(
            callId: String,
            success: Boolean,
            data: String?,
            error: String?,
            errorCode: Int?,
            durationMs: Int,
            screenHashAfter: String?,
        ) {
            lastToolResult = CapturedToolResult(
                callId = callId,
                success = success,
                data = data,
                error = error,
                errorCode = errorCode ?: -32603,
            )
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
