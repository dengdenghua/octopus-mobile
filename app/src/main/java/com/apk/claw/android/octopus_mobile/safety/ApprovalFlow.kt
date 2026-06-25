package com.apk.claw.android.octopus_mobile.safety

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.apk.claw.android.octopus_mobile.ToolAuditLog
import com.apk.claw.android.widget.ConfirmDialog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 审批流程 —— APPROVAL 模式下高危工具调用的人工确认闭环。
 *
 * 工作流：
 * 1. 工具调用线程调用 [requestApproval]（阻塞）
 * 2. 主线程弹出 [ConfirmDialog] 显示工具名、参数、风险说明
 * 3. 用户点击"允许"或"拒绝"，或超时自动拒绝（默认 30s）
 * 4. 审批结果记录到 [ToolAuditLog]
 * 5. [requestApproval] 返回审批结果，工具调用线程继续
 *
 * 用法（在 ToolRegistry 中）：
 * ```kotlin
 * val approved = ApprovalFlow.requestApproval(context, toolName, params, riskDesc)
 * if (!approved) return ToolResult.error("用户拒绝执行高危工具")
 * ```
 */
object ApprovalFlow {

    private const val TAG = "ApprovalFlow"

    /** 审批超时时间（毫秒），超时自动拒绝 */
    private const val DEFAULT_TIMEOUT_MS = 30_000L

    /**
     * 请求人工审批高危工具调用（阻塞调用，需在后台线程执行）。
     *
     * @param context Android Context（用于弹窗，若为 null 直接返回 false）
     * @param toolName 工具名称
     * @param params 工具参数（已脱敏）
     * @param riskDesc 风险说明
     * @param timeoutMs 超时时间，默认 30s
     * @return true=用户允许，false=用户拒绝或超时
     */
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

        // 切到主线程弹窗
        mainHandler.post {
            try {
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

                ConfirmDialog.showWarm(
                    context = context,
                    title = "⚠️ 高危操作审批",
                    message = message,
                    actionTitle = "允许执行",
                    cancelTitle = "拒绝",
                    isDismissible = false,
                    onAction = {
                        approved.set(true)
                        latch.countDown()
                    },
                    onCancel = {
                        approved.set(false)
                        latch.countDown()
                    },
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to show approval dialog", e)
                approved.set(false)
                latch.countDown()
            }
        }

        // 阻塞等待结果或超时
        return try {
            val completed = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            if (!completed) {
                Log.w(TAG, "Approval timed out after ${timeoutMs}ms, auto-deny: $toolName")
                // 超时后需要关闭弹窗（如果还在显示）
                mainHandler.post {
                    runCatching {
                        // ConfirmDialog 无法直接 dismiss，依赖 Activity 销毁
                        // 实际场景中超时通常意味着 Activity 已不可见
                    }
                }
                recordApproval(toolName, params, approved = false, reason = "timeout")
                false
            } else {
                val result = approved.get()
                recordApproval(toolName, params, approved = result, reason = if (result) "user_approved" else "user_denied")
                result
            }
        } catch (e: InterruptedException) {
            Log.e(TAG, "Approval interrupted", e)
            recordApproval(toolName, params, approved = false, reason = "interrupted")
            false
        }
    }

    /** 记录审批结果到审计日志 */
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

    /** 格式化参数用于显示（限制长度，避免弹窗过长） */
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
