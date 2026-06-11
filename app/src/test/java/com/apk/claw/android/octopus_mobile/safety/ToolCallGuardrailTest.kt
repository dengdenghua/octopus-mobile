package com.apk.claw.android.octopus_mobile.safety

import org.junit.Assert.*
import org.junit.Test

/**
 * ToolCallGuardrail 测试 —— 重复失败 / 无进展 / 工具分类.
 */
class ToolCallGuardrailTest {

    // ── 工具分类 ──────────────────────────────────────

    @Test
    fun `classifyTool idempotent`() {
        assertEquals(ToolKind.IDEMPOTENT, ToolCallGuardrailController.classifyTool("get_screen_info"))
        assertEquals(ToolKind.IDEMPOTENT, ToolCallGuardrailController.classifyTool("browser_get_dom"))
        assertEquals(ToolKind.IDEMPOTENT, ToolCallGuardrailController.classifyTool("take_screenshot"))
    }

    @Test
    fun `classifyTool mutating`() {
        assertEquals(ToolKind.MUTATING, ToolCallGuardrailController.classifyTool("tap"))
        assertEquals(ToolKind.MUTATING, ToolCallGuardrailController.classifyTool("browser_navigate"))
    }

    @Test
    fun `classifyTool dangerous`() {
        assertEquals(ToolKind.DANGEROUS, ToolCallGuardrailController.classifyTool("browser_install_extension"))
    }

    @Test
    fun `classifyTool unknown`() {
        assertEquals(ToolKind.UNKNOWN, ToolCallGuardrailController.classifyTool("unknown_tool"))
    }

    // ── 重复失败 ──────────────────────────────────────

    @Test
    fun `exact failure warn after 2`() {
        val guard = ToolCallGuardrailController()
        guard.observe("tap", mapOf("x" to 100, "y" to 200), failed = true)
        guard.observe("tap", mapOf("x" to 100, "y" to 200), failed = true)
        val d = guard.observe("tap", mapOf("x" to 100, "y" to 200), failed = true)
        assertEquals(GuardrailAction.WARN, d.action)
        assertTrue(d.message.contains("2"))
    }

    @Test
    fun `exact failure block after 5`() {
        val guard = ToolCallGuardrailController()
        repeat(5) {
            guard.observe("tap", mapOf("x" to 100, "y" to 200), failed = true)
        }
        val d = guard.observe("tap", mapOf("x" to 100, "y" to 200), failed = true)
        assertEquals(GuardrailAction.BLOCK, d.action)
    }

    @Test
    fun `same tool halt after 8`() {
        val guard = ToolCallGuardrailController()
        repeat(8) {
            guard.observe("tap", mapOf("x" to it, "y" to it), failed = true)
        }
        val d = guard.observe("tap", mapOf("x" to 999, "y" to 999), failed = true)
        assertEquals(GuardrailAction.HALT, d.action)
    }

    @Test
    fun `success resets exact failure`() {
        val guard = ToolCallGuardrailController()
        guard.observe("tap", mapOf("x" to 100, "y" to 200), failed = true)
        guard.observe("tap", mapOf("x" to 100, "y" to 200), failed = true)
        guard.observe("tap", mapOf("x" to 100, "y" to 200), failed = false)
        val d = guard.observe("tap", mapOf("x" to 100, "y" to 200), failed = true)
        // 失败计数被重置了，所以不会 warn
        assertEquals(GuardrailAction.ALLOW, d.action)
    }

    // ── 无进展 ────────────────────────────────────────

    @Test
    fun `no progress warn after 2`() {
        val guard = ToolCallGuardrailController()
        guard.observe("get_screen_info", mapOf(), failed = false)
        guard.observe("get_screen_info", mapOf(), failed = false)
        val d = guard.observe("get_screen_info", mapOf(), failed = false)
        assertEquals(GuardrailAction.WARN, d.action)
    }

    @Test
    fun `no progress block after 5`() {
        val guard = ToolCallGuardrailController()
        repeat(5) {
            guard.observe("get_screen_info", mapOf(), failed = false)
        }
        val d = guard.observe("get_screen_info", mapOf(), failed = false)
        assertEquals(GuardrailAction.BLOCK, d.action)
    }

    @Test
    fun `mutating tool does not trigger no progress`() {
        val guard = ToolCallGuardrailController()
        repeat(10) {
            guard.observe("tap", mapOf("x" to 100, "y" to 200), failed = false)
        }
        val d = guard.observe("tap", mapOf("x" to 100, "y" to 200), failed = false)
        assertEquals(GuardrailAction.ALLOW, d.action)
    }

    // ── 决策属性 ──────────────────────────────────────

    @Test
    fun `allowsExecution true for ALLOW and WARN`() {
        assertTrue(GuardrailDecision(GuardrailAction.ALLOW).allowsExecution)
        assertTrue(GuardrailDecision(GuardrailAction.WARN).allowsExecution)
        assertFalse(GuardrailDecision(GuardrailAction.BLOCK).allowsExecution)
        assertFalse(GuardrailDecision(GuardrailAction.HALT).allowsExecution)
    }

    @Test
    fun `shouldHalt true for BLOCK and HALT`() {
        assertFalse(GuardrailDecision(GuardrailAction.ALLOW).shouldHalt)
        assertFalse(GuardrailDecision(GuardrailAction.WARN).shouldHalt)
        assertTrue(GuardrailDecision(GuardrailAction.BLOCK).shouldHalt)
        assertTrue(GuardrailDecision(GuardrailAction.HALT).shouldHalt)
    }

    // ── onBlock 回调 ────────────────────────────────

    @Test
    fun `onBlock fires on exact failure block`() {
        val guard = ToolCallGuardrailController()
        var blockedTool: String? = null
        var blockedReason: String? = null
        guard.onBlock = { tool, reason ->
            blockedTool = tool
            blockedReason = reason
        }

        // 触发 BLOCK：连续 6 次完全相同失败
        repeat(6) {
            guard.observe("tap", mapOf("x" to 100, "y" to 200), failed = true)
        }

        assertNotNull(blockedTool)
        assertEquals("tap", blockedTool)
        assertTrue(blockedReason!!.contains("exact_failure_block"))
    }

    @Test
    fun `onBlock fires on no progress block`() {
        val guard = ToolCallGuardrailController()
        var blockedTool: String? = null
        guard.onBlock = { tool, _ -> blockedTool = tool }

        // 触发 BLOCK：幂等工具重复调用 6 次
        repeat(6) {
            guard.observe("get_screen_info", mapOf(), failed = false)
        }

        assertNotNull(blockedTool)
        assertEquals("get_screen_info", blockedTool)
    }

    @Test
    fun `onBlock does not fire on ALLOW`() {
        val guard = ToolCallGuardrailController()
        var blockFired = false
        guard.onBlock = { _, _ -> blockFired = true }

        guard.observe("tap", mapOf("x" to 100, "y" to 200), failed = true)
        assertFalse(blockFired)
    }

    @Test
    fun `onBlock does not fire on WARN`() {
        val guard = ToolCallGuardrailController()
        var blockFired = false
        guard.onBlock = { _, _ -> blockFired = true }

        // 2 次失败触发 WARN，但不触发 BLOCK
        repeat(2) {
            guard.observe("tap", mapOf("x" to 100, "y" to 200), failed = true)
        }
        guard.observe("tap", mapOf("x" to 100, "y" to 200), failed = true)
        assertFalse(blockFired)
    }
}
