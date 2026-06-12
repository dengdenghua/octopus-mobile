package com.apk.claw.android.octopus_mobile

import com.apk.claw.android.utils.KVUtils
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.apk.claw.android.utils.XLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 设备注册表 —— 存储局域网内发现的 Octopus Mobile 设备信息。
 *
 * 使用 MMKV 持久化，StateFlow 供 UI 观察。
 */
class DeviceRegistry {

    companion object {
        private const val TAG = "DeviceRegistry"
        private const val KEY_DEVICE_REGISTRY = "OCTOPUS_DEVICE_REGISTRY"
        /** 30 秒无心跳标记为离线 */
        private const val OFFLINE_TIMEOUT_MS = 30_000L
    }

    private val gson = Gson()

    /** 设备列表（内存缓存） */
    private val devices = mutableMapOf<String, DeviceInfo>()

    /** 供 UI 观察的设备列表 */
    private val _deviceList = MutableStateFlow<List<DeviceInfo>>(emptyList())
    val deviceList: StateFlow<List<DeviceInfo>> = _deviceList

    init {
        loadFromStorage()
    }

    /**
     * 插入或更新设备。
     */
    fun upsertDevice(device: DeviceInfo) {
        val existing = devices[device.deviceId]
        val updated = device.copy(
            lastSeenTs = System.currentTimeMillis(),
            online = true,
            firstSeenTs = existing?.firstSeenTs ?: device.firstSeenTs
        )
        devices[device.deviceId] = updated
        emitUpdate()
        persist()
        XLog.d(TAG, "upsertDevice: ${device.deviceId} (${device.deviceName}) at ${device.ip}:${device.configServerPort}")
    }

    /**
     * 移除设备。
     */
    fun removeDevice(deviceId: String) {
        devices.remove(deviceId)
        emitUpdate()
        persist()
    }

    /**
     * 获取所有在线设备。
     */
    fun getOnlineDevices(): List<DeviceInfo> {
        return devices.values.filter { it.online }.toList()
    }

    /**
     * 获取所有设备（含离线）。
     */
    fun getAllDevices(): List<DeviceInfo> {
        return devices.values.toList()
    }

    /**
     * 根据 deviceId 获取设备。
     */
    fun getDevice(deviceId: String): DeviceInfo? = devices[deviceId]

    /**
     * 标记超时设备为离线。
     * 由 DeviceDiscoveryManager 定期调用。
     */
    fun markStaleOffline() {
        val now = System.currentTimeMillis()
        var changed = false
        for ((id, device) in devices) {
            if (device.online && (now - device.lastSeenTs) > OFFLINE_TIMEOUT_MS) {
                devices[id] = device.copy(online = false)
                changed = true
                XLog.d(TAG, "Mark offline: $id (last seen ${now - device.lastSeenTs}ms ago)")
            }
        }
        if (changed) {
            emitUpdate()
            persist()
        }
    }

    /**
     * 标记指定设备为离线。
     */
    fun markOffline(deviceId: String) {
        val device = devices[deviceId] ?: return
        if (device.online) {
            devices[deviceId] = device.copy(online = false)
            emitUpdate()
            persist()
        }
    }

    /**
     * 清空所有设备。
     */
    fun clear() {
        devices.clear()
        emitUpdate()
        persist()
    }

    private fun emitUpdate() {
        _deviceList.value = devices.values.toList().sortedByDescending { it.lastSeenTs }
    }

    // ── 持久化 ──

    private fun persist() {
        try {
            val json = gson.toJson(devices.values.toList())
            KVUtils.putString(KEY_DEVICE_REGISTRY, json)
        } catch (e: Exception) {
            XLog.e(TAG, "persist failed: ${e.message}")
        }
    }

    private fun loadFromStorage() {
        try {
            val json = KVUtils.getString(KEY_DEVICE_REGISTRY, "")
            if (json.isNotEmpty()) {
                val type = object : TypeToken<List<DeviceInfo>>() {}.type
                val list: List<DeviceInfo> = gson.fromJson(json, type)
                for (d in list) {
                    // 加载时标记为离线（需要重新发现）
                    devices[d.deviceId] = d.copy(online = false)
                }
            }
        } catch (e: Exception) {
            XLog.e(TAG, "loadFromStorage failed: ${e.message}")
        }
        emitUpdate()
    }
}

/**
 * 设备信息数据类。
 */
data class DeviceInfo(
    /** 设备唯一标识（brand_model） */
    val deviceId: String,
    /** 设备显示名称 */
    val deviceName: String,
    /** IP 地址 */
    val ip: String,
    /** ConfigServer 端口 */
    val configServerPort: Int = 9527,
    /** Android 版本 */
    val androidVersion: String = "",
    /** App 版本 */
    val appVersion: String = "",
    /** 首次发现时间 */
    val firstSeenTs: Long = System.currentTimeMillis(),
    /** 最近一次心跳时间 */
    val lastSeenTs: Long = System.currentTimeMillis(),
    /** 是否在线 */
    val online: Boolean = true,
    /** 对端 ConfigServer 的鉴权 token（随 beacon 广播，用于远程控制鉴权） */
    val authToken: String = ""
) {
    /**
     * 获取 ConfigServer 的 base URL。
     */
    fun getBaseUrl(): String = "http://$ip:$configServerPort"
}
