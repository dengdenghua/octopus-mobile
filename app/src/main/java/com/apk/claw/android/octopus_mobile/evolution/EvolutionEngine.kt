package com.apk.claw.android.octopus_mobile.evolution

import android.util.Log
import org.json.JSONObject

/**
 * 自进化引擎 —— 从母体 runtime/memory/deep_evolution.py 移植.
 *
 * 三层进化：
 *
 *  B1（免费，TurnScorer）
 *      启发式打分 + 趋势检测。零 LLM 调用。
 *
 *  B2 · deepReflect（便宜，~2-3 分/次）
 *      单次 LLM 调用，评估最近 N 轮表现，给出一个具体行动建议。
 *      "加教训 X" / "回滚上次更新" / "无需行动"
 *
 * 用法：
 * ```kotlin
 * val engine = EvolutionEngine(scorer, llmCall = { prompt -> ... })
 *
 * // B2：便宜反思
 * val reflection = engine.deepReflect()
 * ```
 */
class EvolutionEngine(
    private val scorer: TurnScorer,
    private val llmCall: ((String) -> String)? = null,  // 可选，null 则只跑 B1
    private val lessonStore: LessonStore? = null,  // 可选，非空时反思结果自动写入教训库
) {
    companion object {
        private const val TAG = "EvolutionEngine"

        private const val REFLECT_SYSTEM = """You are a meta-evaluator for an AI agent. You read its recent turns and judge quality. Be terse and concrete. Output ONLY a JSON envelope.

Schema:
```json
{
  "overall_score": 0-100,
  "trend": "improving" | "stable" | "regressing",
  "dominant_failure_mode": "<short tag>" | null,
  "lesson_quality": "helpful" | "neutral" | "harmful" | "no_recent_lesson",
  "action": "add_lesson" | "revert_last" | "no_action",
  "action_detail": "<one-line concrete instruction>",
  "rationale": "<2-3 sentences why>"
}
```"""
    }

    // ── 结果类型 ──────────────────────────────────────

    data class ReflectResult(
        val ok: Boolean,
        val overallScore: Int? = null,
        val trend: String = "stable",
        val dominantFailure: String? = null,
        val action: String = "no_action",
        val actionDetail: String = "",
        val rationale: String = "",
        val error: String? = null,
    )

    // ── B2 · deepReflect ──────────────────────────────

    /**
     * 深度反思：单次 LLM 调用评估最近表现.
     *
     * 需要 llmCall 不为 null，否则返回 B1 降级结果.
     */
    fun deepReflect(window: Int = 20): ReflectResult {
        val fitness = scorer.computeFitness()

        if (llmCall == null) {
            // 降级到 B1
            return ReflectResult(
                ok = true,
                overallScore = (fitness.score * 100).toInt(),
                trend = fitness.trend,
                dominantFailure = fitness.topFailure,
                action = when {
                    fitness.verdict == "critical" -> "add_lesson"
                    fitness.verdict == "unhealthy" -> "add_lesson"
                    fitness.trend == "regressing" -> "revert_last"
                    else -> "no_action"
                },
                actionDetail = when {
                    fitness.topFailure != null -> "工具 ${fitness.topFailure} 频繁失败，检查参数"
                    fitness.trend == "regressing" -> "表现下降趋势，考虑回滚最近变更"
                    else -> "表现正常，无需行动"
                },
                rationale = "B1 启发式评估（无 LLM）: score=${fitness.score}, verdict=${fitness.verdict}",
            )
        }

        // B2：LLM 反思
        val scores = scorer.readRecentScores(window)
        if (scores.isEmpty()) {
            return ReflectResult(ok = true, action = "no_action", rationale = "无打分数据")
        }

        val scoreRows = scores.joinToString("\n") { s ->
            "  - ${s.ts} · score=${s.score} · tool=${s.toolName} · reason=${s.reason} · rounds=${s.rounds}"
        }

        val userMsg = """
AGENT: octopus-mobile
WINDOW: last ${scores.size} scored turns

### Recent scores (newest first)
$scoreRows

### Heuristic verdict (free pre-analysis)
score=${fitness.score} trend=${fitness.trend} success_rate=${fitness.successRate} verdict=${fitness.verdict}
top_failure=${fitness.topFailure ?: "none"}

Now produce your JSON envelope.
""".trimIndent()

        return try {
            val reply = llmCall.invoke("$REFLECT_SYSTEM\n\n$userMsg") ?: ""
            val result = parseReflectReply(reply)
            // 反思结果自动写入教训库
            if (result.ok && result.action == "add_lesson" && result.actionDetail.isNotEmpty()) {
                lessonStore?.addLesson(LessonStore.Lesson(
                    id = "reflect_${System.currentTimeMillis()}",
                    content = result.actionDetail,
                    tag = result.dominantFailure,
                    source = "reflect",
                    createdAt = System.currentTimeMillis(),
                ))
                Log.i(TAG, "Lesson auto-saved from deepReflect: ${result.actionDetail}")
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "deepReflect LLM call failed", e)
            ReflectResult(ok = false, error = "LLM call failed: ${e.message}")
        }
    }

    // ── 解析 ──────────────────────────────────────────

    private fun parseReflectReply(reply: String): ReflectResult {
        return try {
            val jsonStr = extractJson(reply)
            val json = JSONObject(jsonStr)
            ReflectResult(
                ok = true,
                overallScore = json.optInt("overall_score", 50),
                trend = json.optString("trend", "stable"),
                dominantFailure = json.optString("dominant_failure_mode", null),
                action = json.optString("action", "no_action"),
                actionDetail = json.optString("action_detail", ""),
                rationale = json.optString("rationale", ""),
            )
        } catch (e: Exception) {
            ReflectResult(ok = false, error = "Parse failed: ${e.message}")
        }
    }

    private fun extractJson(text: String): String {
        // 尝试提取 ```json ... ``` 块
        val fenced = Regex("```json\\s*\\n([\\s\\S]*?)\\n```").find(text)
        if (fenced != null) return fenced.groupValues[1].trim()

        // 尝试提取 { ... } 块
        val braceStart = text.indexOf('{')
        val braceEnd = text.lastIndexOf('}')
        if (braceStart >= 0 && braceEnd > braceStart) {
            return text.substring(braceStart, braceEnd + 1)
        }

        return text.trim()
    }
}
