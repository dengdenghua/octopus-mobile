package com.apk.claw.android.shizuku

import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku

/**
 * Shizuku 管理器 —— 负责初始化、权限检查和 Binder 生命周期。
 *
 * 使用方式：
 * ```
 * // 在 Application.onCreate() 中初始化
 * ShizukuManager.init()
 *
 * // 检查是否可用
 * if (ShizukuManager.isAvailable()) {
 *     val service = ShizukuShellService.getInstance()
 *     service.tap(500, 500)
 * }
 * ```
 *
 * 用户前提：
 *  1. 已安装 Shizuku App（从 Play Store / GitHub / F-Droid）
 *  2. 已通过无线调试（Android 11+）或 PC ADB 授权一次
 */
object ShizukuManager {

    private const val TAG = "ShizukuManager"
    private const val REQUEST_CODE = 1001

    /** Shizuku Binder 是否已获取 */
    @Volatile
    var isBinderAlive: Boolean = false
        private set

    /** Shizuku 权限是否已授予 */
    @Volatile
    var isPermissionGranted: Boolean = false
        private set

    /**
     * 初始化 Shizuku 监听器。
     * 应在 Application.onCreate() 中调用。
     */
    fun init() {
        // 监听 Binder 到达
        Shizuku.addBinderReceivedListenerSticky {
            Log.i(TAG, "Shizuku binder received")
            isBinderAlive = true
            checkPermission()
        }

        // 监听 Binder 死亡
        Shizuku.addBinderDeadListener {
            Log.w(TAG, "Shizuku binder dead")
            isBinderAlive = false
            isPermissionGranted = false
        }
    }

    /**
     * 检查 Shizuku 是否可用（已安装 + Binder 存活 + 权限已授予）。
     */
    fun isAvailable(): Boolean {
        return isBinderAlive && isPermissionGranted
    }

    /**
     * 检查当前是否已授予 Shizuku 权限。
     * 如果未授予，可通过 requestPermission() 请求。
     */
    fun checkPermission(): Boolean {
        if (!isBinderAlive) return false
        return try {
            val granted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            isPermissionGranted = granted
            if (granted) {
                Log.i(TAG, "Shizuku permission granted")
            } else {
                Log.i(TAG, "Shizuku permission not granted")
            }
            granted
        } catch (e: Exception) {
            Log.e(TAG, "checkPermission failed", e)
            false
        }
    }

    /**
     * 请求 Shizuku 权限（会弹出 Shizuku 授权对话框）。
     * 结果通过 addRequestPermissionResultListener 回调。
     */
    fun requestPermission() {
        if (!isBinderAlive) {
            Log.w(TAG, "Cannot request permission: binder not alive")
            return
        }
        if (isPermissionGranted) return

        Shizuku.addRequestPermissionResultListener { requestCode, grantResult ->
            val granted = grantResult == PackageManager.PERMISSION_GRANTED
            isPermissionGranted = granted
            Log.i(TAG, "Permission request result: $granted (requestCode=$requestCode)")
        }
        Shizuku.requestPermission(REQUEST_CODE)
    }

    /**
     * 检查 Shizuku App 是否已安装（不需要 Binder 也能判断）。
     */
    fun isShizukuInstalled(packageManager: PackageManager): Boolean {
        return try {
            packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }
}
