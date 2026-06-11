package com.apk.claw.android.octopus_mobile.evolution

import android.util.Log
import org.json.JSONObject
import kotlin.math.max
import java.io.File
import java.util.Date
import java.util.Random

/**
 * 金丝雀发布管理器 —— 从母体 runtime/safety/evolution/canary.py 移植.
 *
 * 新技能/新扩展不直接全量上线，而是渐进式灰度：
 *
 *   shadow(0%) → canary_5(5%) → canary_25(25%) → canary_50(50%) → full(100%)
 *
 * 每个阶段：
 *  - 采样足够多的调用
 *  - 成功率 ≥ 阈值 → 晋级下一阶段
 *  - 成功率 < 50% → 自动回滚
 *
 * 手机版简化：
 *  - 状态持久化到 SharedPreferences 或 JSON 文件
 *  - 不需要分布式协调（单设备）
 *
 * 用法：
 * ```kotlin
 * val canary = CanaryManager(dataDir)
 * canary.register("browser_install_extension")  // 新扩展能力，走灰度
 * if (canary.shouldRoute("browser_install_extension")) {
 *     // 允许使用
 * } else {
 *     // 灰度未命中，走旧路径
 * }
 * canary.recordOutcome("browser_install_extension", success = true)
 * ```
 */
class CanaryManager(
    private val dataDir: File,
    private val config: CanaryConfig = CanaryConfig(),
) {
    companion object {
        private const val TAG = "CanaryManager"
        private const val STATES_DIR = "canary_states"
    }

    // ── 数据类 ────────────────────────────────────────

    enum class Phase {
        SHADOW,       // 0% 流量，只记录不生效
        CANARY_5,     // 5% 流量
        CANARY_25,    // 25% 流量
        CANARY_50,    // 50% 流量
        FULL,         // 100% 流量
        ROLLED_BACK;  // 已回滚，不再路由

        fun trafficPercent(): Double = when (this) {
            SHADOW -> 0.0
            CANARY_5 -> 0.05
            CANARY_25 -> 0.25
            CANARY_50 -> 0.50
            FULL -> 1.0
            ROLLED_BACK -> 0.0
        }

        fun minSamples(): Int = when (this) {
            SHADOW -> 10
            CANARY_5 -> 20
            CANARY_25 -> 40
            CANARY_50 -> 60
            FULL -> 0
            ROLLED_BACK -> 0
        }

        fun next(): Phase? = when (this) {
            SHADOW -> CANARY_5
            CANARY_5 -> CANARY_25
            CANARY_25 -> CANARY_50
            CANARY_50 -> FULL
            FULL -> null
            ROLLED_BACK -> null
        }
    }

    data class CanaryConfig(
        val shadowPassRate: Double = 0.70,
        val promotionThresholds: Map<String, Double> = mapOf(
            "SHADOW" to 0.70,
            "CANARY_5" to 0.80,
            "CANARY_25" to 0.80,
            "CANARY_50" to 0.85,
        ),
        val rollbackThreshold: Double = 0.50,
    )

    data class CanaryState(
        val skillName: String,
        var phase: Phase,
        var enteredTs: String,
        var sampleCount: Int = 0,
        var successCount: Int = 0,
        var failureCount: Int = 0,
        var currentRate: Double = 0.0,
    )

    // ── 状态管理 ──────────────────────────────────────

    private val states = mutableMapOf<String, CanaryState>()
    private val random = Random()
    private val statesDir = File(dataDir, STATES_DIR)

    init {
        statesDir.mkdirs()
        loadStates()
    }

    /**
     * 注册一个技能到金丝雀管理.
     */
    fun register(skillName: String): CanaryState {
        if (skillName in states) return states[skillName]!!
        val state = CanaryState(
            skillName = skillName,
            phase = Phase.SHADOW,
            enteredTs = formatDate(Date()),
        )
        states[skillName] = state
        persistState(state)
        return state
    }

    /**
     * 是否应该路由到这个技能（灰度命中）.
     */
    fun shouldRoute(skillName: String): Boolean {
        val state = states[skillName] ?: return true  // 未注册的技能默认放行
        return when (state.phase) {
            Phase.ROLLED_BACK -> false
            Phase.FULL -> true
            else -> random.nextDouble() < state.phase.trafficPercent()
        }
    }

    /**
     * 记录一次调用结果.
     */
    fun recordOutcome(skillName: String, success: Boolean): CanaryState? {
        val state = states[skillName] ?: return null
        if (state.phase == Phase.ROLLED_BACK) return state

        state.sampleCount++
        if (success) state.successCount++ else state.failureCount++
        state.currentRate = state.successCount.toDouble() / max(1, state.sampleCount)

        // 自动回滚
        if (state.currentRate < config.rollbackThreshold && state.sampleCount >= 5) {
            Log.w(TAG, "CANARY ROLLBACK: $skillName rate=${state.currentRate} < ${config.rollbackThreshold}")
            state.phase = Phase.ROLLED_BACK
            state.enteredTs = formatDate(Date())
            persistState(state)
            return state
        }

        // 晋级检查
        val thresholdKey = state.phase.name
        val threshold = config.promotionThresholds[thresholdKey] ?: 0.80
        if (state.currentRate >= threshold && state.sampleCount >= state.phase.minSamples()) {
            promote(state)
        }

        persistState(state)
        return state
    }

    /**
     * 强制回滚.
     */
    fun forceRollback(skillName: String): CanaryState? {
        val state = states[skillName] ?: return null
        state.phase = Phase.ROLLED_BACK
        state.enteredTs = formatDate(Date())
        persistState(state)
        return state
    }

    fun getState(skillName: String): CanaryState? = states[skillName]

    fun listActive(): List<CanaryState> = states.values.filter {
        it.phase != Phase.FULL && it.phase != Phase.ROLLED_BACK
    }

    fun listAll(): List<CanaryState> = states.values.toList()

    // ── 内部 ──────────────────────────────────────────

    private fun promote(state: CanaryState) {
        val next = state.phase.next() ?: return
        val oldPhase = state.phase
        state.phase = next
        state.enteredTs = formatDate(Date())
        state.sampleCount = 0
        state.successCount = 0
        state.failureCount = 0
        state.currentRate = 0.0
        Log.i(TAG, "CANARY PROMOTE: ${state.skillName} ${oldPhase.name} → ${next.name}")
    }

    private fun persistState(state: CanaryState) {
        try {
            val file = File(statesDir, "${state.skillName}.json")
            val json = JSONObject().apply {
                put("skill_name", state.skillName)
                put("phase", state.phase.name)
                put("entered_ts", state.enteredTs)
                put("sample_count", state.sampleCount)
                put("success_count", state.successCount)
                put("failure_count", state.failureCount)
                put("current_rate", state.currentRate)
            }
            file.writeText(json.toString(2))
        } catch (e: Exception) {
            Log.w(TAG, "Persist canary state failed", e)
        }
    }

    private fun loadStates() {
        if (!statesDir.exists()) return
        statesDir.listFiles { f -> f.extension == "json" }?.forEach { file ->
            try {
                val json = JSONObject(file.readText())
                val name = json.optString("skill_name", file.nameWithoutExtension)
                val state = CanaryState(
                    skillName = name,
                    phase = Phase.valueOf(json.optString("phase", "SHADOW")),
                    enteredTs = json.optString("entered_ts", ""),
                    sampleCount = json.optInt("sample_count", 0),
                    successCount = json.optInt("success_count", 0),
                    failureCount = json.optInt("failure_count", 0),
                    currentRate = json.optDouble("current_rate", 0.0),
                )
                states[name] = state
            } catch (e: Exception) {
                Log.d(TAG, "Canary state parse failed: ${file.name}", e)
            }
        }
    }

    private fun formatDate(date: Date): String {
        return java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).format(date)
    }
}
