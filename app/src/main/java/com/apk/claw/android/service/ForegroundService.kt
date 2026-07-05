package com.apk.claw.android.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.apk.claw.android.R
import com.apk.claw.android.server.ConfigServerManager
import com.apk.claw.android.ui.home.HomeActivity

/**
 * 前台服务 - 常驻通知
 */
class ForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "octopus_mobile_foreground_channel"
        const val NOTIFICATION_ID = 1001

        @Volatile
        private var _isRunning = false

        /**
         * 检查前台服务是否正在运行
         */
        fun isRunning(): Boolean = _isRunning

        /**
         * 启动前台服务
         * @param context Context
         * @return 是否成功启动（无权限时返回 false）
         */
        fun start(context: Context): Boolean {
            // 前台服务无需 POST_NOTIFICATIONS 即可启动保活 —— 该权限只决定「运行中」通知
            // 是否可见,不影响服务能否拉起。此前缺通知权限就 return false,导致前台服务从不
            // 启动、进程无保活,无障碍服务随后台被回收一起死(真机「滑返回无障碍就关」的真凶,
            // 2026-07-05 定位)。改为无条件启动;通知可见性另由权限引导解决。
            val intent = Intent(context, ForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            return true
        }

        fun stop(context: Context) {
            val intent = Intent(context, ForegroundService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        _isRunning = true
        createNotificationChannel()
    }

    override fun onDestroy() {
        super.onDestroy()
        _isRunning = false
        ConfigServerManager.stop()
        if (ClawAccessibilityService.isRunning()) {
            scheduleRestart(0)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (ClawAccessibilityService.isRunning()) {
            scheduleRestart(1)
        }
    }

    private fun scheduleRestart(requestCode: Int) {
        val restartIntent = Intent(applicationContext, ForegroundService::class.java)
        val pendingRestart = PendingIntent.getService(
            applicationContext, requestCode, restartIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 3000, pendingRestart)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
        runCatching { RoutineScheduler.rescheduleAll(this) }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, HomeActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_content_title))
            .setContentText(getString(R.string.notification_content_text))
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()
    }
}
