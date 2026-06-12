package com.apk.claw.android.octopus_mobile.safety

import org.junit.Assert.*
import org.junit.Test

/**
 * SafetyGate 测试 —— 规则层 + LLM 法官.
 */
class SafetyGateTest {

    // ── 规则层（零成本）─────────────────────────────────

    @Test
    fun `check blocks on secret`() {
        val gate = SafetyGate()
        // 扫描规则要求 sk- 后至少 20 个字母数字（真实 OpenAI key 为 sk- + 48 字符）
        val verdict = gate.check("my key is sk-abc1234567890abcdefghij", "tool_call")
        assertTrue(verdict.isBlocked)
        assertEquals("OpenAI API key", verdict.secretHits[0].description)
    }

    @Test
    fun `check allows safe text`() {
        val gate = SafetyGate()
        val verdict = gate.check("hello world", "tool_call")
        assertFalse(verdict.isBlocked)
        assertTrue(verdict.secretHits.isEmpty())
        assertTrue(verdict.piiHits.isEmpty())
    }

    @Test
    fun `check scrubs pii but allows`() {
        val gate = SafetyGate()
        val verdict = gate.check("email me at test@example.com", "tool_call")
        assertFalse(verdict.isBlocked)
        assertEquals(1, verdict.piiHits.size)
        assertEquals("email address", verdict.piiHits[0].description)
    }

    @Test
    fun `checkToolCall blocks on secret in params`() {
        val gate = SafetyGate()
        val verdict = gate.checkToolCall(
            "browser_navigate",
            mapOf("url" to "https://example.com?key=sk-abc1234567890abcdefghij"),
        )
        assertTrue(verdict.isBlocked)
    }

    // ── LLM 法官层（可选）───────────────────────────────

    @Test
    fun `llm judge blocks on policy violation`() {
        val mockJudge = object : ConstitutionJudge {
            override fun judge(message: String, destination: String): ConstitutionJudge.JudgeVerdict {
                return ConstitutionJudge.JudgeVerdict(
                    action = JudgeAction.BLOCK,
                    reason = "simulated policy violation",
                )
            }
        }
        val gate = SafetyGate(judge = mockJudge)
        val verdict = gate.check("some text", "tool_call")
        assertTrue(verdict.isBlocked)
        assertEquals("simulated policy violation", verdict.judgeVerdict?.reason)
    }

    @Test
    fun `llm judge escalates to human`() {
        val mockJudge = object : ConstitutionJudge {
            override fun judge(message: String, destination: String): ConstitutionJudge.JudgeVerdict {
                return ConstitutionJudge.JudgeVerdict(
                    action = JudgeAction.HUMAN_GATE,
                    reason = "ambiguous",
                )
            }
        }
        val gate = SafetyGate(judge = mockJudge)
        val verdict = gate.check("some text", "tool_call")
        assertFalse(verdict.isBlocked)
        assertTrue(verdict.needsHuman)
    }

    @Test
    fun `null judge always allows`() {
        val gate = SafetyGate()
        val verdict = gate.check("any text", "tool_call")
        assertFalse(verdict.isBlocked)
        assertFalse(verdict.needsHuman)
    }

    // ── NullJudge ─────────────────────────────────────

    @Test
    fun `NullJudge always allows`() {
        val judge = NullJudge()
        val v = judge.judge("anything", "tool_call")
        assertEquals(JudgeAction.ALLOW, v.action)
    }
}
