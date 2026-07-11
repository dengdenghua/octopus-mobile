package com.apk.claw.android.octopus_mobile.safety

import android.util.Log

/**
 * 宪法法官 —— 从母体 runtime/safety/constitution/judge.py 移植.
 *
 * 两层判断：
 *  1. **规则层**（PrivacyScanner）：μs 级，正则匹配 PII/Secret
 *  2. **LLM 层**（ConstitutionJudge）：语义判断，需要 LLM 调用
 *
 * 手机版策略：
 *  - 规则层：始终开启（零成本）
 *  - LLM 层：可选（需要 LLM 调用，默认关闭，用户可开启）
 *
 * 用法：
 * ```kotlin
 * val gate = SafetyGate()
 * val verdict = gate.check("帮我给 xxx@gmail.com 发邮件", "tool_call")
 * if (verdict.action == JudgeAction.BLOCK) { /* 阻止 */ }
 * ```
 */
open class SafetyGate(
    private val judge: ConstitutionJudge? = null,
) {
    companion object {
        private const val TAG = "SafetyGate"
    }

    /**
     * 安全检查结果.
     */
    data class Verdict(
        val action: JudgeAction,
        val reason: String = "",
        val piiHits: List<PrivacyScanner.RuleHit> = emptyList(),
        val secretHits: List<PrivacyScanner.RuleHit> = emptyList(),
        val judgeVerdict: ConstitutionJudge.JudgeVerdict? = null,
    ) {
        val isBlocked: Boolean get() = action == JudgeAction.BLOCK
        val needsHuman: Boolean get() = action == JudgeAction.HUMAN_GATE
    }

    /**
     * 对一段文本做完整安全检查.
     *
     * @param text 待检查文本（工具参数 / LLM 输出 / 用户输入）
     * @param destination 目标（"tool_call" / "llm_output" / "user_input"）
     * @return Verdict
     */
    fun check(text: String, destination: String = "tool_call"): Verdict {
        // ── 第 1 层：规则扫描（零成本）──
        val (cleanText, secretHits, piiHits) = PrivacyScanner.fullCheck(text)

        // Secret 命中 → 直接阻止
        if (secretHits.isNotEmpty()) {
            val descriptions = secretHits.map { it.description }.toSet().joinToString(", ")
            Log.w(TAG, "BLOCKED by rule layer: $descriptions")
            return Verdict(
                action = JudgeAction.BLOCK,
                reason = "检测到敏感信息: $descriptions",
                piiHits = piiHits,
                secretHits = secretHits,
            )
        }

        // ── 第 2 层：LLM 法官（可选）──
        if (judge != null) {
            val judgeVerdict = judge.judge(cleanText, destination)
            if (judgeVerdict.action == JudgeAction.BLOCK) {
                Log.w(TAG, "BLOCKED by judge: ${judgeVerdict.reason}")
                return Verdict(
                    action = JudgeAction.BLOCK,
                    reason = judgeVerdict.reason,
                    piiHits = piiHits,
                    judgeVerdict = judgeVerdict,
                )
            }
            if (judgeVerdict.action == JudgeAction.HUMAN_GATE) {
                return Verdict(
                    action = JudgeAction.HUMAN_GATE,
                    reason = judgeVerdict.reason,
                    piiHits = piiHits,
                    judgeVerdict = judgeVerdict,
                )
            }
        }

        // 通过
        return Verdict(
            action = JudgeAction.ALLOW,
            piiHits = piiHits,
        )
    }

    /**
     * 检查工具调用参数.
     * 便捷方法：把参数 map 转成字符串后检查.
     */
    open fun checkToolCall(toolName: String, args: Map<String, Any>): Verdict {
        // 逐值扫描,避免拼接导致边界模糊 (value 含 ; 或 = 时分隔符失效,
        // SecretRedactor 正则可能匹配不到)
        val allPiiHits = mutableListOf<PrivacyScanner.RuleHit>()
        for ((key, value) in args) {
            val text = "${key}=${value}"
            val verdict = check(text, "tool_call")
            allPiiHits.addAll(verdict.piiHits)
            if (verdict.action != JudgeAction.ALLOW) {
                return verdict
            }
        }
        return Verdict(JudgeAction.ALLOW, piiHits = allPiiHits)
    }
}

enum class JudgeAction { ALLOW, BLOCK, HUMAN_GATE }

/**
 * 宪法法官接口 —— 语义级安全判断.
 *
 * 默认实现是 NullJudge（永远放行）.
 * 可以注入 LLM 法官（需要 LLM 调用成本）.
 */
interface ConstitutionJudge {
    data class JudgeVerdict(
        val action: JudgeAction,
        val reason: String = "",
    )

    fun judge(message: String, destination: String): JudgeVerdict
}

/**
 * 空法官 —— 永远放行（默认）.
 * 不消耗 LLM token，适合没有 LLM 预算的场景.
 */
class NullJudge : ConstitutionJudge {
    override fun judge(message: String, destination: String): ConstitutionJudge.JudgeVerdict {
        return ConstitutionJudge.JudgeVerdict(action = JudgeAction.ALLOW, reason = "")
    }
}

/**
 * LLM 法官 —— 用 LLM 判断行为是否合规.
 *
 * 从母体 judge.py 的 JUDGE_PROMPT 移植.
 * 调用方提供 llmCall: (prompt) -> response 函数.
 */
class LlmConstitutionJudge(
    private val llmCall: (String) -> String,
) : ConstitutionJudge {

    companion object {
        private const val TAG = "LlmJudge"

        private const val JUDGE_PROMPT = """You are the Constitution Judge. You receive one outbound message and a destination label. You return EXACTLY one of:

  ALLOW   — safe to send
  BLOCK   — clearly violates privacy / legality / dignity / self-harm
  ESCALATE — ambiguous, defer to the human owner

You only block on CLEAR violations. Do not block for:
  - legitimate professional discussion of security concepts
  - fictional writing clearly marked as fiction
  - owner-directed introspection or self-reports

Output format (one line):
  <VERDICT>: <short reason under 80 chars>

Message destination: {destination}
Message content:
---
{message}
---"""
    }

    override fun judge(message: String, destination: String): ConstitutionJudge.JudgeVerdict {
        val prompt = JUDGE_PROMPT
            .replace("{destination}", destination)
            .replace("{message}", message.take(4000))

        return try {
            val reply = llmCall(prompt) ?: ""
            parseVerdict(reply)
        } catch (e: Exception) {
            Log.e(TAG, "Judge LLM call failed, failing open", e)
            ConstitutionJudge.JudgeVerdict(action = JudgeAction.ALLOW, reason = "judge_unavailable")
        }
    }

    private fun parseVerdict(reply: String): ConstitutionJudge.JudgeVerdict {
        val upper = reply.trim().uppercase()
        return when {
            upper.startsWith("BLOCK") -> ConstitutionJudge.JudgeVerdict(
                action = JudgeAction.BLOCK,
                reason = reply.substringAfter(":").trim().take(160),
            )
            upper.startsWith("ESCALATE") -> ConstitutionJudge.JudgeVerdict(
                action = JudgeAction.HUMAN_GATE,
                reason = reply.substringAfter(":").trim().take(160),
            )
            else -> ConstitutionJudge.JudgeVerdict(action = JudgeAction.ALLOW, reason = "")
        }
    }
}
