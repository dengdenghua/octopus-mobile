@file:Suppress("PackageNaming", "MagicNumber", "MaxLineLength", "TooManyFunctions")   // 计数器天然多小函数;百分比/汇总内联常量

package com.apk.claw.android.octopus_mobile

import com.apk.claw.android.utils.KVUtils
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 自进化层效果度量（Evolution Metrics）—— 量化 ReflexArc / ImmuneSystem / ExperienceLedger 是否真起作用。
 *
 * 轻量计数器(内存 AtomicLong,检查点持久化到 KVUtils),[report] 汇总成可读的命中率/告警率等。
 * 目的:把"逻辑正确"变成"实测有效"的证据 —— 不埋点就无从判断这套自进化层到底有没有价值。
 *
 * 计数(自增)是纯内存操作,可脱离 Android 单测;[load]/[persist] 才碰 KVUtils。
 */
object EvolutionMetrics {

    const val REFLEX_HIT = "reflex_hit"
    const val REFLEX_MISS = "reflex_miss"
    const val IMMUNE_CALL = "immune_call"
    const val IMMUNE_WARN = "immune_warn"
    const val LEDGER_ERROR = "ledger_error"
    const val LEDGER_REPAIR = "ledger_repair"
    const val MITIGATION_INJECTED = "mitigation_injected"
    const val MODEL_FAILOVER = "model_failover"

    private const val KEY = "EVOLUTION_METRICS"
    private val counters = ConcurrentHashMap<String, AtomicLong>()

    private fun c(key: String): AtomicLong = counters.getOrPut(key) { AtomicLong(0) }

    fun reflexHit() { c(REFLEX_HIT).incrementAndGet() }
    fun reflexMiss() { c(REFLEX_MISS).incrementAndGet() }
    fun immuneCall(warned: Boolean) {
        c(IMMUNE_CALL).incrementAndGet()
        if (warned) c(IMMUNE_WARN).incrementAndGet()
    }
    fun ledgerError() { c(LEDGER_ERROR).incrementAndGet() }
    fun ledgerRepair() { c(LEDGER_REPAIR).incrementAndGet() }
    fun mitigationInjected() { c(MITIGATION_INJECTED).incrementAndGet() }
    fun modelFailover() { c(MODEL_FAILOVER).incrementAndGet() }

    fun get(key: String): Long = counters[key]?.get() ?: 0L

    /** 清空全部计数(调试页"重置统计"/单测隔离用)。 */
    fun reset() { counters.clear() }

    /** ReflexArc 命中率 = 命中 /(命中+未命中),即"省下多少次完整 LLM 流程"的占比。 */
    fun reflexHitRate(): Double {
        val hit = get(REFLEX_HIT)
        val total = hit + get(REFLEX_MISS)
        return if (total > 0) hit.toDouble() / total else 0.0
    }

    /** ImmuneSystem 告警率 = 告警 / 预检次数。 */
    fun immuneWarnRate(): Double {
        val calls = get(IMMUNE_CALL)
        return if (calls > 0) get(IMMUNE_WARN).toDouble() / calls else 0.0
    }

    /** 汇总为一行可读报告(打日志/调试页展示)。 */
    fun report(): String {
        val hit = get(REFLEX_HIT)
        val reflexTotal = hit + get(REFLEX_MISS)
        return buildString {
            append("ReflexArc 命中率 ").append(pct(reflexHitRate()))
            append(" ($hit/$reflexTotal, 省 $hit 次 LLM); ")
            append("ImmuneSystem 告警率 ").append(pct(immuneWarnRate()))
            append(" (${get(IMMUNE_WARN)}/${get(IMMUNE_CALL)}); ")
            append("经验账本 记错 ${get(LEDGER_ERROR)} / 修复 ${get(LEDGER_REPAIR)} / 注入 ${get(MITIGATION_INJECTED)} 次; ")
            append("模型故障转移 ${get(MODEL_FAILOVER)} 次")
        }
    }

    private fun pct(v: Double): String = String.format(Locale.US, "%.1f%%", v * 100)

    /** 启动时从 KVUtils 载入累计计数。 */
    fun load() {
        val raw = KVUtils.getString(KEY, "")
        if (raw.isBlank()) return
        raw.split(";").forEach { pair ->
            val k = pair.substringBefore("=")
            val v = pair.substringAfter("=", "").toLongOrNull()
            if (k.isNotBlank() && v != null) c(k).set(v)
        }
    }

    /** 检查点(任务完成等)持久化到 KVUtils。 */
    fun persist() {
        KVUtils.putString(KEY, counters.entries.joinToString(";") { "${it.key}=${it.value.get()}" })
    }
}
