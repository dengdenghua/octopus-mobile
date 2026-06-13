package com.apk.claw.android.service

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.apk.claw.android.R
import com.apk.claw.android.octopus_mobile.RoutineStore
import com.apk.claw.android.ui.compose.screen.RoutineRunner
import com.apk.claw.android.utils.XLog

/**
 * 例程定时到点接收器 —— [RoutineScheduler] 注册的闹钟触发此处。
 *
 * 流程：拉起前台服务保活 → 处理续期（每天重排 / 一次性清除）→ 满足条件则重放例程，
 * 否则发通知告知用户被跳过。
 */
class RoutineAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "RoutineAlarmReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(RoutineScheduler.EXTRA_ID) ?: return
        val r = RoutineStore.all().find { it.id == id } ?: return
        XLog.i(TAG, "alarm fired for routine ${r.id} (${r.name})")

        // 保活：从后台执行长任务，先确保前台服务在跑
        runCatching { ForegroundService.start(context) }

        // 续期：每天重复→排下一天；仅一次→清除定时
        if (r.scheduleDaily) {
            runCatching { RoutineScheduler.schedule(context, r) }
        } else {
            RoutineStore.update(r.copy(scheduleHour = null, scheduleMinute = null, scheduleDaily = false))
        }

        // 执行
        if (RoutineRunner.canRun()) {
            val status = RoutineRunner.run(context, r)
            notify(context, r.id.hashCode(), "正在运行例程", "${r.name}\n$status")
        } else {
            notify(context, r.id.hashCode(), "例程已跳过", "「${r.name}」到点，但未配置模型")
        }
    }

    private fun notify(ctx: Context, id: Int, title: String, text: String) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // 复用前台服务的通知渠道（若尚未创建，前台服务启动时会创建）
        val n = NotificationCompat.Builder(ctx, ForegroundService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(id, n) }
    }
}
