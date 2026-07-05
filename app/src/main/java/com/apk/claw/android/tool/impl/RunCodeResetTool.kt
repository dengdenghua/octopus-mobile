package com.apk.claw.android.tool.impl

import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult

/**
 * 会话重置工具 —— 销毁指定会话的 scope,释放持久状态（变量/函数定义/定时器）。
 *
 * 本身无外部副作用（不改设备/文件/网络），但会丢弃会话内累积的全部状态,故登记为 MEDIUM 风险
 * （纳入审计,便于排查「为何我的会话状态没了」）。返回 {reset: true|false} 表示该会话是否曾存在。
 *
 * 见 [ScriptSandbox.resetSession]。
 */
class RunCodeResetTool : BaseTool() {

    companion object {
        private const val MAX_SESSION_ID_LEN = 128
    }

    override fun getName() = "run_code_reset"
    override fun getDisplayName() = if (useChineseDescription) "重置代码会话" else "Reset Code Session"

    override fun getParameters() = listOf(
        ToolParameter(
            "session_id", "string",
            "Session identifier to destroy. Returns {reset: true} if it existed, {reset: false} otherwise.",
            true
        ),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val sessionId = requireString(params, "session_id")
        val validationError = when {
            sessionId.isBlank() -> "session_id 不能为空。"
            sessionId.length > MAX_SESSION_ID_LEN ->
                "session_id 过长（${sessionId.length} > $MAX_SESSION_ID_LEN 字符）。"
            else -> null
        }
        if (validationError != null) return ToolResult.error(validationError)
        val existed = ScriptSandbox.resetSession(sessionId)
        return ToolResult.success("{\"reset\": $existed}")
    }

    override fun getDescriptionEN() = """
        Destroy a persistent code session, releasing its scope and all accumulated state
        (variables, function definitions, timers). Use when a multi-step task is complete
        and the session no longer needed.

        Returns {reset: true} if the session existed, {reset: false} otherwise.
        No effect on device/files/network — only in-memory sandbox state.
    """.trimIndent()

    override fun getDescriptionCN() = """
        销毁一个持久代码会话,释放其 scope 及累积的全部状态(变量、函数定义、定时器)。
        当多步任务完成、会话不再需要时调用。

        返回 {reset: true} 表示会话曾存在,{reset: false} 表示不存在。
        不影响设备/文件/网络 —— 仅清理内存中的沙箱状态。
    """.trimIndent()
}
