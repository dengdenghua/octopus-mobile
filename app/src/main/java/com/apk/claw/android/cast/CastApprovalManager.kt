package com.apk.claw.android.cast

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.apk.claw.android.R
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 投屏请求用户确认闸。
 *
 * /api/cast/start 与 /api/cast/launch 在真正执行前，必须经设备端通知让用户显式允许。
 * 若用户未在 [DEFAULT_TIMEOUT_MS] 内响应，或点击拒绝，则请求失败。
 */
object CastApprovalManager {

    private const val CHANNEL_ID = "octopus_cast_approval"
    internal const val ACTION_APPROVE = "com.apk.claw.android.cast.ACTION_APPROVE_CAST"
    internal const val ACTION_DENY = "com.apk.claw.android.cast.ACTION_DENY_CAST"
    internal const val EXTRA_REQUEST_ID = "request_id"
    private const val DEFAULT_TIMEOUT_MS = 30_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pending = ConcurrentHashMap<String, LatchEntry>()
    private val notifyIdSeq = AtomicInteger(9600)

    private data class LatchEntry(
        val latch: CountDownLatch,
        @Volatile var approved: Boolean = false,
        val notificationId: Int,
    )

    /**
     * 请求用户批准当前投屏/启动操作。
     *
     * @param source 请求来源（如远程 IP），用于通知文案
     * @return true=用户允许；false=拒绝/超时/无通知权限
     */
    fun requestApproval(context: Context, source: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Boolean {
        if (!canPostNotifications(context)) return false

        val requestId = UUID.randomUUID().toString()
        val latch = CountDownLatch(1)
        val notificationId = notifyIdSeq.getAndIncrement()
        pending[requestId] = LatchEntry(latch, notificationId = notificationId)

        showNotification(context, requestId, source, notificationId)

        val becameUnblocked = try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

        val entry = pending.remove(requestId)
        cancelNotification(context, notificationId)
        return becameUnblocked && entry?.approved == true
    }

    /**
     * 由 [CastApprovalReceiver] 调用，解析用户选择。
     */
    fun resolve(requestId: String, approved: Boolean) {
        pending[requestId]?.let {
            it.approved = approved
            it.latch.countDown()
        }
    }

    private fun canPostNotifications(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun showNotification(context: Context, requestId: String, source: String, notificationId: Int) {
        mainHandler.post {
            createChannel(context)
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return@post

            val approveIntent = Intent(context, CastApprovalReceiver::class.java)
                .setAction(ACTION_APPROVE)
                .putExtra(EXTRA_REQUEST_ID, requestId)
            val denyIntent = Intent(context, CastApprovalReceiver::class.java)
                .setAction(ACTION_DENY)
                .putExtra(EXTRA_REQUEST_ID, requestId)

            val approvePending = PendingIntent.getBroadcast(
                context,
                notificationId,
                approveIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val denyPending = PendingIntent.getBroadcast(
                context,
                -notificationId,
                denyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val contentPending = if (launchIntent != null) {
                PendingIntent.getActivity(
                    context,
                    0,
                    launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            } else null

            val notif = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_cast)
                .setContentTitle(context.getString(R.string.cast_approval_title))
                .setContentText(context.getString(R.string.cast_approval_text, source))
                .setStyle(NotificationCompat.BigTextStyle().bigText(
                    context.getString(R.string.cast_approval_text, source)
                ))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setOngoing(true)
                .setAutoCancel(false)
                .apply {
                    if (contentPending != null) setContentIntent(contentPending)
                }
                .addAction(0, context.getString(R.string.cast_approval_allow), approvePending)
                .addAction(0, context.getString(R.string.cast_approval_deny), denyPending)
                .build()

            nm.notify(notificationId, notif)
        }
    }

    private fun cancelNotification(context: Context, notificationId: Int) {
        mainHandler.post {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return@post
            nm.cancel(notificationId)
        }
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.cast_approval_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.cast_approval_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }
}
