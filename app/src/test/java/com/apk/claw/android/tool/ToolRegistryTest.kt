package com.apk.claw.android.tool

import com.apk.claw.android.TestClawApplication
import com.apk.claw.android.octopus_mobile.ToolAuditLog
import com.apk.claw.android.octopus_mobile.browser.BrowserEngine
import com.apk.claw.android.octopus_mobile.browser.EngineEvent
import com.apk.claw.android.octopus_mobile.browser.EngineInfo
import com.apk.claw.android.octopus_mobile.browser.SystemWebViewEngine
import com.apk.claw.android.octopus_mobile.evolution.TurnScorer
import com.apk.claw.android.octopus_mobile.nerves.EventBus
import com.apk.claw.android.octopus_mobile.safety.JudgeAction
import com.apk.claw.android.octopus_mobile.safety.SafetyGate
import com.apk.claw.android.octopus_mobile.safety.ToolRiskPolicy
import com.apk.claw.android.octopus_mobile.safety.ToolCallGuardrailController
import com.apk.claw.android.utils.KVUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ToolRegistry 单元测试.
 *
 * 覆盖：
 *  - registerAllTools() TV / MOBILE 模式注册正确数量
 *  - register() / getTool() / getAllTools()
 *  - getDisplayName() 回退逻辑
 *  - setBrowserEngine() 注册/替换 browser tools
 *  - executeTool() 未知工具返回 error
 *  - executeTool() 正常执行返回 success
 *  - executeTool() 异常捕获返回 error
 *  - safetyGate 拦截路径
 *  - guardrail 预检查拦截路径
 *  - guardrail 失败后 WARN 路径
 *  - turnScorer 记录路径
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestClawApplication::class)
class ToolRegistryTest {

    @Before
    fun setUp() {
        // 每次测试前重置单例状态（含其他测试类可能遗留的 browserEngine）
        ToolRegistry.guardrail.reset()
        ToolRegistry.safetyGate = null
        ToolRegistry.turnScorer = null
        ToolRegistry.eventBus = null
        KVUtils.setToolDisabled("finish", false)
        KVUtils.setToolDisabled("send_sms", false)
        ToolAuditLog.clear()
        ToolRegistry.clearBrowserEngine()
    }

    // ── 注册与查询 ────────────────────────────────────────

    @Test
    fun `registerAllTools TV mode registers correct tools`() {
        ToolRegistry.registerAllTools(ToolRegistry.DeviceType.TV)
        val tools = ToolRegistry.getAllTools()
        val names = tools.map { it.getName() }.toSet()

        assertTrue("TV registry should include common, TV, and system tools", tools.size >= 36)

        // TV 特有
        assertTrue(names.contains("dpad_up"))
        assertTrue(names.contains("dpad_down"))
        assertTrue(names.contains("volume_up"))
        assertTrue(names.contains("press_power"))

        // Mobile 特有（不应存在）
        assertFalse(names.contains("tap"))
        assertFalse(names.contains("swipe"))

        // Browser tools（无 engine 时不注册）
        assertFalse(names.contains("browser_navigate"))
    }

    @Test
    fun `registerAllTools MOBILE mode registers correct tools`() {
        ToolRegistry.registerAllTools(ToolRegistry.DeviceType.MOBILE)
        val tools = ToolRegistry.getAllTools()
        val names = tools.map { it.getName() }.toSet()

        assertTrue("Mobile registry should include common, mobile, and system tools", tools.size >= 34)

        // Mobile 特有
        assertTrue(names.contains("tap"))
        assertTrue(names.contains("long_press"))
        assertTrue(names.contains("swipe"))
        assertTrue(names.contains("scroll_to_find"))

        // TV 特有（不应存在）
        assertFalse(names.contains("dpad_up"))
        assertFalse(names.contains("volume_up"))
    }

    @Test
    fun `getTool returns registered tool`() {
        ToolRegistry.registerAllTools(ToolRegistry.DeviceType.MOBILE)
        val tool = ToolRegistry.getTool("tap")
        assertNotNull(tool)
        assertEquals("tap", tool!!.getName())
    }

    @Test
    fun `getTool returns null for unknown tool`() {
        ToolRegistry.registerAllTools(ToolRegistry.DeviceType.MOBILE)
        assertNull(ToolRegistry.getTool("nonexistent"))
    }

