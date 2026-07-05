package com.apk.claw.android.tool.impl

import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult

/**
 * 会话式代码执行工具 —— 在 Rhino JS 引擎（JVM 内）执行 Agent 生成的脚本，同一 session_id
 * 复用 scope，变量/函数定义跨多次执行持久。
 *
 * 适用场景：分步调试（先定义函数，再逐步调用）、多轮构建（累积状态）、长任务拆分。
 *
 * 宿主 API 与 [RunCodeTool] 完全一致（print/readFile/writeFile/listFiles/mkdir/deleteFile/
 * exists/fetch/callTool/callToolAsync/crypto/datetime/uuid/setTimeout/…），见 [ScriptSandbox]。
 *
 * 安全模型：登记为 HIGH 风险 + NON_IDEMPOTENT（变量持久 → 同一调用可能在不同次执行产生不同效果）。
 * 会话 30 分钟无访问自动重建（TTL），最多 20 个并发会话（LRU 淘汰）。见 [ScriptSandbox.executeInSession]。
 */
class RunCodeSessionTool : BaseTool() {

    companion object {
        private const val MAX_CODE_LEN = 100_000
        private const val DEFAULT_TIMEOUT_MS = 20_000L
        private const val MAX_TIMEOUT_MS = 60_000L
        private const val MIN_TIMEOUT_MS = 1_000L
        private const val MAX_SESSION_ID_LEN = 128
    }

    override fun getName() = "run_code_session"
    override fun getDisplayName() = if (useChineseDescription) "运行代码(会话)" else "Run Code (Session)"

    override fun getParameters() = listOf(
        ToolParameter(
            "session_id", "string",
            "Stable session identifier. Reusing the same id shares variables/functions across calls " +
                "(persisted in scope). Use a unique id per logical task; call run_code_reset to release.",
            true
        ),
        ToolParameter(
            "code", "string",
            "JavaScript to execute in the sandboxed Rhino engine. Same host APIs as run_code " +
                "(print, readFile, writeFile, fetch, callTool, crypto, datetime, uuid, timers, callToolAsync). " +
                "Variables and function definitions persist across calls with the same session_id.",
            true
        ),
        ToolParameter(
            "timeout_ms", "integer",
            "Max execution time in milliseconds. Default 20000, max 60000.",
            false
        ),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val sessionId = requireString(params, "session_id")
        val code = requireString(params, "code")
        val validationError = when {
            sessionId.isBlank() -> "session_id 不能为空。"
            sessionId.length > MAX_SESSION_ID_LEN ->
                "session_id 过长（${sessionId.length} > $MAX_SESSION_ID_LEN 字符）。"
            code.length > MAX_CODE_LEN ->
                "代码过长（${code.length} > $MAX_CODE_LEN 字符）。"
            else -> null
        }
        if (validationError != null) return ToolResult.error(validationError)
        val timeout = optionalLong(params, "timeout_ms", DEFAULT_TIMEOUT_MS)
            .coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
        // ScriptSandbox 为 object 单例,会话状态跨工具共享。当前任务取消令牌传入以支持中断。
        return ScriptSandbox.executeInSession(sessionId, code, timeout, currentCancellationToken())
    }

    override fun getDescriptionEN() = """
        Execute JavaScript in a Rhino sandbox with a persistent session (no Shizuku required).
        Same host APIs as run_code (print, readFile, writeFile, fetch, callTool, crypto, datetime,
        uuid, setTimeout/setInterval, callToolAsync).

        Key difference from run_code: variables and function definitions persist across calls
        that share the same session_id. Use for multi-step debugging and iterative building
        (define a function in one call, invoke it in the next).

        Sessions auto-expire after 30 min of inactivity; at most 20 concurrent sessions (LRU).
        Call run_code_reset to explicitly release a session when done.

        Timeout default 20s (max 60s). Code ≤ 100000 chars. Output ≤ 64KB per call.
        session_id ≤ 128 chars. Note: async/await syntax is NOT supported — use Promise + .then().
    """.trimIndent()

    override fun getDescriptionCN() = """
        在 Rhino 沙箱内执行 JavaScript,支持会话持久（无需 Shizuku，所有用户可用）。
        宿主 API 与 run_code 完全一致（print、readFile、writeFile、fetch、callTool、crypto、
        datetime、uuid、setTimeout/setInterval、callToolAsync）。

        与 run_code 的区别:同一 session_id 的多次调用共享 scope,变量和函数定义跨调用持久。
        适合分步调试与多轮构建（第一次定义函数,第二次调用）。

        会话 30 分钟无访问自动过期;最多 20 个并发会话(LRU 淘汰)。
        完成后调用 run_code_reset 显式释放会话。

        超时默认 20 秒（上限 60 秒），代码 ≤ 100000 字符，单次输出 ≤ 64KB。
        session_id ≤ 128 字符。注意:不支持 async/await 语法,请用 Promise + .then()。
    """.trimIndent()
}
