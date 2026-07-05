package com.apk.claw.android.octopus_mobile

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.sqrt

/**
 * 回合评分器（Turn Scorer）——从 octopus-os turn_scoring.py + SOUL impact analysis 移植。
 *
 * 每次 generate_app 完成后评分（0.0 / 0.5 / 1.0），记录：
 * - 是否成功生成
 * - JS/静态错误数
 * - 修复轮次
 * - 视觉验收是否通过
 * - 耗时
 *
 * 持续维护：
 * - 最近 N 轮的滑动平均（衡量当前质量基线）
 * - SOUL hash（当ExperienceLedger或prompt变更时更新），自动检测质量回归
 * - 统计数据写入文件，重启后保留
 */
object TurnScorer {

    private const val TAG = "TurnScorer"
    private const val FILE_NAME = "turn_scores.json"
    private const val MAX_HISTORY = 100
    private const val WINDOW_SIZE = 20
    private const val REGRESSION_THRESHOLD = -0.15

    data class TurnScore(
        val ts: Long,
        val score: Double,           // 0.0=失败 / 0.5=有错误 / 1.0=完美
        val reason: String,          // "success" / "with_repairs" / "failed_xxx"
        val repairRounds: Int,
        val visualPassed: Boolean,
        val errorCount: Int,
        val durationMs: Long,
        val soulHash: String,
    )

    data class ScoreSummary(
        val totalRounds: Int,
        val avgScore: Double,
        val recentAvg: Double,       // 最近WINDOW_SIZE轮平均分
        val successRate: Double,     // 1.0分占比
        val avgRepairRounds: Double,
        val verdict: String,         // "healthy" / "degraded" / "regressed"
        val delta: Double,           // 最近窗口vs前窗口的差值
    )

    private val history = mutableListOf<TurnScore>()
    private var currentSoulHash: String = "v1"
    private var loaded = false

    fun init(filesDir: File) {
        if (loaded) return
        synchronized(history) {
            if (loaded) return
            val file = File(filesDir, FILE_NAME)
            if (file.exists()) {
                runCatching {
                    val root = JSONObject(file.readText())
                    currentSoulHash = root.optString("soulHash", "v1")
                    val arr = root.optJSONArray("scores") ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        history.add(TurnScore(
                            ts = obj.getLong("ts"),
                            score = obj.getDouble("score"),
                            reason = obj.optString("reason", ""),
                            repairRounds = obj.optInt("repairRounds", 0),
                            visualPassed = obj.optBoolean("visualPassed", true),
                            errorCount = obj.optInt("errorCount", 0),
                            durationMs = obj.getLong("durationMs"),
                            soulHash = obj.optString("soulHash", currentSoulHash),
                        ))
                    }
                    Log.i(TAG, "Loaded ${history.size} turn scores, avg=${String.format("%.2f", average())}")
                }.onFailure { Log.w(TAG, "Failed to load scores: ${it.message}") }
            }
            loaded = true
        }
    }

    /** 更新SOUL hash——prompt/经验账本变更后调用，用于回归检测。 */
    fun updateSoulHash(newHash: String) {
        synchronized(history) {
            if (newHash != currentSoulHash) {
                Log.i(TAG, "SOUL hash changed: $currentSoulHash -> $newHash")
                currentSoulHash = newHash
                save()
            }
        }
    }

    /** 记录一次成功（零错误、视觉通过）。 */
    fun recordSuccess(durationMs: Long, repairRounds: Int = 0, visualPassed: Boolean = true) {
        val score = if (repairRounds == 0 && visualPassed) 1.0 else 0.7
        add(TurnScore(
            ts = System.currentTimeMillis(),
            score = score,
            reason = if (score == 1.0) "perfect" else "success_with_repairs",
            repairRounds = repairRounds,
            visualPassed = visualPassed,
            errorCount = 0,
            durationMs = durationMs,
            soulHash = currentSoulHash,
        ))
    }

    /** 记录一次有残留错误的完成。 */
    fun recordCompleted(durationMs: Long, repairRounds: Int, remainingErrors: Int, visualPassed: Boolean) {
        val score = when {
            remainingErrors == 0 && visualPassed -> 1.0
            remainingErrors <= 1 -> 0.5
            else -> 0.3
        }
        add(TurnScore(
            ts = System.currentTimeMillis(),
            score = score,
            reason = "completed_with_errors",
            repairRounds = repairRounds,
            visualPassed = visualPassed,
            errorCount = remainingErrors,
            durationMs = durationMs,
            soulHash = currentSoulHash,
        ))
    }

    /** 记录一次失败。 */
    fun recordFailure(durationMs: Long, reason: String, errorCount: Int = 0) {
        add(TurnScore(
            ts = System.currentTimeMillis(),
            score = 0.0,
            reason = reason.take(80),
            repairRounds = 0,
            visualPassed = false,
            errorCount = errorCount,
            durationMs = durationMs,
            soulHash = currentSoulHash,
        ))
    }

    fun summary(): ScoreSummary {
        synchronized(history) {
            if (history.isEmpty()) {
                return ScoreSummary(0, 0.0, 0.0, 0.0, 0.0, "no_data", 0.0)
            }
            val recent = history.takeLast(WINDOW_SIZE)
            val prev = history.dropLast(WINDOW_SIZE).takeLast(WINDOW_SIZE)
            val avg = history.map { it.score }.average()
            val recentAvg = recent.map { it.score }.average()
            val prevAvg = if (prev.isNotEmpty()) prev.map { it.score }.average() else recentAvg
            val delta = recentAvg - prevAvg
            val successRate = history.count { it.score >= 1.0 }.toDouble() / history.size
            val avgRepairs = history.map { it.repairRounds }.average()

            val verdict = when {
                history.size < WINDOW_SIZE -> "warming_up"
                delta < REGRESSION_THRESHOLD -> "regressed"
                recentAvg < 0.5 -> "degraded"
                recentAvg > 0.8 -> "healthy"
                else -> "stable"
            }
            return ScoreSummary(history.size, avg, recentAvg, successRate, avgRepairs, verdict, delta)
        }
    }

    fun average(): Double = synchronized(history) {
        if (history.isEmpty()) 0.0 else history.map { it.score }.average()
    }

    private fun add(score: TurnScore) {
        if (!loaded) return
        synchronized(history) {
            history.add(score)
            while (history.size > MAX_HISTORY) history.removeAt(0)
            save()
            val s = summary()
            Log.i(TAG, "Turn scored=${String.format("%.1f", score.score)} reason=${score.reason} " +
                    "recent_avg=${String.format("%.2f", s.recentAvg)} verdict=${s.verdict}")
        }
    }

    private fun save() {
        try {
            val ctx = com.apk.claw.android.ClawApplication.instance
            val file = File(ctx.filesDir, FILE_NAME)
            val root = JSONObject()
            root.put("soulHash", currentSoulHash)
            root.put("version", 1)
            val arr = JSONArray()
            history.takeLast(MAX_HISTORY).forEach { s ->
                arr.put(JSONObject().apply {
                    put("ts", s.ts)
                    put("score", s.score)
                    put("reason", s.reason)
                    put("repairRounds", s.repairRounds)
                    put("visualPassed", s.visualPassed)
                    put("errorCount", s.errorCount)
                    put("durationMs", s.durationMs)
                    put("soulHash", s.soulHash)
                })
            }
            root.put("scores", arr)
            file.writeText(root.toString(2))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save scores: ${e.message}")
        }
    }
}
