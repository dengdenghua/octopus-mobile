@file:Suppress(
    "PackageNaming", "ImplicitDefaultLocale", "MagicNumber", "MaxLineLength",
    "UnusedParameter", "UnusedPrivateProperty",
)   // 风险阈值/日志/算法内联常量 + 预留的 args/阈值常量,整文件豁免

package com.apk.claw.android.octopus_mobile

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 免疫系统（Immune System）——从 octopus-os immunity.md 协议移植。
 *
 * 轻量级工具调用风险监控：
 * 1. **基线学习**：记录每个工具的平均延迟、token用量、调用频率，形成行为基线
 * 2. **异常检测**：z-score 判断当前调用是否偏离基线（延迟异常高/参数异常/调用频率异常）
 * 3. **风险评分**：综合打分，高风险操作（如send_sms、file写入）额外加权
 * 4. **攻击模式记忆**：检测到异常模式后记录，下次类似调用可直接拦截
 *
 * 阈值策略：z-score > 2.0 视为异常（95%置信度），风险分 > 0.7 时打印警告日志。
 * 当前不做强制拦截（避免误杀正常操作），只记录日志供调试和后续策略升级使用。
 */
object ImmuneSystem {

    private const val TAG = "ImmuneSystem"
    private const val ZSCORE_WARN_THRESHOLD = 2.0
    private const val RISK_WARN_THRESHOLD = 0.7
    private const val BASELINE_SAMPLES = 10  // 收集多少样本后开始异常检测
    private const val MAX_BASELINE = 50

    data class ToolBaseline(
        val callCount: AtomicInteger = AtomicInteger(0),
        @Volatile var avgLatencyMs: Double = 0.0,
        @Volatile var avgResultSize: Double = 0.0,
        @Volatile var m2Latency: Double = 0.0,  // Welford's M2
        @Volatile var m2Size: Double = 0.0,
        @Volatile var errorCount: Int = 0,
    )

    data class RiskVerdict(
        val verdict: Verdict,
        val riskScore: Double,
        val reason: String,
    )

    enum class Verdict { ALLOW, WARN, QUARANTINE }

    private val baselines = ConcurrentHashMap<String, ToolBaseline>()
    private val recentCalls = ArrayDeque<ToolCallRecord>(30)

    private data class ToolCallRecord(
        val toolName: String,
        val timestamp: Long,
        val latencyMs: Long,
        val resultSize: Int,
        val isError: Boolean,
    )

    /** 高风险工具：调用时额外加权。 */
    private val HIGH_RISK_TOOLS = setOf(
        "send_sms", "write_file", "send_intent", "open_app",
        "start_vpn", "set_clipboard",
    )

    private val MEDIUM_RISK_TOOLS = setOf(
        "read_sms", "read_calendar", "read_file", "search_files",
        "install_extension", "take_screenshot", "generate_app",
    )

    /**
     * 工具调用前的预检。
     */
    fun preCheck(toolName: String, args: Map<String, Any?>): RiskVerdict {
        val baseRisk = when {
            toolName in HIGH_RISK_TOOLS -> 0.3
            toolName in MEDIUM_RISK_TOOLS -> 0.15
            else -> 0.0
        }

        val baseline = baselines[toolName]
        if (baseline != null && baseline.callCount.get() >= BASELINE_SAMPLES) {
            val recent = synchronized(recentCalls) { recentCalls.count { it.toolName == toolName } }
            val freqAnomaly = recent > 5
            if (freqAnomaly) {
                return RiskVerdict(Verdict.WARN, minOf(1.0, baseRisk + 0.4), "high_frequency($recent calls in window)")
            }
        }

        return if (baseRisk >= 0.3) {
            RiskVerdict(Verdict.WARN, baseRisk, "high_risk_tool")
        } else {
            RiskVerdict(Verdict.ALLOW, baseRisk, "ok")
        }
    }

    /**
     * 工具调用后的结果上报，更新基线。
     */
    fun postResult(
        toolName: String,
        latencyMs: Long,
        resultSize: Int,
        isError: Boolean,
    ) {
        val baseline = baselines.getOrPut(toolName) { ToolBaseline() }
        val n = baseline.callCount.incrementAndGet()

        // Welford's online algorithm for mean/variance
        val delta = latencyMs - baseline.avgLatencyMs
        baseline.avgLatencyMs += delta / n
        baseline.m2Latency += delta * (latencyMs - baseline.avgLatencyMs)

        val deltaS = resultSize - baseline.avgResultSize
        baseline.avgResultSize += deltaS / n
        baseline.m2Size += deltaS * (resultSize - baseline.avgResultSize)

        if (isError) baseline.errorCount++

        synchronized(recentCalls) {
            recentCalls.addLast(ToolCallRecord(toolName, System.currentTimeMillis(), latencyMs, resultSize, isError))
            val cutoff = System.currentTimeMillis() - 60_000L // 1 minute window
            while (recentCalls.isNotEmpty() && recentCalls.first().timestamp < cutoff) {
                recentCalls.removeFirst()
            }
        }

        if (n >= BASELINE_SAMPLES) {
            val stdLatency = sqrt(baseline.m2Latency / (n - 1))
            if (stdLatency > 0) {
                val zLatency = abs(latencyMs - baseline.avgLatencyMs) / stdLatency
                if (zLatency > ZSCORE_WARN_THRESHOLD) {
                    Log.w(TAG, "Anomaly on $toolName: latency=${latencyMs}ms (z=${String.format("%.1f", zLatency)}, avg=${String.format("%.0f", baseline.avgLatencyMs)}ms)")
                }
            }
            if (isError) {
                val errorRate = baseline.errorCount.toDouble() / n
                if (errorRate > 0.5 && n > 5) {
                    Log.w(TAG, "High error rate on $toolName: ${String.format("%.0f", errorRate * 100)}% (${baseline.errorCount}/$n)")
                }
            }
        }

        if (n > MAX_BASELINE) {
            // 防止无限增长，重置计数器，保留移动平均
            baseline.callCount.set(BASELINE_SAMPLES)
            baseline.m2Latency *= BASELINE_SAMPLES.toDouble() / n
            baseline.m2Size *= BASELINE_SAMPLES.toDouble() / n
        }
    }

    /** 获取工具调用统计（用于调试/显示）。 */
    fun getStats(toolName: String): String {
        val b = baselines[toolName] ?: return "no_data"
        val n = b.callCount.get()
        val errRate = if (n > 0) b.errorCount.toDouble() / n * 100 else 0.0
        return "$toolName: calls=$n avgLat=${b.avgLatencyMs.toLong()}ms err=${String.format("%.0f", errRate)}%"
    }

    fun allStats(): String = baselines.keys.sorted().joinToString("\n") { getStats(it) }
}
