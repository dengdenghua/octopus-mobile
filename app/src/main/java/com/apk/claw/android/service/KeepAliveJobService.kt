package com.apk.claw.android.service

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.apk.claw.android.utils.XLog

/**
 * 定时守护 JobService
 * 每 15 分钟检查前台服务是否存活，若被杀则重新拉起
 */
class KeepAliveJobService : JobService() {

    companion object {
        private const val TAG = "KeepAliveJob"
        private const val JOB_ID = 10086
        private const val INTERVAL_MS = 15 * 60 * 1000L // 15 分钟

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
    }

    override fun onStartJob(params: JobParameters?): Boolean {
        val a11yRunning = ClawAccessibilityService.isRunning()
        val fgsRunning = ForegroundService.isRunning()
        XLog.i(TAG, "KeepAlive job triggered, A11y: $a11yRunning, ForegroundService: $fgsRunning")
        // 核心保活逻辑：
        // 1. 如果无障碍服务在运行——它自己已经是前台服务，进程已被保活，无需额外启动ForegroundService
        // 2. 如果无障碍服务没在运行（用户未开启或被系统杀死），才用ForegroundService保活进程等待重连
        if (!a11yRunning && !fgsRunning) {
            val started = ForegroundService.start(applicationContext)
            XLog.i(TAG, "Restarted ForegroundService (no a11y): $started")
        }
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        return true
    }
}
