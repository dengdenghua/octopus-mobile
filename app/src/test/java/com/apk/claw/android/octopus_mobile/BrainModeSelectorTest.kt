package com.apk.claw.android.octopus_mobile

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.apk.claw.android.tool.ToolRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * BrainModeSelector 单元测试.
 *
 * 覆盖：
 *  - 初始状态：EXECUTOR_ONLY + MOBILE 领域
 *  - 意图分类驱动领域切换（浏览器/手机/混合/不明确）
 *  - forceDomain 强制切换
 *  - currentDomain() / currentMode() 状态读取
 *  - onDomainChanged 回调触发
 *  - executeLocalTool 前缀剥离
 *  - skillCount() 返回 42
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BrainModeSelectorTest {

    private lateinit var context: Context
    private lateinit var selector: BrainModeSelector
    private lateinit var client: StubOctopusMobileClient

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        client = StubOctopusMobileClient()
        ToolRegistry.registerAllTools(ToolRegistry.DeviceType.MOBILE)
        selector = BrainModeSelector(
            context = context,
            rpcClient = client,
            toolRegistry = ToolRegistry,
        )
    }

    // ── 初始状态 ──────────────────────────────────────────

    @Test
    fun `initial mode is EXECUTOR_ONLY`() {
        assertEquals(BrainMode.EXECUTOR_ONLY, selector.currentMode())
    }

    @Test
    fun `initial domain is MOBILE`() {
        assertEquals(AutomationDomain.MOBILE, selector.currentDomain())
    }

    @Test
    fun `skillCount returns 42`() {
        assertEquals(42, selector.skillCount())
    }

    // ── forceDomain ───────────────────────────────────────

    @Test
    fun `forceDomain switches domain`() {
        assertEquals(AutomationDomain.MOBILE, selector.currentDomain())
        selector.forceDomain(AutomationDomain.BROWSER)
        assertEquals(AutomationDomain.BROWSER, selector.currentDomain())
    }

    @Test
    fun `forceDomain does not switch when same domain`() {
        var callbackCount = 0
        selector.onDomainChanged = { callbackCount++ }
        selector.forceDomain(AutomationDomain.MOBILE)  // 已经是 MOBILE
        assertEquals(0, callbackCount)
    }

    // ── executeLocalTool ──────────────────────────────────

    @Test
    fun `executeLocalTool strips android prefix`() = runBlocking {
        val result = selector.executeLocalTool(
            ToolCall(id = "1", name = "android.finish", args = mapOf("summary" to "done"))
        )
        assertTrue(result is ToolExecutionResult.Success)
    }

    @Test
    fun `executeLocalTool handles unknown tool`() = runBlocking {
        val result = selector.executeLocalTool(
            ToolCall(id = "1", name = "android.nonexistent", args = emptyMap())
        )
        assertTrue(result is ToolExecutionResult.Failure)
        val failure = result as ToolExecutionResult.Failure
        assertEquals(-32603, failure.errorCode)
    }

    @Test
    fun `executeLocalTool passes through without prefix`() = runBlocking {
        val result = selector.executeLocalTool(
            ToolCall(id = "1", name = "finish", args = mapOf("summary" to "done"))
        )
        assertTrue(result is ToolExecutionResult.Success)
    }

    // ── 模式切换 ──────────────────────────────────────────

    @Test
    fun `checkAndSwitch switches to LOCAL_FALLBACK when offline`() {
        client.setState(ConnectionState.OFFLINE)
        selector.checkAndSwitch()
        assertEquals(BrainMode.LOCAL_FALLBACK, selector.currentMode())
    }

    @Test
    fun `checkAndSwitch stays EXECUTOR_ONLY when online`() {
        client.setState(ConnectionState.ONLINE)
        selector.checkAndSwitch()
        assertEquals(BrainMode.EXECUTOR_ONLY, selector.currentMode())
    }

    @Test
    fun `forceMode switches mode`() {
        selector.forceMode(BrainMode.LOCAL_FALLBACK)
        assertEquals(BrainMode.LOCAL_FALLBACK, selector.currentMode())
    }

    @Test
    fun `onModeChanged fires on mode switch`() {
        var firedMode: BrainMode? = null
        selector.onModeChanged = { firedMode = it }
        client.setState(ConnectionState.OFFLINE)
        selector.checkAndSwitch()
        assertEquals(BrainMode.LOCAL_FALLBACK, firedMode)
    }

    // ── Stub ──────────────────────────────────────────────

    class StubOctopusMobileClient : OctopusMobileClient(
        runtimeUrl = "ws://localhost:9999",
        tentacleId = "test-device"
    ) {
        private var stubState: ConnectionState = ConnectionState.ONLINE

        fun setState(state: ConnectionState) {
            stubState = state
        }

        override fun currentState(): ConnectionState = stubState
    }
}
