package com.apk.claw.android.octopus_mobile

/**
 * 当前 Agent 设备控制的「目标」。
 *
 *  - null  → 本机（走无障碍 ClawAccessibilityService，默认）
 *  - 非空  → 局域网远程设备（点击/输入/截屏等转发到对端 ConfigServer）
 *
 * 由对话页的「目标选择器」设置；设备控制类工具在执行前读取此目标决定路由。
 */
object ControlTarget {

    @Volatile
    private var target: DeviceInfo? = null

    /** 远程目标设备；本机时返回 null。 */
    @JvmStatic
    fun remoteTarget(): DeviceInfo? = target

    @JvmStatic
    fun isRemote(): Boolean = target != null

    @JvmStatic
    fun setLocal() {
        target = null
    }

    @JvmStatic
    fun setRemote(device: DeviceInfo) {
        target = device
    }

    /** 当前目标显示名（本机 / 设备名）。 */
    @JvmStatic
    fun label(): String = target?.deviceName ?: "本机"

    /** 当前目标 id（本机为 "local"）。 */
    @JvmStatic
    fun id(): String = target?.deviceId ?: "local"
}
