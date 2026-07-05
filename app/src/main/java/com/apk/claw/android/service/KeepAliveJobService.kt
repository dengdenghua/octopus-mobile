package com.apk.claw.android.service

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import com.apk.claw.android.utils.XLog

/**
 * 定时守护 JobService —— 每 15 分钟检查保活是否还在,掉了就恢复。
 *
 * 两类 job 共用本 Service,靠 jobId 区分:
 *  - [JOB_ID](周期):15 分钟一次的巡检。发现无障碍掉了 → 先试 Shizuku 自愈([A11ySelfHeal]),
 *    再(若前台服务也没了)调度一个**一次性 expedited 恢复 job**。
 *  - [RECOVERY_JOB_ID](一次性 expedited):真正去 `ForegroundService.start()`。
 *
 * 为什么恢复要单开 expedited job:周期 job 的 onStartJob 在后台上下文里,Android 12+ 直接从这里
 * 拉前台服务会被 `ForegroundServiceStartNotAllowedException` 拒(而周期 job 又不能设 expedited)。
 * expedited 一次性 job 运行在「前台可拉起前台服务」的宽限状态,是从后台恢复前台服务的正确姿势。
 * (Android 12 以下无此限制,periodic 里直接 start 也行,但走同一条恢复路径更省事、无副作用。)
 */
class KeepAliveJobService : JobService() {

    companion object {
        private const val TAG = "KeepAliveJob"
        private const val JOB_ID = 10086
        private const val RECOVERY_JOB_ID = 10087
        private const val INTERVAL_MS = 15 * 60 * 1000L // 15 分钟
        private const val RECOVERY_DEADLINE_MS = 5_000L

        fun schedule(context: Context) {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            if (scheduler.getPendingJob(JOB_ID) != null) return

            val jobInfo = JobInfo.Builder(JOB_ID, ComponentName(context, KeepAliveJobService::class.java))
                .setPeriodic(INTERVAL_MS)
                .setPersisted(true)
                .build()

            val result = scheduler.schedule(jobInfo)
            if (result == JobScheduler.RESULT_SUCCESS) {
                XLog.i(TAG, "KeepAlive job scheduled")
            } else {
                XLog.e(TAG, "KeepAlive job schedule failed")
            }
        }

        /**
         * 调度一次性恢复 job:优先 expedited(有拉前台服务的宽限窗口);expedited 配额耗尽被拒时
         * 退化为普通一次性 job(最小延迟)兜底,总比不重启强。
         */
        private fun scheduleRecovery(context: Context) {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            if (scheduler.getPendingJob(RECOVERY_JOB_ID) != null) return
            val comp = ComponentName(context, KeepAliveJobService::class.java)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val expedited = JobInfo.Builder(RECOVERY_JOB_ID, comp)
                    .setExpedited(true)
                    .build()
                if (scheduler.schedule(expedited) == JobScheduler.RESULT_SUCCESS) {
                    XLog.i(TAG, "recovery job scheduled (expedited)")
                    return
                }
                XLog.w(TAG, "expedited recovery rejected (quota?), fallback to plain one-off")
            }
            val plain = JobInfo.Builder(RECOVERY_JOB_ID, comp)
                .setMinimumLatency(0)
                .setOverrideDeadline(RECOVERY_DEADLINE_MS)
                .build()
            val ok = scheduler.schedule(plain) == JobScheduler.RESULT_SUCCESS
            XLog.i(TAG, "recovery job scheduled (plain one-off): $ok")
        }
    }

    override fun onStartJob(params: JobParameters?): Boolean {
        // 一次性恢复 job:在 FGS 宽限窗口里真正拉前台服务。
        if (params?.jobId == RECOVERY_JOB_ID) {
            val started = ForegroundService.start(applicationContext)
            XLog.i(TAG, "recovery job: ForegroundService.start = $started")
            return false
        }

        // 用 isConnected 不用 isRunning:巡检要的是「服务真活着」,enabled 列表回退会在
        // 服务已死但列表仍启用的窗口(如崩溃惩罚期)误判在跑,跳过自愈和恢复。
        val a11yRunning = ClawAccessibilityService.isConnected()
        val fgsRunning = ForegroundService.isRunning()
        XLog.i(TAG, "KeepAlive tick — a11y=$a11yRunning fgs=$fgsRunning")

        // 无障碍在跑 → 它自己已是前台服务,进程已保活,啥都不用做。掉了才恢复。
        if (!a11yRunning) {
            // 先试 Shizuku 自愈把无障碍写回启用(有 Shizuku 才成;成了它会自动重连并自托管前台)。
            val healed = runCatching { A11ySelfHeal.healIfNeeded(applicationContext) }.getOrDefault(false)
            if (healed) {
                XLog.i(TAG, "KeepAlive: requested a11y self-heal via shizuku")
            }
            // 无论自愈是否成功,只要当前前台服务也没了,就调度 expedited 恢复 job 保活进程,
            // 等无障碍重连(自愈成功场景)或纯前台服务苟着(无 Shizuku 场景)。
            if (!fgsRunning) {
                runCatching { scheduleRecovery(applicationContext) }
            }
        }
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        // 周期巡检被打断需系统重排;一次性恢复 job 不需要。
        return params?.jobId != RECOVERY_JOB_ID
    }
}
