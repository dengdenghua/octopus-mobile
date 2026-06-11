package com.apk.claw.android.octopus_mobile

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import com.apk.claw.android.service.ClawAccessibilityService
import kotlinx.coroutines.*

/**
 * 方案 F · 心跳上报器.
 *
 * 周期性（默认 30s）向 octopus-agent Runtime 推送设备状态：
 *  - 电量 + 是否充电
 *  - 当前前台应用（从无障碍服务取）
 *  - 最近一次屏幕树哈希（去重 + 心跳包轻量化）
 *  - 在线时间戳
 *
 * 设计要点：
 *  - 与 ConnectionState 联动：仅在 ONLINE 时发送
 *  - 与 ConnectionStateMachine.getBackoffDelayMs 协同：断线时不发浪费
 *  - 后台协程：SupervisorJob 隔离异常，避免影响主流程
 *  - 极简 payload：< 1KB
 */
class HeartbeatReporter(
    private val context: Context,
    private val client: OctopusMobileClient,
    private val intervalMs: Long = 30_000L,
) {
    private val tag = "HeartbeatReporter"

    /** 上次屏幕树哈希（由 ScreenStreamer 写入） */
    @Volatile
    var lastScreenHash: String? = null

    /** 当前前台应用（由 ScreenStreamer / Agent 写入） */
    @Volatile
    var currentApp: String? = null

    private var heartbeatJob: Job? = null

    /**
     * 启动心跳循环.
     */
    fun start(scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
        stop()
        heartbeatJob = scope.launch {
            // 立即发一次，缩短首次感知延迟
            sendOnce()
            while (isActive) {
                delay(intervalMs)
                sendOnce()
            }
        }
        Log.i(tag, "HeartbeatReporter started (interval=${intervalMs}ms)")
    }

    /**
     * 停止心跳.
     */
    fun stop() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    /**
     * 主动发一次心跳（外部可触发，如状态切换后立即上报）.
     */
    suspend fun sendOnce() {
        if (client.currentState() != ConnectionState.ONLINE) return

        val battery = readBatteryLevel()
        val isCharging = readIsCharging()
        val envelope = EnvelopeFactory.heartbeat(
            tentacleId = "",  // 在 OctopusMobileClient.send 时会自动填入
            currentApp = currentApp ?: detectCurrentApp(),
            battery = battery,
            isCharging = isCharging,
            screenTreeHash = lastScreenHash,
        )
        try {
            client.send(envelope)
            Log.d(tag, "heartbeat sent: battery=$battery% charging=$isCharging app=$currentApp")
        } catch (e: Exception) {
            Log.w(tag, "heartbeat send failed: ${e.message}")
        }
    }

    /**
     * 读取电池电量（0-100）.
     */
    private fun readBatteryLevel(): Int {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        } catch (e: Exception) {
            -1
        }
    }

    /**
     * 读取是否在充电.
     */
    private fun readIsCharging(): Boolean {
        return try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val intent = context.registerReceiver(null, filter) ?: return false
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 检测当前前台应用.
     *
     * 优先从无障碍服务取（更准确），
     * 失败时回退到 UsageStatsManager（需权限）或返回 null.
     */
    private fun detectCurrentApp(): String? {
        return try {
            ClawAccessibilityService.getInstance()?.rootInActiveWindow?.packageName?.toString()
        } catch (e: Exception) {
            null
        }
    }
}
