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
 *  B3 · deepEvolve（贵，~10-30 分/次，手动触发）
 *      MiniMax 式自主循环：
 *        1. 分析最近失败轨迹
 *        2. LLM 提议 K 个候选改动
 *        3. LLM 当裁判打分
 *        4. 选最优（dry_run=True 只预览，False 才应用）
 *
 * 用法：
 * ```kotlin
 * val engine = EvolutionEngine(scorer, llmCall = { prompt -> ... })
 *
 * // B2：便宜反思
 * val reflection = engine.deepReflect()
 *
 * // B3：贵但彻底
 * val evolution = engine.deepEvolve(dryRun = true)
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

        private const val PROPOSE_SYSTEM = """You are a self-improvement proposer for an AI agent.
You read recent turns and propose K candidate changes. Output ONLY a JSON envelope.

Schema:
```json
{
  "candidates": [
    {
      "id": "c1",
      "kind": "add_lesson" | "revert",
      "lesson": "<imperative one-liner>" | null,
      "tag": "<short category>" | null,
      "predicted_impact": "<one sentence>",
      "risk": "low" | "medium" | "high"
    }
  ]
}
```
Prefer LOW risk. Favor concrete tool/workflow lessons over vague personality changes."""

        private const val JUDGE_SYSTEM = """You are an impact judge. You read recent agent turns and a candidate self-improvement, then predict the impact. Output ONLY a JSON envelope.

Schema:
```json
{
  "candidate_id": "<id>",
  "predicted_avg_score_delta": -1.0 .. +1.0,
  "would_help_count": 0,
  "would_hurt_count": 0,
  "confidence": "low" | "medium" | "high",
  "verdict": "apply" | "skip" | "needs_more_data"
}
```
Be conservative. "needs_more_data" is fine when uncertain."""
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

    data class EvolveResult(
        val ok: Boolean,
        val roundsRun: Int = 0,
        val audit: List<EvolveRound> = emptyList(),
        val applied: List<AppliedAction> = emptyList(),
        val dryRun: Boolean = true,
        val error: String? = null,
    )

    data class EvolveRound(
        val round: Int,
        val candidates: List<Candidate> = emptyList(),
        val winner: Candidate? = null,
        val applied: AppliedAction? = null,
    )

    data class Candidate(
        val id: String,
        val kind: String,       // "add_lesson" | "revert"
        val lesson: String? = null,
        val tag: String? = null,
        val predictedImpact: String = "",
        val risk: String = "low",
        val verdict: String = "skip",
        val confidence: String = "low",
    )

    data class AppliedAction(
        val kind: String,
        val detail: String,
        val success: Boolean,
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

    // ── B3 · deepEvolve ───────────────────────────────

    /**
     * 自进化循环：提议 → 判断 → 应用.
     *
     * @param dryRun true=只预览不应用，false=实际修改
     * @param maxRounds 最多跑几轮
     * @param candidatesPerRound 每轮提议几个候选
     */
    fun deepEvolve(
        dryRun: Boolean = true,
        maxRounds: Int = 1,
        candidatesPerRound: Int = 3,
    ): EvolveResult {
        if (llmCall == null) {
            return EvolveResult(ok = false, error = "LLM not configured for deep_evolve")
        }

        val audit = mutableListOf<EvolveRound>()
        val applied = mutableListOf<AppliedAction>()

        for (round in 1..maxRounds) {
            val fitness = scorer.computeFitness()
            val scores = scorer.readRecentScores(20)
            if (scores.isEmpty()) break

            val scoreRows = scores.joinToString("\n") { s ->
                "  - ${s.ts} · score=${s.score} · tool=${s.toolName} · reason=${s.reason}"
            }

            // 1. 提议
            val proposeUser = """
AGENT: octopus-mobile
ROUND: $round/$maxRounds
K = $candidatesPerRound

### Recent scores
$scoreRows

### Heuristic verdict
score=${fitness.score} trend=${fitness.trend} verdict=${fitness.verdict}

Propose $candidatesPerRound candidate changes.
""".trimIndent()

            val candidates = try {
                val proposeReply = llmCall.invoke("$PROPOSE_SYSTEM\n\n$proposeUser") ?: ""
                parseCandidates(proposeReply)
            } catch (e: Exception) {
                Log.e(TAG, "Propose failed round $round", e)
                emptyList()
            }

            if (candidates.isEmpty()) {
                audit.add(EvolveRound(round = round))
                break
            }

            // 2. 判断每个候选
            val judgedCandidates = candidates.map { cand ->
                val judgeUser = """
AGENT: octopus-mobile

### Recent turns
$scoreRows

### Candidate
id=${cand.id} kind=${cand.kind} lesson=${cand.lesson} risk=${cand.risk}

Predict impact + verdict.
""".trimIndent()

                try {
                    val judgeReply = llmCall.invoke("$JUDGE_SYSTEM\n\n$judgeUser") ?: ""
                    val verdict = parseJudgeVerdict(judgeReply)
                    cand.copy(verdict = verdict.first, confidence = verdict.second)
                } catch (e: Exception) {
                    cand.copy(verdict = "skip")
                }
            }

            // 3. 选最优
            val winner = judgedCandidates
                .filter { it.verdict == "apply" }
                .maxWithOrNull(compareBy { c ->
                    mapOf("high" to 3, "medium" to 2, "low" to 1)[c.confidence] ?: 0
                })

            val appliedAction = if (winner != null && !dryRun) {
                val action = AppliedAction(
                    kind = winner.kind,
                    detail = winner.lesson ?: winner.predictedImpact,
                    success = true,
                )
                applied.add(action)
                // 自动写入教训库
                if (winner.kind == "add_lesson" && !winner.lesson.isNullOrEmpty()) {
                    lessonStore?.addLesson(LessonStore.Lesson(
                        id = "evolve_${System.currentTimeMillis()}",
                        content = winner.lesson,
                        tag = winner.tag,
                        source = "evolve",
                        createdAt = System.currentTimeMillis(),
                    ))
                    Log.i(TAG, "Lesson auto-saved from deepEvolve: ${winner.lesson}")
                }
                Log.i(TAG, "EVOLVE APPLIED: ${winner.kind} - ${winner.lesson}")
                action
            } else null

            audit.add(EvolveRound(
                round = round,
                candidates = judgedCandidates,
                winner = winner,
                applied = appliedAction,
            ))
        }

        return EvolveResult(
            ok = true,
            roundsRun = audit.size,
            audit = audit,
            applied = applied,
            dryRun = dryRun,
        )
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

    private fun parseCandidates(reply: String): List<Candidate> {
        return try {
            val jsonStr = extractJson(reply)
            val json = JSONObject(jsonStr)
            val arr = json.optJSONArray("candidates") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                Candidate(
                    id = obj.optString("id", "c${i + 1}"),
                    kind = obj.optString("kind", "add_lesson"),
                    lesson = obj.optString("lesson", null),
                    tag = obj.optString("tag", null),
                    predictedImpact = obj.optString("predicted_impact", ""),
                    risk = obj.optString("risk", "low"),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Parse candidates failed", e)
            emptyList()
        }
    }

    private fun parseJudgeVerdict(reply: String): Pair<String, String> {
        return try {
            val jsonStr = extractJson(reply)
            val json = JSONObject(jsonStr)
            json.optString("verdict", "skip") to json.optString("confidence", "low")
        } catch (e: Exception) {
            "skip" to "low"
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