    @Test
    fun `getDisplayName returns tool display name`() {
        ToolRegistry.registerAllTools(ToolRegistry.DeviceType.MOBILE)
        val name = ToolRegistry.getDisplayName("tap")
        assertNotEquals("tap", name)  // 应有中文显示名
        assertTrue(name.isNotBlank())
    }

    @Test
    fun `getDisplayName falls back to raw name for unknown tool`() {
        assertEquals("unknown_tool", ToolRegistry.getDisplayName("unknown_tool"))
    }

    // ── BrowserEngine 集成 ────────────────────────────────

    @Test
    fun `setBrowserEngine registers browser tools`() {
        ToolRegistry.registerAllTools(ToolRegistry.DeviceType.MOBILE)
        val engine = StubBrowserEngine()
        ToolRegistry.setBrowserEngine(engine)

        val names = ToolRegistry.getAllTools().map { it.getName() }.toSet()
        assertTrue(names.contains("browser_navigate"))
        assertTrue(names.contains("browser_get_dom"))
        assertTrue(names.contains("browser_click"))
        assertTrue(names.contains("browser_type"))
        assertTrue(names.contains("browser_screenshot"))
        assertTrue(names.contains("browser_evaluate"))
        assertTrue(names.contains("browser_install_extension"))
    }

    @Test
    fun `setBrowserEngine replaces previous browser tools`() {
        ToolRegistry.registerAllTools(ToolRegistry.DeviceType.MOBILE)
        val engine1 = StubBrowserEngine()
        ToolRegistry.setBrowserEngine(engine1)

        val tool1 = ToolRegistry.getTool("browser_navigate")
        assertNotNull(tool1)

        // 替换 engine
        val engine2 = StubBrowserEngine()
        ToolRegistry.setBrowserEngine(engine2)

        val tool2 = ToolRegistry.getTool("browser_navigate")
        assertNotNull(tool2)
        // tool2 应该绑定到 engine2（无法直接比较，但至少不 crash）
    }

    // ── executeTool ───────────────────────────────────────

    @Test
    fun `executeTool returns error for unknown tool`() {
        ToolRegistry.registerAllTools(ToolRegistry.DeviceType.MOBILE)
        val result = ToolRegistry.executeTool("nonexistent", emptyMap())
        assertFalse(result.isSuccess)
        assertTrue(result.error!!.contains("Unknown tool"))
    }

    @Test
    fun `executeTool executes finish tool successfully`() {
        ToolRegistry.registerAllTools(ToolRegistry.DeviceType.MOBILE)
        val result = ToolRegistry.executeTool("finish", mapOf("summary" to "done"))
        assertTrue(result.isSuccess)
    }

    @Test
    fun `executeTool catches exception and returns error`() {
        ToolRegistry.registerAllTools(ToolRegistry.DeviceType.MOBILE)
        // 用 tap 工具（不传 y）来触发 missing-required-parameter 错误
        val result = ToolRegistry.executeTool("tap", mapOf("x" to 100))
        assertFalse(result.isSuccess)
        // 错误信息应该包含 "Missing" 或 "Accessibility"（任一即可）
        val err = result.error ?: ""
        assertTrue(
            "error should describe failure: got '$err'",
            err.contains("Missing") || err.contains("Accessibility") ||
                err.contains("failed") || err.contains("IllegalArgument")
        )
    }

    @Test
    fun `executeTool blocks disabled tool`() {
        ToolRegistry.registerAllTools(ToolRegistry.DeviceType.MOBILE)
        KVUtils.setToolDisabled("finish", true)

        val result = ToolRegistry.executeTool("finish", mapOf("summary" to "done"))

        assertFalse(result.isSuccess)
        assertTrue(result.error!!.contains("工具已停用"))
    }

    @Test
    fun `executeTool records audit log for high risk tool`() {
        ToolRegistry.registerAllTools(ToolRegistry.DeviceType.MOBILE)

        val result = ToolRegistry.executeTool(
            "send_sms",
            mapOf("phone_number" to "13800138000", "message" to "hello", "api_token" to "secret-token"),
        )

        val entry = ToolAuditLog.all().firstOrNull()
        assertNotNull(entry)
        assertEquals("send_sms", entry!!.toolName)
        assertEquals(ToolRiskPolicy.RISK_HIGH, entry.risk)
        assertEquals(result.isSuccess, entry.success)
        assertTrue(entry.params.contains("api_token=<redacted>"))
        assertFalse(entry.params.contains("secret-token"))
    }

