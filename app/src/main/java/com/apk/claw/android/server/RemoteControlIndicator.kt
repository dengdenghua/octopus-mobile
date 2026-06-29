package com.apk.claw.android.server

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.R
import com.apk.claw.android.utils.XLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 远程控制指示器 —— 当设备被远程查看或控制时，显示持续可见的通知。
 *
 * 功能：
 * 1. MJPEG 屏幕流开始时，显示"正在被远程查看"通知
 * 2. 收到控制输入时，升级为"正在被远程控制"通知
 * 3. 所有会话结束后，清除通知
 * 4. 通知点击跳转到信任中心（查看/终止远程访问）
 *
 * 线程安全：所有方法均可从任意线程调用。
 */
object RemoteControlIndicator {

    private const val TAG = "RemoteCtrlIndicator"
    private const val CHANNEL_ID = "octopus_remote_control"
    private const val NOTIF_ID = 9530

    /** 控制输入"最近活跃"的超时：超过此时间无新输入则降级为"查看中" */
    private const val CONTROL_ACTIVE_TIMEOUT_MS = 10_000L

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 活跃的屏幕查看会话（source → 开始时间） */
    private val viewers = ConcurrentHashMap<String, Long>()

    /** 活跃的控制会话（source → 最后控制时间） */
    private val controllers = ConcurrentHashMap<String, Long>()

    /** 总连接计数（用于通知文案） */
    private val viewerCount = AtomicInteger(0)
    private val controllerCount = AtomicInteger(0)

    private val isControlActive get() = controllers.values.any { System.currentTimeMillis() - it < CONTROL_ACTIVE_TIMEOUT_MS }

    // ==================== 公开 API ====================

    /** 屏幕流开始（MJPEG/H.264/WebRTC 远程查看会话建立）。 */
    fun onViewerStarted(source: String) {
        viewers[source] = System.currentTimeMillis()
        viewerCount.incrementAndGet()
        XLog.i(TAG, "Remote viewer started: $source (total viewers=${viewerCount.get()})")
        updateNotification()
    }

    /** 屏幕流结束。 */
    fun onViewerEnded(source: String) {
        viewers.remove(source)
        viewerCount.updateAndGet { (it - 1).coerceAtLeast(0) }
        XLog.i(TAG, "Remote viewer ended: $source (total viewers=${viewerCount.get()})")
        updateNotification()
    }

    /** 收到远程控制输入（tap/swipe/key/text 等）。 */
    fun onControlInput(source: String) {
        controllers[source] = System.currentTimeMillis()
        if (controllerCount.get() == 0) {
            controllerCount.set(1)
        }
        XLog.d(TAG, "Remote control input from: $source")
        updateNotification()
    }

    /** PC 远程桌面会话开始（通过 WebSocket 的 pc_screen/subscribe）。 */
    fun onPcRemoteStarted(source: String) {
        onViewerStarted("PC:$source")
    }

    /** PC 远程桌面会话结束。 */
    fun onPcRemoteEnded(source: String) {
        onViewerEnded("PC:$source")
    }

    /** 是否有活跃的远程会话。 */
    fun hasActiveSession(): Boolean = viewers.isNotEmpty() || isControlActive

    // ==================== 通知管理 ====================

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "远程控制提醒",
                NotificationManager.IMPORTANCE_LOW, // 低优先级：不发声，但持续可见
            ).apply {
                description = "设备被远程查看或控制时显示提醒"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun updateNotification() {
        mainHandler.post {
            val context = ClawApplication.instance
            createChannel(context)

            val nm = context.getSystemService(NotificationManager::class.java) ?: return@post

            if (viewers.isEmpty() && !isControlActive) {
                nm.cancel(NOTIF_ID)
                return@post
            }

            // 清理过期的控制会话
            val now = System.currentTimeMillis()
            controllers.entries.removeAll { now - it.value > CONTROL_ACTIVE_TIMEOUT_MS }
            if (controllers.isEmpty()) controllerCount.set(0)

            val controlling = isControlActive
            val title = if (controlling) {
                context.getString(R.string.remote_control_notif_controlling)
            } else {
                context.getString(R.string.remote_control_notif_viewing)
            }
            val sources = (viewers.keys + controllers.keys).distinct()
            val text = context.getString(
                R.string.remote_control_notif_text,
                viewerCount.get().coerceAtLeast(0),
                if (controlling) controllerCount.get() else 0,
                sources.joinToString(", ").take(60),
            )

            // 点击跳转到信任中心
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pendingIntent = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val notif = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_cast)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(pendingIntent)
                .build()

            nm.notify(NOTIF_ID, notif)
        }
    }
}
