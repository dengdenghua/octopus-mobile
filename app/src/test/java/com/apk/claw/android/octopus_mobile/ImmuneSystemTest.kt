@file:Suppress("PackageNaming")   // 沿用既有 octopus_mobile 包(带下划线)

package com.apk.claw.android.octopus_mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ImmuneSystem 测试 —— 风险预检 / 基线学习 / 频率异常。
 *
 * ImmuneSystem 是单例、跨测试累积状态,故每个用例用**独立工具名**隔离,避免相互污染。
 */
class ImmuneSystemTest {

    @Test
    fun `high-risk tool warns on precheck`() {
        val v = ImmuneSystem.preCheck("send_sms", emptyMap())
        assertEquals(ImmuneSystem.Verdict.WARN, v.verdict)
        assertTrue("riskScore should be >= 0.3 but was ${v.riskScore}", v.riskScore >= 0.3)
        assertEquals("high_risk_tool", v.reason)
    }

    @Test
    fun `unknown safe tool is allowed`() {
        val v = ImmuneSystem.preCheck("imm_safe_noop_tool", emptyMap())
        assertEquals(ImmuneSystem.Verdict.ALLOW, v.verdict)
        assertEquals(0.0, v.riskScore, 1e-9)
    }

    @Test
    fun `postResult builds baseline reflected in stats`() {
        val tool = "imm_stats_tool"
        ImmuneSystem.postResult(tool, 120, 40, false)
        ImmuneSystem.postResult(tool, 130, 42, false)
        val stats = ImmuneSystem.getStats(tool)
        assertTrue("stats missing call count: $stats", stats.contains("calls=2"))
    }

    @Test
    fun `frequency anomaly warns after baseline established`() {
        val tool = "imm_freq_tool"
        // 建立基线(>= BASELINE_SAMPLES=10)且短时间内高频调用 → 频率异常
        repeat(11) { ImmuneSystem.postResult(tool, 50, 10, false) }
        val v = ImmuneSystem.preCheck(tool, emptyMap())
        assertEquals(ImmuneSystem.Verdict.WARN, v.verdict)
        assertTrue("reason should flag high_frequency: ${v.reason}", v.reason.startsWith("high_frequency"))
    }
}