    @Test
    fun `executeTool publishes audit event for blocked high risk tool`() {
        ToolRegistry.registerAllTools(ToolRegistry.DeviceType.MOBILE)
        KVUtils.setToolDisabled("send_sms", true)

        val eventBus = EventBus()
        var capturedEvent: EventBus.ToolAuditEvent? = null
        eventBus.subscribe(EventBus.ToolAuditEvent::class.java) { event ->
            capturedEvent = event
        }
        ToolRegistry.eventBus = eventBus

        val result = ToolRegistry.executeTool("send_sms", mapOf("phone_number" to "13800138000", "message" to "hello"))

        assertFalse(result.isSuccess)
        assertNotNull(capturedEvent)
        assertEquals("send_sms", capturedEvent!!.toolName)
        assertEquals("settings", capturedEvent!!.blockedBy)
        assertEquals("settings", ToolAuditLog.all().first().blockedBy)
    }

    // ── SafetyGate 拦截 ───────────────────────────────────

    @Test
    fun `executeTool blocks when safetyGate returns blocked`() {
        ToolRegistry.registerAllTools(ToolRegistry.DeviceType.MOBILE)
        ToolRegistry.safetyGate = StubSafetyGate(blocked = true, reason = "PII detected")

        val result = ToolRegistry.executeTool("finish", emptyMap())
        assertFalse(result.isSuccess)
        assertTrue(result.error!!.contains("安全拦截"))
        assertTrue(result.error!!.contains("PII detected"))
    }

    @Test
    fun `executeTool allows when safetyGate returns allow`() {
        ToolRegistry.registerAllTools(ToolRegistry.DeviceType.MOBILE)
        ToolRegistry.safetyGate = StubSafetyGate(blocked = false)

        val result = ToolRegistry.executeTool("finish", mapOf("summary" to "done"))
        assertTrue(result.isSuccess)
    }

    // ── Guardrail 拦截 ────────────────────────────────────

    @Test
    fun `executeTool halts when guardrail preCheck shouldHalt`() {
        ToolRegistry.registerAllTools(ToolRegistry.DeviceType.MOBILE)
        // 制造 guardrail 触发条件：连续多次失败
        repeat(5) {
            ToolRegistry.guardrail.observe("tap", mapOf("x" to 100, "y" to 200), failed = true)
        }

        val result = ToolRegistry.executeTool("tap", mapOf("x" to 100, "y" to 200))
        assertFalse(result.isSuccess)
        assertTrue(result.error!!.contains("护栏拦截"))
    }

    @Test
    fun `executeTool warns on repeated failure but still executes`() {
        ToolRegistry.registerAllTools(ToolRegistry.DeviceType.MOBILE)
        // 2 次失败触发 WARN（但不应阻止执行）
        repeat(2) {
            ToolRegistry.guardrail.observe("tap", mapOf("x" to 100, "y" to 200), failed = true)
        }

        // guardrail 预检查对成功调用不触发
        val result = ToolRegistry.executeTool("finish", mapOf("summary" to "done"))
        assertTrue(result.isSuccess)
    }

    // ── TurnScorer 记录 ───────────────────────────────────

    @Test
    fun `executeTool records to turnScorer on success`() {
        ToolRegistry.registerAllTools(ToolRegistry.DeviceType.MOBILE)
        val scorer = StubTurnScorer()
        ToolRegistry.turnScorer = scorer

        ToolRegistry.executeTool("finish", mapOf("summary" to "done"))
        assertEquals("finish", scorer.lastToolName)
        assertTrue(scorer.lastSuccess)
    }

    @Test
    fun `executeTool records to turnScorer on failure`() {
        ToolRegistry.registerAllTools(ToolRegistry.DeviceType.MOBILE)
        val scorer = StubTurnScorer()
        ToolRegistry.turnScorer = scorer

        // 触发失败
        ToolRegistry.executeTool("tap", mapOf("x" to "bad"))
        assertEquals("tap", scorer.lastToolName)
        assertFalse(scorer.lastSuccess)
    }

