package com.apk.claw.android.octopus_mobile.safety

import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.util.Log
import com.apk.claw.android.R
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

    // 有了可滚动弹窗容器后不必再狠截断——但仍设上限，防止异常巨大的参数值把弹窗渲染卡死。
    private const val MAX_VALUE_CHARS = 4000
    private const val MAX_TOTAL_CHARS = 6000

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
                val message = buildMessage(activityContext, toolName, params, riskDesc)

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

    /** 标签加粗、风险着色、参数值等宽字体——长 JS 代码也能在审批前看清楚。 */
    private fun buildMessage(
        context: Context,
        toolName: String,
        params: Map<String, Any>,
        riskDesc: String,
    ): CharSequence {
        val sb = SpannableStringBuilder()
        appendBold(sb, "工具: ")
        sb.append(toolName).append('\n')

        appendBold(sb, "风险: ")
        appendColored(context, sb, riskDesc, R.color.colorWarningOnContainer)
        sb.append('\n')

        if (params.isNotEmpty()) {
            appendBold(sb, "参数:\n")
            appendParams(sb, params)
        }

        sb.append("\n是否允许执行？")
        return sb
    }

    private fun appendParams(sb: SpannableStringBuilder, params: Map<String, Any>) {
        var remaining = MAX_TOTAL_CHARS
        for ((key, value) in params.entries.sortedBy { it.key }) {
            if (remaining <= 0) {
                sb.append("  …(其余参数已省略)\n")
                break
            }
            sb.append("  $key = ")
            val raw = if (ToolRiskPolicy.isSensitiveKey(key)) "<redacted>" else value.toString()
            val shownLen = minOf(raw.length, MAX_VALUE_CHARS, remaining)
            appendMonospace(sb, raw.take(shownLen))
            if (shownLen < raw.length) sb.append("…(已截断)")
            sb.append('\n')
            remaining -= shownLen
        }
    }

    private fun appendBold(sb: SpannableStringBuilder, text: String) {
        val start = sb.length
        sb.append(text)
        sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun appendColored(context: Context, sb: SpannableStringBuilder, text: String, colorRes: Int) {
        val start = sb.length
        sb.append(text)
        sb.setSpan(ForegroundColorSpan(context.getColor(colorRes)), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun appendMonospace(sb: SpannableStringBuilder, text: String) {
        val start = sb.length
        sb.append(text)
        sb.setSpan(TypefaceSpan("monospace"), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}
