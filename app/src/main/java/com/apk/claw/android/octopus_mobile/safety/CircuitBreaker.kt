package com.apk.claw.android.octopus_mobile.safety

import android.util.Log
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * 断路器 —— 从母体 runtime/safety/ink/breaker.py 移植.
 *
 * 三态机：
 *  - **CLOSED**（正常）：请求正常通过
 *  - **OPEN**（熔断）：所有请求被拒绝，等待冷却
 *  - **HALF_OPEN**（探测）：放一个请求试，成功→CLOSED，失败→OPEN
 *
 * 触发条件（可配置）：
 *  - 窗口内调用次数超限
 *  - 窗口内错误次数超限
 *  - 窗口内费用超限
 *
 * 用法：
 * ```kotlin
 * val breaker = CircuitBreaker(maxErrorsPerWindow = 5, cooldownSeconds = 30.0)
 *
 * // 每次调用前检查
 * try {
 *     breaker.check()  // 可能抛 CircuitOpenException
 *     val result = doSomething()
 *     breaker.record(success = true)
 * } catch (e: CircuitOpenException) {
 *     // 熔断中，走降级逻辑
 * } catch (e: Exception) {
 *     breaker.record(success = false)
 * }
 * ```
 */
class CircuitBreaker(
    val windowSeconds: Double = 60.0,
    val maxCallsPerWindow: Int? = null,
    val maxCostPerWindow: Double? = null,
    val maxErrorsPerWindow: Int? = null,
    val cooldownSeconds: Double = 30.0,
) {
    companion object {
        private const val TAG = "CircuitBreaker"
    }

    // ── 状态 ──────────────────────────────────────────

    enum class CircuitState { CLOSED, OPEN, HALF_OPEN }

    class CircuitOpenException(
        val reason: String,
        val openedAtMs: Long,
        val cooldownSeconds: Double,
    ) : RuntimeException("Circuit open: $reason (cooldown ${cooldownSeconds}s)")

    private data class Event(
        val tsMs: Long,
        val success: Boolean,
        val costUsd: Double,
    )

    private val events = ConcurrentLinkedDeque<Event>()

    @Volatile
    private var state: CircuitState = CircuitState.CLOSED

    @Volatile
    private var openedAtMs: Long = 0L

    @Volatile
    private var openReason: String = ""

    // ── 查询 ──────────────────────────────────────────

    fun getState(): CircuitState = state

    fun snapshot(): Map<String, Any?> {
        prune()
        val totalCost = events.sumOf { it.costUsd }
        val errors = events.count { !it.success }
        return mapOf(
            "state" to state.name,
            "calls_in_window" to events.size,
            "cost_in_window_usd" to (totalCost * 1_000_000).toInt() / 1_000_000.0,
            "errors" to errors,
            "opened_at" to if (state != CircuitState.CLOSED) openedAtMs else null,
            "reason" to if (state != CircuitState.CLOSED) openReason else null,
        )
    }

    // ── 检查 ──────────────────────────────────────────

    /**
     * 检查是否允许请求通过.
     *
     * @throws CircuitOpenException 如果断路器处于 OPEN 或 HALF_OPEN 状态
     * @return 当前状态（CLOSED 或 HALF_OPEN）
     */
    fun check(): CircuitState {
        val now = System.currentTimeMillis()
        prune()

        when (state) {
            CircuitState.OPEN -> {
                val elapsed = (now - openedAtMs) / 1000.0
                if (elapsed >= cooldownSeconds) {
                    state = CircuitState.HALF_OPEN
                    Log.i(TAG, "Circuit → HALF_OPEN (cooldown elapsed)")
                    return CircuitState.HALF_OPEN
                }
                throw CircuitOpenException(
                    reason = openReason,
                    openedAtMs = openedAtMs,
                    cooldownSeconds = cooldownSeconds,
                )
            }
            CircuitState.HALF_OPEN -> {
                throw CircuitOpenException(
                    reason = "half_open probe in flight",
                    openedAtMs = openedAtMs,
                    cooldownSeconds = cooldownSeconds,
                )
            }
            CircuitState.CLOSED -> {
                return CircuitState.CLOSED
            }
        }
    }

    // ── 记录 ──────────────────────────────────────────

    /**
     * 记录一次调用结果.
     */
    fun record(success: Boolean, costUsd: Double = 0.0) {
        val now = System.currentTimeMillis()
        events.add(Event(tsMs = now, success = success, costUsd = costUsd))
        prune()

        when (state) {
            CircuitState.HALF_OPEN -> {
                if (success) {
                    state = CircuitState.CLOSED
                    openedAtMs = 0L
                    openReason = ""
                    Log.i(TAG, "Circuit → CLOSED (probe succeeded)")
                } else {
                    trip("half_open probe failed", now)
                }
            }
            CircuitState.CLOSED -> {
                val tripReason = evaluateThresholds()
                if (tripReason != null) {
                    trip(tripReason, now)
                }
            }
            CircuitState.OPEN -> {
                // OPEN 状态下不应该有新事件，忽略
            }
        }
    }

    /**
     * 重置断路器.
     */
    fun reset() {
        events.clear()
        state = CircuitState.CLOSED
        openedAtMs = 0L
        openReason = ""
        Log.i(TAG, "Circuit reset → CLOSED")
    }

    // ── 内部 ──────────────────────────────────────────

    private fun prune() {
        val thresholdMs = System.currentTimeMillis() - (windowSeconds * 1000).toLong()
        while (events.isNotEmpty() && events.first.tsMs < thresholdMs) {
            events.removeFirst()
        }
    }

    private fun evaluateThresholds(): String? {
        if (maxCallsPerWindow != null && events.size > maxCallsPerWindow) {
            return "max_calls (${events.size} > $maxCallsPerWindow/${windowSeconds}s)"
        }
        if (maxCostPerWindow != null) {
            val total = events.sumOf { it.costUsd }
            if (total > maxCostPerWindow) {
                return "max_cost (\$${"%.4f".format(total)} > \$${"%.4f".format(maxCostPerWindow)}/${windowSeconds}s)"
            }
        }
        if (maxErrorsPerWindow != null) {
            val errors = events.count { !it.success }
            if (errors > maxErrorsPerWindow) {
                return "max_errors ($errors > $maxErrorsPerWindow/${windowSeconds}s)"
            }
        }
        return null
    }

    private fun trip(reason: String, nowMs: Long) {
        state = CircuitState.OPEN
        openedAtMs = nowMs
        openReason = reason
        Log.w(TAG, "Circuit → OPEN: $reason")
    }
}
