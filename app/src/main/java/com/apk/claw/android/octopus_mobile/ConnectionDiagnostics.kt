package com.apk.claw.android.octopus_mobile

/**
 * 连接诊断 —— 记录母体 WebSocket 的重连历史与失败原因，补上此前"断线时 UI 无任何
 * 诊断信息、用户分不清是网络抖动还是服务端宕机"的缺口。
 *
 * 纯逻辑、无 Android 依赖、时间戳由调用方注入（保证确定性、可单测）。
 * 由 [OctopusMobileClient] 在 onConnected / onDisconnected / 重连调度处喂入事件，
 * 通过 [snapshot] / [summary] 供 UI 或控制台读取。
 *
 * 名词区分：
 *  - [Snapshot.totalReconnectAttempts] 是**本会话累计**重连尝试（只增，用于观测连接是否抖动频繁）；
 *    与状态机里"成功后清零的退避计数"不同。
 *  - [Snapshot.consecutiveFailures] 是**连续**失败数，一旦成功连上即清零。
 */
class ConnectionDiagnostics {

    data class Snapshot(
        val online: Boolean,
        val totalReconnectAttempts: Int,
        val consecutiveFailures: Int,
        val lastFailureReason: String?,
        val lastConnectedAtMs: Long,
        val lastDisconnectedAtMs: Long,
    )

    @Volatile private var online: Boolean = false
    @Volatile private var totalReconnectAttempts: Int = 0
    @Volatile private var consecutiveFailures: Int = 0
    @Volatile private var lastFailureReason: String? = null
    @Volatile private var lastConnectedAtMs: Long = 0
    @Volatile private var lastDisconnectedAtMs: Long = 0

    /** 握手成功、进入 ONLINE 时调用：清零连续失败计数。 */
    @Synchronized
    fun onConnected(nowMs: Long) {
        online = true
        consecutiveFailures = 0
        lastConnectedAtMs = nowMs
    }

    /** 断线（被动关闭 / 失败）时调用。reason 为空表示正常关闭。 */
    @Synchronized
    fun onDisconnected(reason: String?, nowMs: Long) {
        online = false
        lastDisconnectedAtMs = nowMs
        if (!reason.isNullOrBlank()) {
            lastFailureReason = reason
            consecutiveFailures++
        }
    }

    /** 每次调度一次重连尝试时调用（累计，不清零）。 */
    @Synchronized
    fun onReconnectAttempt() {
        totalReconnectAttempts++
    }

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(
        online = online,
        totalReconnectAttempts = totalReconnectAttempts,
        consecutiveFailures = consecutiveFailures,
        lastFailureReason = lastFailureReason,
        lastConnectedAtMs = lastConnectedAtMs,
        lastDisconnectedAtMs = lastDisconnectedAtMs,
    )

    /** 一句话人类可读状态，供 UI/控制台直接展示。 */
    @Synchronized
    fun summary(nowMs: Long): String {
        if (online) {
            return if (totalReconnectAttempts > 0) "在线（本会话重连过 $totalReconnectAttempts 次）" else "在线"
        }
        val offlineFor = if (lastDisconnectedAtMs > 0) humanDuration(nowMs - lastDisconnectedAtMs) else null
        return buildString {
            append(if (consecutiveFailures > 0) "断线重连中" else "未连接")
            if (consecutiveFailures > 0) append("（连续失败 $consecutiveFailures 次）")
            if (offlineFor != null) append("，已离线 $offlineFor")
            if (!lastFailureReason.isNullOrBlank()) append("，上次原因：$lastFailureReason")
        }
    }

    private fun humanDuration(ms: Long): String {
        if (ms < 0) return "0秒"
        val sec = ms / 1000
        return when {
            sec < 60 -> "${sec}秒"
            sec < 3600 -> "${sec / 60}分${sec % 60}秒"
            else -> "${sec / 3600}小时${(sec % 3600) / 60}分"
        }
    }
}
