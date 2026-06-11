package com.apk.claw.android.octopus_mobile.proactive

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * 通知监听中继 —— 将系统通知转发给 ProactiveRuleEngine.
 *
 * 用户需在系统设置中授予通知访问权限.
 */
class NotificationRelayService : NotificationListenerService() {

    companion object {
        private const val TAG = "NotificationRelay"

        @Volatile
        var proactiveEngine: ProactiveRuleEngine? = null

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isRunning = true
        Log.i(TAG, "Notification listener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isRunning = false
        Log.i(TAG, "Notification listener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        sbn ?: return
        val engine = proactiveEngine ?: return

        val packageName = sbn.packageName
        val notification = sbn.notification
        val extras = notification.extras

        val title = extras.getString("android.title") ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""

        try {
            val results = engine.onNotification(packageName, title, text)
            results.forEach { result ->
                Log.d(TAG, "Proactive: ${result.message}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Proactive rule execution failed", e)
        }
    }
}
