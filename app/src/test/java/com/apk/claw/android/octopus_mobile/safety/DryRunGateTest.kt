@file:Suppress("PackageNaming")   // 沿用既有 octopus_mobile.safety 包(带下划线)

package com.apk.claw.android.octopus_mobile.safety

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DryRunGate 测试 —— 纯 JVM。演示模式下改动型工具跳过、只读工具照常;关闭时全放行。
 */
class DryRunGateTest {

    @Test
    fun `mutating tools are skipped when enabled`() {
        assertTrue(DryRunGate.shouldSkip("tap", enabled = true))
        assertTrue(DryRunGate.shouldSkip("input_text", enabled = true))
        assertTrue(DryRunGate.shouldSkip("send_sms", enabled = true))
        assertTrue("危险工具也算改动型,跳过", DryRunGate.shouldSkip("run_code", enabled = true))
    }

    @Test
    fun `read-only tools pass through even when enabled`() {
        // IDEMPOTENT(只读)工具照常执行,Agent 才能看屏/规划
        assertFalse(DryRunGate.shouldSkip("take_screenshot", enabled = true))
        assertFalse(DryRunGate.shouldSkip("get_screen_info", enabled = true))
        assertFalse(DryRunGate.shouldSkip("look_at_screen", enabled = true))
        assertFalse(DryRunGate.shouldSkip("find_node_info", enabled = true))
    }

    @Test
    fun `nothing skipped when disabled`() {
        assertFalse(DryRunGate.shouldSkip("tap", enabled = false))
        assertFalse(DryRunGate.shouldSkip("send_sms", enabled = false))
    }

    @Test
    fun `skipResult is success and marked as preview`() {
        val r = DryRunGate.skipResult("tap")
        assertTrue("演示结果标记成功,让 Agent 继续走流程", r.isSuccess)
        assertTrue(r.data?.contains("演示模式") == true)
    }
}
