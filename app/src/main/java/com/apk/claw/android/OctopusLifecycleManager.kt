package com.apk.claw.android

import android.content.Intent
import android.os.PowerManager
import com.apk.claw.android.ClawApplication.Companion.appViewModelInstance
import com.apk.claw.android.channel.ChannelSetup
import com.apk.claw.android.floating.FloatingCircleManager
import com.apk.claw.android.server.ConfigServerManager
import com.apk.claw.android.service.ForegroundService
import com.apk.claw.android.service.KeepAliveJobService
import com.apk.claw.android.utils.KVUtils
import com.apk.claw.android.utils.XLog

/**
 * 管理 Octopus Mobile 应用的生命周期相关操作：
 * - 亮屏 WakeLock 获取/释放
 * - 前台服务、保活任务、配置服务器启动
 * - 悬浮窗显示与前台切换
 */
class OctopusLifecycleManager(private val app: ClawApplication) {

    companion object {
        private const val TAG = "OctopusLifecycleManager"
    }

    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * 应用初始化完成后的编排入口（替代原 AppViewModel.afterInit）
     */
    fun onAppInitialized(channelSetup: ChannelSetup, autoConnectAction: () -> Unit) {
        acquireScreenWakeLock()
        ForegroundService.start(app)
        KeepAliveJobService.schedule(app)
        ConfigServerManager.autoStartIfNeeded(app)
        if (android.provider.Settings.canDrawOverlays(app)) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                appViewModelInstance.showFloatingCircle()
            }
        }
        channelSetup.setup()
        // 自动连接 Runtime（如果已配置且启用了自动连接）
        if (KVUtils.getOctopusRpcUrl().isNotEmpty() && KVUtils.isOctopusAutoConnect()) {
            autoConnectAction()
        }
    }

    /**
     * 显示圆形悬浮窗
     */
    fun showFloatingCircle() {
        try {
            FloatingCircleManager.show(app)
            FloatingCircleManager.onFloatClick = {
                XLog.d(TAG, "Floating circle clicked")
                bringAppToForeground()
            }
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to show floating circle: ${e.message}")
        }
    }

    /**
     * 获取亮屏锁，防止息屏后无障碍服务无法操作
     */
    private fun acquireScreenWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = app.getSystemService(android.content.Context.POWER_SERVICE) as? PowerManager
            ?: return
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "OctopusMobile::ScreenWakeLock"
        ).apply {
            acquire()
        }
        XLog.i(TAG, "亮屏锁已获取")
    }

    /**
     * 释放亮屏锁
     */
    private fun releaseScreenWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                XLog.i(TAG, "亮屏锁已释放")
            }
        }
        wakeLock = null
    }

    /**
     * 将应用带回前台
     */
    private fun bringAppToForeground() {
        val intent = Intent(app, com.apk.claw.android.ui.home.HomeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        app.startActivity(intent)
    }
}
