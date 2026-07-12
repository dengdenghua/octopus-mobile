package com.apk.claw.android.agent

import com.apk.claw.android.utils.KVUtils
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Agent 主循环度量(Agent Metrics)—— 量化 Agent Loop 各环节的运行状况.
 *
 * 与 [com.apk.claw.android.octopus_mobile.EvolutionMetrics] 互补:
 * - EvolutionMetrics 度量自进化层(ReflexArc/ImmuneSystem/Ledger)
 * - AgentMetrics 度量主循环(LLM/工具/死循环/目标校验/流式降级)
 *
 * 轻量计数器(内存 AtomicLong,检查点持久化到 KVUtils),[report] 汇总成可读的命中率/失败率等.
 * 没有这些数据就无从判断优化效果,也无法发现运行时退化.
 */
object AgentMetrics {

    // ── 计数器键 ──
    const val ITERATIONS = "iterations"
    const val LLM_CALLS = "llm_calls"
    const val LLM_FAILURES = "llm_failures"
    const val TOOL_CALLS = "tool_calls"
    const val TOOL_FAILURES = "tool_failures"
    const val LOOP_WARNINGS = "loop_warnings"
    const val GOAL_VERIFIES = "goal_verifies"
    const val GOAL_REPAIRS = "goal_repairs"
    const val STREAMING_DEGRADED = "streaming_degraded"
    const val SCREEN_UNCHANGED = "screen_unchanged"
    const val VLM_CACHE_HITS = "vlm_cache_hits"

    private const val KEY = "AGENT_METRICS"
    private val counters = ConcurrentHashMap<String, AtomicLong>()

    private fun c(key: String): AtomicLong = counters.getOrPut(key) { AtomicLong(0) }

    fun inc(key: String, delta: Long = 1) { c(key).addAndGet(delta) }

    fun get(key: String): Long = counters[key]?.get() ?: 0L

    /** 清空全部计数(调试页"重置统计"/单测隔离用). */
    fun reset() { counters.clear() }

    // ── 便捷方法 ──
    fun iteration() { inc(ITERATIONS) }
    fun llmCall() { inc(LLM_CALLS) }
    fun llmFailure() { inc(LLM_FAILURES) }
    fun toolCall() { inc(TOOL_CALLS) }
    fun toolFailure() { inc(TOOL_FAILURES) }
    fun loopWarning() { inc(LOOP_WARNINGS) }
    fun goalVerify() { inc(GOAL_VERIFIES) }
    fun goalRepair() { inc(GOAL_REPAIRS) }
    fun streamingDegraded() { inc(STREAMING_DEGRADED) }
    fun screenUnchanged() { inc(SCREEN_UNCHANGED) }
    fun vlmCacheHit() { inc(VLM_CACHE_HITS) }

    /** LLM 调用失败率 = 失败 / 调用次数. */
    fun llmFailureRate(): Double {
        val calls = get(LLM_CALLS)
        return if (calls > 0) get(LLM_FAILURES).toDouble() / calls else 0.0
    }

    /** 工具调用失败率 = 失败 / 调用次数. */
    fun toolFailureRate(): Double {
        val calls = get(TOOL_CALLS)
        return if (calls > 0) get(TOOL_FAILURES).toDouble() / calls else 0.0
    }

    /** 目标校验修复率 = 修复次数 / 校验次数. */
    fun goalRepairRate(): Double {
        val verifies = get(GOAL_VERIFIES)
        return if (verifies > 0) get(GOAL_REPAIRS).toDouble() / verifies else 0.0
    }

    /** 汇总为一行可读报告(打日志/调试页展示). */
    fun report(): String {
        return buildString {
            append("迭代 ").append(get(ITERATIONS)).append("次; ")
            append("LLM ").append(get(LLM_CALLS)).append("次(失败 ").append(pct(llmFailureRate())).append("); ")
            append("工具 ").append(get(TOOL_CALLS)).append("次(失败 ").append(pct(toolFailureRate())).append("); ")
            append("死循环告警 ").append(get(LOOP_WARNINGS)).append("次; ")
            append("目标校验 ").append(get(GOAL_VERIFIES)).append("次(修复 ").append(get(GOAL_REPAIRS)).append(", ")
            append(pct(goalRepairRate())).append("); ")
            append("流式降级 ").append(get(STREAMING_DEGRADED)).append("次; ")
            append("截图去重 ").append(get(SCREEN_UNCHANGED)).append("次; ")
            append("VLM缓存命中 ").append(get(VLM_CACHE_HITS)).append("次")
        }
    }

    private fun pct(v: Double): String = String.format(Locale.US, "%.1f%%", v * 100)

    /** 启动时从 KVUtils 载入累计计数. */
    fun load() {
        val raw = KVUtils.getString(KEY, "")
        if (raw.isBlank()) return
        raw.split(";").forEach { pair ->
            val k = pair.substringBefore("=")
            val v = pair.substringAfter("=", "").toLongOrNull()
            if (k.isNotBlank() && v != null) c(k).set(v)
        }
    }

    /** 检查点(任务完成等)持久化到 KVUtils. */
    fun persist() {
        KVUtils.putString(KEY, counters.entries.joinToString(";") { "${it.key}=${it.value.get()}" })
    }
}
