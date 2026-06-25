package com.apk.claw.android

import com.apk.claw.android.octopus_mobile.ConnectionState
import com.apk.claw.android.octopus_mobile.DualConfigWriter
import com.apk.claw.android.octopus_mobile.HeartbeatReporter
import com.apk.claw.android.octopus_mobile.OctopusMobileClient
import com.apk.claw.android.octopus_mobile.ScreenStreamer
import com.apk.claw.android.octopus_mobile.SkillExporter
import com.apk.claw.android.octopus_mobile.ToolCallDispatcher
import com.apk.claw.android.utils.KVUtils
import com.apk.claw.android.utils.XLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 管理 Octopus Mobile 与 Runtime 的连接生命周期：
 * - 连接 / 断开 Runtime
 * - 子组件（HeartbeatReporter / ToolCallDispatcher / ScreenStreamer / DualConfigWriter）启停
 * - 连接状态暴露
 */
class OctopusConnectionManager(
    private val octopusClient: OctopusMobileClient,
    private val toolDispatcher: ToolCallDispatcher?,
    private val heartbeatReporter: HeartbeatReporter?,
    private val screenStreamer: ScreenStreamer?,
    private val dualConfigWriter: DualConfigWriter?,
    private val coroutineScope: CoroutineScope
) {

    companion object {
        private const val TAG = "OctopusConnectionManager"
    }

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    /** Runtime URL 是否已配置 */
    fun isConfigured(): Boolean = KVUtils.getOctopusRpcUrl().isNotEmpty()

    /** 连接到 Runtime */
    fun connect() {
        if (_connectionState.value == ConnectionState.ONLINE ||
            _connectionState.value == ConnectionState.CONNECTING) {
            XLog.d(TAG, "Already connected or connecting")
            return
        }

        _connectionState.value = ConnectionState.CONNECTING
        octopusClient.onStateChanged = { state ->
            _connectionState.value = state
            if (state == ConnectionState.ONLINE) {
                startSubComponents()
            }
        }
        octopusClient.connect()
        XLog.i(TAG, "Connecting to Runtime: ${KVUtils.getOctopusRpcUrl()}")
    }

    /** 断开 Runtime */
    fun disconnect() {
        stopSubComponents()
        octopusClient.disconnect()
        _connectionState.value = ConnectionState.OFFLINE
        XLog.i(TAG, "Disconnected from Runtime")
    }

    /** 启动子组件（ONLINE 时调用） */
    private fun startSubComponents() {
        toolDispatcher?.start()
        heartbeatReporter?.start(coroutineScope)
        screenStreamer?.start()
        ScreenStreamer.registerListener(screenStreamer!!)
        dualConfigWriter?.initialSync()

        // 上传 SKILL.md
        val skillPairs = SkillExporter.exportAllFromRegistry()
        SkillExporter.uploadToRuntime(octopusClient, skillPairs)
        XLog.i(TAG, "Sub-components started (Heartbeat/ToolDispatcher/ScreenStreamer/DualConfig)")
    }

    /** 停止子组件 */
    private fun stopSubComponents() {
        toolDispatcher?.stop()
        heartbeatReporter?.stop()
        screenStreamer?.stop()
        XLog.i(TAG, "Sub-components stopped")
    }
}
