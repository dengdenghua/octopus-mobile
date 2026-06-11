package com.apk.claw.android.octopus_mobile

import kotlin.random.Random

// ──────────────────────────────────────────────
// 连接状态机
// DISCONNECTED → CONNECTING → CONNECTED → HELLO_SENT → ONLINE
// ONLINE → RECONNECTING → CONNECTING → ...
// 任何状态 → OFFLINE（用户主动断开）
// ──────────────────────────────────────────────

/** 触发状态转换的事件 */
sealed class ConnectionEvent {
    /** 开始连接 */
    object Connect : ConnectionEvent()
    /** WebSocket 连接成功 */
    object Connected : ConnectionEvent()
    /** 发送 hello 握手 */
    object HelloSent : ConnectionEvent()
    /** 握手响应成功 */
    object HelloAcked : ConnectionEvent()
    /** 连接断开 */
    object Disconnected : ConnectionEvent()
    /** 用户主动断开 */
    object GoOffline : ConnectionEvent()
    /** 重连请求 */
    object Reconnect : ConnectionEvent()
}

/** 退避策略配置（参考 AWS Architecture Blog: Exponential Backoff and Jitter） */
data class BackoffConfig(
    /** 第 1 次重连的基准延迟（毫秒） */
    val baseDelayMs: Long = 1_000L,
    /** 单次最大延迟（毫秒） */
    val maxDelayMs: Long = 30_000L,
    /** 重连多少次后放弃（null = 无限重试） */
    val maxAttempts: Int? = 10,
    /** 退避上限指数（防溢出：1L shl 30 已经达到约 1.07e9ms ≈ 12 天） */
    val capExponent: Int = 5,
) {
    companion object {
        /** 默认 1s → 30s 封顶，10 次后放弃 */
        val DEFAULT = BackoffConfig()
    }
}

/** 连接状态机 */
class ConnectionStateMachine(private val backoff: BackoffConfig = BackoffConfig.DEFAULT) {

    @Volatile
    var currentState: ConnectionState = ConnectionState.DISCONNECTED
        private set

    /** 状态变更监听器 */
    var onStateChanged: ((from: ConnectionState, to: ConnectionState) -> Unit)? = null

    /** 重连次数（用于指数退避计算） */
    var reconnectAttempts: Int = 0
        internal set

    /**
     * 尝试状态转换
     * @return 转换后的状态，如果转换不合法则返回 null
     */
    fun transition(event: ConnectionEvent): ConnectionState? {
        val from = currentState
        val to = when (event) {
            ConnectionEvent.Connect -> when (from) {
                ConnectionState.DISCONNECTED -> ConnectionState.CONNECTING
                ConnectionState.RECONNECTING -> ConnectionState.CONNECTING
                else -> return null
            }
            ConnectionEvent.Connected -> when (from) {
                ConnectionState.CONNECTING -> ConnectionState.CONNECTED
                else -> return null
            }
            ConnectionEvent.HelloSent -> when (from) {
                ConnectionState.CONNECTED -> ConnectionState.HELLO_SENT
                else -> return null
            }
            ConnectionEvent.HelloAcked -> when (from) {
                ConnectionState.HELLO_SENT -> {
                    reconnectAttempts = 0
                    ConnectionState.ONLINE
                }
                else -> return null
            }
            ConnectionEvent.Disconnected -> when (from) {
                ConnectionState.CONNECTING -> ConnectionState.DISCONNECTED
                ConnectionState.CONNECTED -> ConnectionState.DISCONNECTED
                ConnectionState.HELLO_SENT -> ConnectionState.DISCONNECTED
                ConnectionState.ONLINE -> ConnectionState.RECONNECTING
                else -> return null
            }
            ConnectionEvent.GoOffline -> ConnectionState.OFFLINE
            ConnectionEvent.Reconnect -> when (from) {
                ConnectionState.RECONNECTING -> {
                    if (!canReconnect()) {
                        // 超过最大重试次数 → 进入 OFFLINE，放弃自动重连
                        ConnectionState.OFFLINE
                    } else {
                        reconnectAttempts++
                        ConnectionState.CONNECTING
                    }
                }
                else -> return null
            }
        }

        currentState = to
        onStateChanged?.invoke(from, to)
        return to
    }

    /** 检查是否可以转换到目标状态 */
    fun canTransition(to: ConnectionState): Boolean {
        return when (to) {
            ConnectionState.CONNECTING -> currentState == ConnectionState.DISCONNECTED
                    || currentState == ConnectionState.RECONNECTING
            ConnectionState.CONNECTED -> currentState == ConnectionState.CONNECTING
            ConnectionState.HELLO_SENT -> currentState == ConnectionState.CONNECTED
            ConnectionState.ONLINE -> currentState == ConnectionState.HELLO_SENT
            ConnectionState.RECONNECTING -> currentState == ConnectionState.ONLINE
            ConnectionState.DISCONNECTED -> currentState == ConnectionState.CONNECTING
                    || currentState == ConnectionState.CONNECTED
                    || currentState == ConnectionState.HELLO_SENT
            ConnectionState.OFFLINE -> true // 任何状态都可以转到 OFFLINE
        }
    }

    /** 是否处于在线状态 */
    fun isOnline(): Boolean = currentState == ConnectionState.ONLINE

    /** 是否处于连接中状态 */
    fun isConnecting(): Boolean = currentState == ConnectionState.CONNECTING
            || currentState == ConnectionState.RECONNECTING

    /**
     * 还能否继续重试。
     * - maxAttempts == null → 永远返回 true（无限重试）
     * - 重连成功后 [reconnectAttempts] 会被 [transition] 重置为 0
     */
    fun canReconnect(): Boolean {
        val cap = backoff.maxAttempts ?: return true
        return reconnectAttempts < cap
    }

    /**
     * 计算下一次重连的延迟（毫秒）。
     *
     * 使用 AWS 推荐的 **Full Jitter** 算法：
     *   delay = random(0, min(cap, base * 2^attempt))
     * 比"等距指数 + 固定抖动"更能避免雪崩重连。
     *
     * 调用顺序：
     *   1. [ConnectionEvent.Disconnected] 触发，进入 RECONNECTING
     *   2. 调度器在协程中 [getBackoffDelayMs] delay 后发送 [ConnectionEvent.Reconnect]
     *   3. [transition] 内会 ++ reconnectAttempts 并切到 CONNECTING
     */
    fun getBackoffDelayMs(): Long {
        // 限位：左移次数 >= capExponent 时退到 max
        val safeAttempt = minOf(reconnectAttempts, backoff.capExponent)
        // 防溢出：baseDelay * 2^safeAttempt
        val ceiling = if (safeAttempt >= 30) {
            backoff.maxDelayMs
        } else {
            minOf(backoff.baseDelayMs shl safeAttempt, backoff.maxDelayMs)
        }
        // Full Jitter: random in [0, ceiling]
        return Random.nextLong(0, ceiling + 1)
    }

    /** 重置状态机 */
    fun reset() {
        val from = currentState
        currentState = ConnectionState.DISCONNECTED
        reconnectAttempts = 0
        if (from != ConnectionState.DISCONNECTED) {
            onStateChanged?.invoke(from, ConnectionState.DISCONNECTED)
        }
    }
}
