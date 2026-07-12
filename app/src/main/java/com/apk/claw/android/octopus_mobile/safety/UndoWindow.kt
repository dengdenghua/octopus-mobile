@file:Suppress(
    "PackageNaming", "TooGenericExceptionCaught", "ReturnCount",
)   // octopus_mobile.safety 包(带下划线);弹窗失败兜底放行的宽 catch;守卫式提前 return

package com.apk.claw.android.octopus_mobile.safety

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.apk.claw.android.octopus_mobile.ToolAuditLog
import com.apk.claw.android.utils.KVUtils
import com.apk.claw.android.widget.ConfirmDialog
import com.blankj.utilcode.util.ActivityUtils
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 撤销窗口（Undo Window）—— 不可逆动作的"Gmail undo-send"式反悔窗。
 *
 * 与 [ApprovalFlow] 的关键区别:
 * - ApprovalFlow 是**阻塞式审批**(超时=拒绝),只在不可信来源触发,给"要不要放行"的把关。
 * - UndoWindow 是**非阻断式反悔**(超时=放行),补的是**本地在场用户**驱动不可逆动作的缺口:
 *   agent 默认继续执行(零确认摩擦),但先给 N 秒可撤销窗,误操作能在发出去前叫停。
 *
 * 适用面(由 [com.apk.claw.android.tool.ToolRegistry] 判定):不可逆动作 + 本地来源 + 有前台 Activity。
 * 无前台(无人值守/母体群控)或用户关闭本功能 → 直接放行,不改 FULL_POWER 语义。
 *
 * 抬的是"信任天花板":哪怕开了满血,发短信/发帖这类收不回的动作也有 catch 窗,用户才敢把自主权放开。
 */
object UndoWindow {

    private const val TAG = "UndoWindow"
    private const val DEFAULT_WINDOW_MS = 5_000L
    private const val MS_PER_SECOND = 1000L
    private const val LATCH_BUFFER_MS = 2_000L      // 等 latch 比窗口多留的缓冲
    private const val AUDIT_PARAM_MAX = 200         // 审计参数摘要截断长度

    /**
     * 给不可逆动作一个可撤销窗。返回 true=放行(默认/超时/立即执行),false=用户撤销。
     *
     * 前置:无前台 Activity 或功能关闭 → 立即 true(不打扰无人值守场景)。
     * 阻塞调用线程最多 windowMs + 缓冲;超时自动放行。
     */
    fun awaitOrProceed(
        toolName: String,
        actionDesc: String,
        windowMs: Long = DEFAULT_WINDOW_MS,
    ): Boolean {
        if (!KVUtils.isUndoWindowEnabled()) return true
        val activity = ActivityUtils.getTopActivity()
        if (activity == null) {
            // 无前台=无人在场看得到撤销窗=无人值守/远程,直接放行(保 FULL_POWER 语义)。
            return true
        }

        val latch = CountDownLatch(1)
        val proceed = AtomicBoolean(true)   // 默认放行
        val resolved = AtomicBoolean(false)
        val mainHandler = Handler(Looper.getMainLooper())

        val resolve: (Boolean) -> Unit = { go ->
            if (resolved.compareAndSet(false, true)) {
                proceed.set(go)
                latch.countDown()
            }
        }

        val seconds = (windowMs / MS_PER_SECOND).coerceAtLeast(1)
        val message = "AI 即将执行不可逆操作:\n$actionDesc\n\n$seconds 秒后自动执行。如为误操作,请点「撤销」。"

        mainHandler.post {
            try {
                val ctx = ActivityUtils.getTopActivity() ?: activity
                val dialog = ConfirmDialog.showWarm(
                    context = ctx,
                    title = "⏳ 即将执行 · 可撤销",
                    message = message,
                    actionTitle = "立即执行",
                    cancelTitle = "撤销",
                    isDismissible = false,
                    onAction = { resolve(true) },   // 立即执行
                    onCancel = { resolve(false) },  // 撤销
                )
                // 到点自动放行:关掉弹窗并按"放行"结算。
                mainHandler.postDelayed({
                    if (!resolved.get()) {
                        runCatching { dialog.dismiss() }
                        resolve(true)
                    }
                }, windowMs)
            } catch (e: Exception) {
                Log.e(TAG, "show undo dialog failed, proceed by default", e)
                resolve(true)
            }
        }

        runCatching { latch.await(windowMs + LATCH_BUFFER_MS, TimeUnit.MILLISECONDS) }
        val go = proceed.get()
        recordAudit(toolName, actionDesc, go)
        return go
    }

    private fun recordAudit(toolName: String, actionDesc: String, proceeded: Boolean) {
        runCatching {
            ToolAuditLog.record(
                ToolAuditLog.Entry(
                    id = "undo_${System.currentTimeMillis()}_$toolName",
                    ts = System.currentTimeMillis(),
                    toolName = toolName,
                    risk = ToolRiskPolicy.RISK_HIGH,
                    params = actionDesc.take(AUDIT_PARAM_MAX),
                    success = proceeded,
                    result = if (proceeded) "proceeded" else "undone",
                    blockedBy = if (proceeded) null else "undo_window",
                    durationMs = 0,
                )
            )
        }
    }
}
