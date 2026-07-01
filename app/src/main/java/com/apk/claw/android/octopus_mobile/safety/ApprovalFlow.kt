package com.apk.claw.android.octopus_mobile.safety

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.apk.claw.android.octopus_mobile.ToolAuditLog
import com.apk.claw.android.widget.ConfirmDialog
import com.blankj.utilcode.util.ActivityUtils
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object ApprovalFlow {

    private const val TAG = "ApprovalFlow"

    private const val DEFAULT_TIMEOUT_MS = 30_000L

    fun requestApproval(
        context: Context?,
        toolName: String,
        params: Map<String, Any>,
        riskDesc: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): Boolean {
        if (context == null) {
            Log.w(TAG, "context is null, auto-deny: $toolName")
            recordApproval(toolName, params, approved = false, reason = "no_context")
            return false
        }

        val approved = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        val mainHandler = Handler(Looper.getMainLooper())
        val dialogRef = AtomicReference<ConfirmDialog?>(null)
        val resolved = AtomicBoolean(false)

        val onAction: (Boolean) -> Unit = {
            if (resolved.compareAndSet(false, true)) {
                approved.set(true)
                latch.countDown()
            }
        }
        val onCancel: () -> Unit = {
            if (resolved.compareAndSet(false, true)) {
                approved.set(false)
                latch.countDown()
            }
        }

        mainHandler.post {
            try {
                val activityContext = ActivityUtils.getTopActivity() ?: context
                val displayParams = formatParamsForDisplay(params)
                val message = buildString {
                    appendLine("工具: $toolName")
                    appendLine("风险: $riskDesc")
                    if (displayParams.isNotEmpty()) {
                        appendLine("参数:")
                        appendLine(displayParams)
                    }
                    appendLine()
                    appendLine("是否允许执行？")
                }

                val dialog = ConfirmDialog.showWarm(
                    context = activityContext,
                    title = "⚠️ 高危操作审批",
                    message = message,
                    actionTitle = "允许执行",
                    cancelTitle = "拒绝",
                    isDismissible = false,
                    onAction = onAction,
                    onCancel = onCancel,
                )
                dialogRef.set(dialog)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to show approval dialog", e)
                if (resolved.compareAndSet(false, true)) {
                    approved.set(false)
                    latch.countDown()
                }
            }
        }

        return try {
            val completed = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            if (!completed) {
                if (resolved.compareAndSet(false, true)) {
                    Log.w(TAG, "Approval timed out after ${timeoutMs}ms, auto-deny: $toolName")
                    recordApproval(toolName, params, approved = false, reason = "timeout")
                    mainHandler.post {
                        runCatching { dialogRef.get()?.dismiss() }
                    }
                    false
                } else {
                    val result = approved.get()
                    recordApproval(toolName, params, approved = result,
                        reason = if (result) "user_approved" else "user_denied")
                    result
                }
            } else {
                val result = approved.get()
                recordApproval(toolName, params, approved = result,
                    reason = if (result) "user_approved" else "user_denied")
                result
            }
        } catch (e: InterruptedException) {
            Log.e(TAG, "Approval interrupted", e)
            if (resolved.compareAndSet(false, true)) {
                recordApproval(toolName, params, approved = false, reason = "interrupted")
                mainHandler.post {
                    runCatching { dialogRef.get()?.dismiss() }
                }
            }
            false
        }
    }

    private fun recordApproval(
        toolName: String,
        params: Map<String, Any>,
        approved: Boolean,
        reason: String,
    ) {
        runCatching {
            val paramsSummary = ToolRiskPolicy.summarizeParams(params)
            ToolAuditLog.record(
                ToolAuditLog.Entry(
                    id = "approval_${System.currentTimeMillis()}_$toolName",
                    ts = System.currentTimeMillis(),
                    toolName = toolName,
                    risk = ToolRiskPolicy.RISK_HIGH,
                    params = paramsSummary,
                    success = approved,
                    result = if (approved) "approved" else "denied",
                    blockedBy = if (approved) null else "approval_$reason",
                    durationMs = 0,
                )
            )
        }
    }

    private fun formatParamsForDisplay(params: Map<String, Any>): String {
        if (params.isEmpty()) return ""
        return params.entries
            .sortedBy { it.key }
            .joinToString("\n") { (key, value) ->
                val displayValue = if (ToolRiskPolicy.isSensitiveKey(key)) {
                    "<redacted>"
                } else {
                    value.toString().take(80)
                }
                "  $key = $displayValue"
            }
            .take(500)
    }
}