    // ── 单例模式 ──────────────────────────────────────────

    @Test
    fun `getInstance returns same object`() {
        val instance1 = ToolRegistry.getInstance()
        val instance2 = ToolRegistry.getInstance()
        assertSame(instance1, instance2)
    }

    // ── EventBus 集成 ──────────────────────────────────

    @Test
    fun `executeTool publishes ToolBlockedEvent when safetyGate blocks`() {
        ToolRegistry.registerAllTools(ToolRegistry.DeviceType.MOBILE)
        ToolRegistry.safetyGate = StubSafetyGate(blocked = true, reason = "PII detected")

        val eventBus = EventBus()
        var capturedEvent: EventBus.ToolBlockedEvent? = null
        eventBus.subscribe(EventBus.ToolBlockedEvent::class.java) { event ->
            capturedEvent = event
        }
        ToolRegistry.eventBus = eventBus

        ToolRegistry.executeTool("finish", emptyMap())
        assertNotNull(capturedEvent)
        assertEquals("finish", capturedEvent!!.toolName)
        assertEquals("PII detected", capturedEvent!!.reason)
        assertEquals("safety", capturedEvent!!.gate)
    }

    @Test
    fun `executeTool publishes ToolBlockedEvent when guardrail blocks`() {
        ToolRegistry.registerAllTools(ToolRegistry.DeviceType.MOBILE)
        // 触发 guardrail block：连续 6 次相同失败
        repeat(6) {
            ToolRegistry.guardrail.observe("tap", mapOf("x" to 100, "y" to 200), failed = true)
        }

        val eventBus = EventBus()
        var capturedEvent: EventBus.ToolBlockedEvent? = null
        eventBus.subscribe(EventBus.ToolBlockedEvent::class.java) { event ->
            capturedEvent = event
        }
        ToolRegistry.eventBus = eventBus

        ToolRegistry.executeTool("tap", mapOf("x" to 100, "y" to 200))
        assertNotNull(capturedEvent)
        assertEquals("tap", capturedEvent!!.toolName)
        assertEquals("guardrail", capturedEvent!!.gate)
    }

    @Test
    fun `executeTool does not crash when eventBus is null`() {
        ToolRegistry.registerAllTools(ToolRegistry.DeviceType.MOBILE)
        ToolRegistry.safetyGate = StubSafetyGate(blocked = true)
        ToolRegistry.eventBus = null

        // Should not throw
        val result = ToolRegistry.executeTool("finish", emptyMap())
        assertFalse(result.isSuccess)
    }

    // ── Stub 实现 ─────────────────────────────────────────

    class StubBrowserEngine : BrowserEngine {
        override val name = "Stub"
        override val antiBotScore = 50
        override val supportsExtensions = false
        override val supportsEval = true

        override fun createView(context: android.content.Context): android.view.View {
            throw NotImplementedError()
        }
        override fun isAvailable(): Boolean = true
        override fun describe(): EngineInfo = EngineInfo(name, "1.0", "Stub", false, 50)
        override fun events(): Flow<EngineEvent> = emptyFlow()
        override fun navigate(url: String) {}
        override fun currentUrl(): String = ""
        override fun evaluateJs(script: String, callback: ((String?) -> Unit)?) {
            callback?.invoke(null)
        }
        override fun screenshot(): String? = null
    }

    class StubSafetyGate(
        private val blocked: Boolean,
        private val reason: String = ""
    ) : SafetyGate() {
        override fun checkToolCall(toolName: String, args: Map<String, Any>): Verdict {
            return if (blocked) {
                Verdict(action = JudgeAction.BLOCK, reason = reason)
            } else {
                Verdict(action = JudgeAction.ALLOW)
            }
        }
    }

    class StubTurnScorer : TurnScorer(
        dataDir = java.io.File(System.getProperty("java.io.tmpdir") ?: "/tmp")
    ) {
        var lastToolName: String? = null
        var lastSuccess: Boolean = false

        override fun record(toolName: String, success: Boolean, rounds: Int, reason: String) {
            lastToolName = toolName
            lastSuccess = success
        }
    }
}
