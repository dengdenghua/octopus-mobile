package com.apk.claw.android.octopus_mobile

import android.content.Context
import com.apk.claw.android.server.ConfigServer
import com.apk.claw.android.server.ConfigServerManager
import com.apk.claw.android.utils.KVUtils
import com.apk.claw.android.utils.XLog
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlin.coroutines.coroutineContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections

/**
 * 局域网设备发现管理器。
 *
 * 原理：
 *  - UDP Broadcast 端口 9528，每 5 秒发送 beacon
 *  - 监听其他设备的 beacon，更新 DeviceRegistry
 *  - 30 秒无 beacon 的设备自动标记为 OFFLINE
 *
 * Beacon 格式（JSON）:
 *  ```json
 *  {
 *    "type": "octopus-beacon",
 *    "deviceId": "xiaomi_redmi_k60",
 *    "deviceName": "Redmi K60",
 *    "ip": "192.168.1.100",
 *    "configServerPort": 9527,
 *    "androidVersion": "14",
 *    "appVersion": "1.0.0"
 *  }
 *  ```
 */
class DeviceDiscoveryManager(
    private val context: Context,
    private val registry: DeviceRegistry
) {

    companion object {
        private const val TAG = "DeviceDiscoveryMgr"
        const val BEACON_PORT = 9528
        private const val BEACON_INTERVAL_MS = 5_000L
        private const val STALE_CHECK_INTERVAL_MS = 10_000L
        private const val RECEIVE_BUFFER_SIZE = 2048
        private const val BEACON_TYPE = "octopus-beacon"
    }

    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 本机 deviceId */
    private val localDeviceId: String by lazy {
        "${android.os.Build.BRAND}_${android.os.Build.MODEL}".replace(" ", "_").lowercase()
    }

    @Volatile
    private var running = false

    private var senderJob: Job? = null
    private var receiverJob: Job? = null
    private var staleCheckerJob: Job? = null
    private var socket: DatagramSocket? = null

    /**
     * 启动设备发现（发送 + 接收 beacon）。
     */
    fun start() {
        if (running) return
        running = true

        try {
            socket = DatagramSocket(BEACON_PORT).apply {
                broadcast = true
                reuseAddress = true
                soTimeout = 3000 // 3 秒超时，避免 receive 永久阻塞
            }
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to bind UDP socket: ${e.message}")
            running = false
            return
        }

        senderJob = scope.launch { sendBeaconLoop() }
        receiverJob = scope.launch { receiveBeaconLoop() }
        staleCheckerJob = scope.launch { staleCheckerLoop() }

        XLog.i(TAG, "Device discovery started (port=$BEACON_PORT)")
    }

    /**
     * 停止设备发现。
     */
    fun stop() {
        running = false
        senderJob?.cancel()
        receiverJob?.cancel()
        staleCheckerJob?.cancel()
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        XLog.i(TAG, "Device discovery stopped")
    }

    /**
     * 是否正在运行。
     */
    fun isRunning(): Boolean = running

    // ── 发送 Beacon ──

    private suspend fun sendBeaconLoop() {
        while (running && coroutineContext.isActive) {
            try {
                sendBeacon()
            } catch (e: Exception) {
                XLog.w(TAG, "Send beacon failed: ${e.message}")
            }
            delay(BEACON_INTERVAL_MS)
        }
    }

    private fun sendBeacon() {
        val localIp = getLocalIp() ?: return
        val beacon = mapOf(
            "type" to BEACON_TYPE,
            "deviceId" to localDeviceId,
            "deviceName" to "${android.os.Build.BRAND} ${android.os.Build.MODEL}",
            "ip" to localIp,
            "configServerPort" to ConfigServer.PORT,
            "androidVersion" to android.os.Build.VERSION.RELEASE,
            "appVersion" to getAppVersion(),
            // 仅当用户显式允许「被局域网控制」时才广播控制 token；默认关闭，避免明文泄露
            "authToken" to (if (KVUtils.isLanControlEnabled()) ConfigServerManager.getAuthToken() ?: "" else "")
        )
        val json = gson.toJson(beacon)
        val bytes = json.toByteArray(Charsets.UTF_8)

        val broadcastAddr = InetAddress.getByName("255.255.255.255")
        val packet = DatagramPacket(bytes, bytes.size, broadcastAddr, BEACON_PORT)
        socket?.send(packet)
        XLog.v(TAG, "Beacon sent: $localIp")
    }

    // ── 接收 Beacon ──

    private suspend fun receiveBeaconLoop() {
        val buf = ByteArray(RECEIVE_BUFFER_SIZE)
        while (running && coroutineContext.isActive) {
            try {
                val packet = DatagramPacket(buf, buf.size)
                socket?.receive(packet)

                val json = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                processBeacon(json, packet.address?.hostAddress ?: continue)
            } catch (_: java.net.SocketTimeoutException) {
                // 正常的 3 秒超时，继续循环
            } catch (_: CancellationException) {
                break
            } catch (e: Exception) {
                XLog.w(TAG, "Receive error: ${e.message}")
                delay(500) // 短暂退避
            }
        }
    }

    private fun processBeacon(json: String, sourceIp: String) {
        try {
            val map = gson.fromJson(json, Map::class.java) as? Map<*, *> ?: return
            if (map["type"] != BEACON_TYPE) return

            val deviceId = map["deviceId"] as? String ?: return
            // 忽略自己的 beacon
            if (deviceId == localDeviceId) return

            val device = DeviceInfo(
                deviceId = deviceId,
                deviceName = map["deviceName"] as? String ?: deviceId,
                ip = map["ip"] as? String ?: sourceIp,
                configServerPort = (map["configServerPort"] as? Double)?.toInt() ?: ConfigServer.PORT,
                androidVersion = map["androidVersion"] as? String ?: "",
                appVersion = map["appVersion"] as? String ?: "",
                authToken = map["authToken"] as? String ?: ""
            )

            registry.upsertDevice(device)
        } catch (e: Exception) {
            XLog.w(TAG, "Process beacon failed: ${e.message}")
        }
    }

    // ── 超时检测 ──

    private suspend fun staleCheckerLoop() {
        while (running && coroutineContext.isActive) {
            delay(STALE_CHECK_INTERVAL_MS)
            registry.markStaleOffline()
        }
    }

    // ── 工具方法 ──

    /**
     * 获取本机 WiFi IP 地址。
     */
    private fun getLocalIp(): String? {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (ni in interfaces) {
                if (ni.isLoopback || !ni.isUp) continue
                val addrs = Collections.list(ni.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr.hostAddress?.contains(':') == false) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            XLog.e(TAG, "getLocalIp failed: ${e.message}")
        }
        return null
    }

    private fun getAppVersion(): String {
        return try {
            val pm = context.packageManager
            pm.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }
    }
}
