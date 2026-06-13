package com.apk.claw.android.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.apk.claw.android.octopus_mobile.RoutineStore
import com.apk.claw.android.utils.XLog
import java.util.Calendar

/**
 * 例程定时器 —— 用 [AlarmManager] 在指定时刻唤醒 [RoutineAlarmReceiver] 重放例程。
 *
 * - 每条已定时的例程对应一个精确闹钟（requestCode = 例程 id 的 hashCode）。
 * - 每天重复：触发后由接收器再排下一天；仅一次：触发后清除定时。
 * - 开机后闹钟会被系统清空，需 [rescheduleAll]（[BootReceiver] 调用）重新注册。
 */
object RoutineScheduler {

    private const val TAG = "RoutineScheduler"
    const val ACTION = "com.apk.claw.android.RUN_ROUTINE"
    const val EXTRA_ID = "routine_id"

    /** 注册/更新一条例程的定时闹钟。 */
    fun schedule(ctx: Context, r: RoutineStore.Routine) {
        val h = r.scheduleHour ?: return
        val m = r.scheduleMinute ?: return
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val at = nextTrigger(h, m)
        val pi = pendingIntent(ctx, r.id)
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        try {
            if (canExact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
            XLog.i(TAG, "scheduled ${r.id} at $at (exact=$canExact, daily=${r.scheduleDaily})")
        } catch (e: SecurityException) {
            // 没有精确闹钟权限时退化为非精确
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            XLog.w(TAG, "exact alarm denied, fell back to inexact: ${e.message}")
        }
    }

    /** 取消一条例程的定时闹钟。 */
    fun cancel(ctx: Context, routineId: String) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent(ctx, routineId))
        XLog.i(TAG, "cancelled $routineId")
    }

    /** 开机后重新注册所有已定时的例程。 */
    fun rescheduleAll(ctx: Context) {
        RoutineStore.all().filter { it.isScheduled }.forEach { schedule(ctx, it) }
    }

    /** 下一次触发时刻：今天的 HH:MM 若已过则顺延到明天。 */
    private fun nextTrigger(hour: Int, minute: Int): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, hour)
        cal.set(Calendar.MINUTE, minute)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (cal.timeInMillis <= System.currentTimeMillis()) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }

    private fun pendingIntent(ctx: Context, routineId: String): PendingIntent {
        val intent = Intent(ctx, RoutineAlarmReceiver::class.java).apply {
            action = ACTION
            putExtra(EXTRA_ID, routineId)
        }
        return PendingIntent.getBroadcast(
            ctx, routineId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
