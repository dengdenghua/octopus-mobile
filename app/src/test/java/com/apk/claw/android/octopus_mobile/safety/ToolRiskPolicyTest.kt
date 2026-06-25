package com.apk.claw.android.octopus_mobile.safety

import org.junit.Assert.*
import org.junit.Test

/**
 * ToolRiskPolicy 测试 —— 工具风险分级 + 审计摘要脱敏（纯逻辑）。
 *
 * 安全要点：高危工具必须被判为 high（决定来源闸门 / 审批),敏感参数(key/token/secret…)
 * 在审计摘要里必须脱敏,不能把凭据明文落审计日志。
 */
class ToolRiskPolicyTest {

    // ── riskOf ───────────────────────────────────────────
    @Test
    fun `high risk tools classified high`() {
        for (t in listOf("send_sms", "send_intent", "file_ops", "install_app",
                          "browser_evaluate", "browser_install_extension", "send_file")) {
            assertEquals("$t should be high", ToolRiskPolicy.RISK_HIGH, ToolRiskPolicy.riskOf(t))
        }
    }

    @Test
    fun `medium risk tools classified medium`() {
        for (t in listOf("tap", "swipe", "input_text", "open_app", "read_sms", "read_calendar")) {
            assertEquals("$t should be medium", ToolRiskPolicy.RISK_MEDIUM, ToolRiskPolicy.riskOf(t))
        }
    }

    @Test
    fun `unknown tool defaults to low`() {
        assertEquals(ToolRiskPolicy.RISK_LOW, ToolRiskPolicy.riskOf("totally_unknown_tool"))
        assertEquals(ToolRiskPolicy.RISK_LOW, ToolRiskPolicy.riskOf(""))
    }

    // ── shouldAudit ──────────────────────────────────────
    @Test
    fun `shouldAudit true for high and medium, false for low`() {
        assertTrue(ToolRiskPolicy.shouldAudit("send_sms"))
        assertTrue(ToolRiskPolicy.shouldAudit("tap"))
        assertFalse(ToolRiskPolicy.shouldAudit("unknown_tool"))
    }

    // ── isSensitiveKey ───────────────────────────────────
    @Test
    fun `sensitive keys detected case-insensitively`() {
        for (k in listOf("apiKey", "TOKEN", "password", "Authorization", "Cookie",
                         "credential", "api_base", "user_secret", "pwd")) {
            assertTrue("$k should be sensitive", ToolRiskPolicy.isSensitiveKey(k))
        }
    }

    @Test
    fun `non-sensitive keys not flagged`() {
        for (k in listOf("url", "text", "x", "y", "appName", "count")) {
            assertFalse("$k should not be sensitive", ToolRiskPolicy.isSensitiveKey(k))
        }
    }

    // ── summarizeParams ──────────────────────────────────
    @Test
    fun `summarizeParams redacts sensitive values and sorts by key`() {
        val out = ToolRiskPolicy.summarizeParams(mapOf("token" to "secret123", "x" to 5))
        assertEquals("{token=<redacted>, x=5}", out)
    }

    @Test
    fun `summarizeParams empty is braces`() {
        assertEquals("{}", ToolRiskPolicy.summarizeParams(emptyMap()))
    }

    @Test
    fun `summarizeParams truncates long non-sensitive value`() {
        val out = ToolRiskPolicy.summarizeParams(mapOf("name" to "abcdefghij"), maxValueChars = 3)
        assertEquals("{name=abc}", out)
    }

    // ── summarizeResult ──────────────────────────────────
    @Test
    fun `summarizeResult null becomes empty`() {
        assertEquals("", ToolRiskPolicy.summarizeResult(null))
    }

    @Test
    fun `summarizeResult flattens newlines and truncates`() {
        assertEquals("line1 line2", ToolRiskPolicy.summarizeResult("line1\nline2"))
        assertEquals(240, ToolRiskPolicy.summarizeResult("x".repeat(300)).length)
    }
}
