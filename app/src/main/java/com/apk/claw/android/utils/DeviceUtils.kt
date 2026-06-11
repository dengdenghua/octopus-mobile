package com.apk.claw.android.utils

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build

/**
 * 设备类型检测工具 —— 区分 TV 盒子 / 手机 / 平板。
 *
 * TV 检测依据（满足任一即判定为 TV）：
 * 1. PackageManager.hasSystemFeature("android.software.leanback")
 * 2. UiModeManager.currentModeType == UI_MODE_TYPE_TELEVISION
 * 3. 无触摸屏（!hasSystemFeature("android.hardware.touchscreen")）
 */
object DeviceUtils {

    private var _isTv: Boolean? = null
    private var _isMobile: Boolean? = null

    /**
     * 判断是否为 TV 设备（Android TV / Google TV / TV 盒子）。
     * 结果首次调用后缓存。
     */
    fun isTvDevice(context: Context): Boolean {
        _isTv?.let { return it }
        val pm = context.packageManager
        val isTv = pm.hasSystemFeature("android.software.leanback")
            || isUiModeTv(context)
            || !pm.hasSystemFeature("android.hardware.touchscreen")
        _isTv = isTv
        return isTv
    }

    /**
     * 判断是否为手机设备（有触摸屏 + 不是 TV）。
     */
    fun isMobileDevice(context: Context): Boolean {
        _isMobile?.let { return it }
        val isMobile = !isTvDevice(context)
        _isMobile = isMobile
        return isMobile
    }

    /**
     * TV 设备是否有 Leanback 特性。
     */
    fun hasLeanback(context: Context): Boolean {
        return context.packageManager.hasSystemFeature("android.software.leanback")
    }

    /**
     * 设备是否有触摸屏。
     */
    fun hasTouchscreen(context: Context): Boolean {
        return context.packageManager.hasSystemFeature("android.hardware.touchscreen")
    }

    /**
     * 设备是否有电话功能（SIM 卡）。
     */
    fun hasTelephony(context: Context): Boolean {
        return context.packageManager.hasSystemFeature("android.hardware.telephony")
    }

    /**
     * 通过 UiModeManager 检测是否为 TV 模式。
     * 部分 TV 盒子的 leanback feature 不一定声明，但 uiMode 会标记为 TV。
     */
    private fun isUiModeTv(context: Context): Boolean {
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        return uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }

    /**
     * 获取设备类型描述字符串（用于日志）。
     */
    fun getDeviceDescription(context: Context): String {
        val type = if (isTvDevice(context)) "TV" else "Mobile"
        val brand = Build.BRAND
        val model = Build.MODEL
        val sdk = Build.VERSION.SDK_INT
        return "$type | $brand $model | SDK $sdk | leanback=${hasLeanback(context)} touch=${hasTouchscreen(context)} telephony=${hasTelephony(context)}"
    }
}
