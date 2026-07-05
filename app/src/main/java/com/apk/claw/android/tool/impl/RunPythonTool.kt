package com.apk.claw.android.tool.impl

import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolErr
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolRegistry
import com.apk.claw.android.tool.ToolResult

/**
 * Python 代码执行工具 —— 基于 Chaquopy 嵌入 CPython 3.11 解释器,在 App 进程内执行
 * Agent 生成的 Python 代码(无需 Shizuku,所有用户可用)。
 *
 * 适用场景:
 *  - 需要丰富标准库(json/re/math/datetime/os/time/collections 等)
 *  - 列表推导 / 装饰器 / 类继承 / 生成器等 JS 沙箱表达力不足的复杂逻辑
 *  - 数据处理 / 文本分析 / 算法原型
 *
 * 宿主 API(见 [PythonSandbox.PythonHostApi]):
 *  - print(…) / read_file / write_file / list_files / exists / mkdir / delete_file
 *  - fetch(url, opts?) — 同步 HTTP,返回 dict {status, ok, body}
 *  - call_tool(name, params?) — 调用已注册 Tool
 *  - md5 / sha256 / hmac_sha256 / base64_encode / base64_decode / uuid / now_ms
 *
 * 安全模型:登记 HIGH 风险 + NON_IDEMPOTENT(与 run_code 一致)。
 * 文件白名单、SSRF 防护、来源闸门与 run_code 完全一致。
 *
 * 限制:CPython 原生代码无法被 JVM 中断,超时后返回错误但解释器可能继续在后台跑
 * (单线程 executor 串行化,后续调用会排队等待)。单次输出 ≤ 64KB。
 */
class RunPythonTool : BaseTool() {

    companion object {
        private const val MAX_CODE_LEN = 100_000
        private const val DEFAULT_TIMEOUT_MS = 20_000L
        private const val MAX_TIMEOUT_MS = 60_000L
        private const val MIN_TIMEOUT_MS = 1_000L
    }

    override fun getName() = "run_python"
    override fun getDisplayName() = if (useChineseDescription) "运行Python" else "Run Python"

    override fun getParameters() = listOf(
        ToolParameter(
            "code", "string",
            "Python 3 code to execute in the embedded CPython sandbox. Standard library available " +
                "(json, re, math, datetime, os, time, collections). Host APIs: print, read_file, " +
                "write_file, list_files, exists, mkdir, delete_file, fetch, call_tool, md5, sha256, " +
                "hmac_sha256, base64_encode, base64_decode, uuid, now_ms.",
            true
        ),
        ToolParameter(
            "timeout_ms", "integer",
            "Max execution time in milliseconds. Default 20000, max 60000. " +
                "Note: CPython cannot be forcibly interrupted — on timeout the error returns but " +
                "the interpreter may still run in background.",
            false
        ),
    )

    @Suppress("ReturnCount")
    override fun execute(params: Map<String, Any>): ToolResult {
        val code = requireString(params, "code")
        if (code.length > MAX_CODE_LEN) {
            return ToolResult.error("代码过长（${code.length} > $MAX_CODE_LEN 字符）。")
        }
        val timeout = optionalLong(params, "timeout_ms", DEFAULT_TIMEOUT_MS)
            .coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
        // Python.start() 需要 Application Context —— 从 ToolRegistry 获取(由 ClawApplication 注入)。
        val context = ToolRegistry.getInstance().appContext
            ?: return ToolResult.error("Application context 未初始化,无法启动 Python。", ToolErr.INTERNAL)
        return PythonSandbox.execute(context, code, timeout, currentCancellationToken())
    }

    override fun getDescriptionEN() = """
        Execute Python 3 code in an embedded CPython 3.11 sandbox (via Chaquopy, no Shizuku required).
        Full standard library available (json, re, math, datetime, os, time, collections, etc.).

        Host APIs (call directly as global functions):
          - print(...) / read_file(path) / write_file(path, content) / list_files(path)
          - exists(path) / mkdir(path) / delete_file(path)
          - fetch(url, opts?) — sync HTTP, returns dict {status, ok, body} (SSRF-guarded)
          - call_tool(name, params?) — invoke a registered tool
          - md5(str) / sha256(str) / hmac_sha256(key, msg)
          - base64_encode(str) / base64_decode(str) / uuid() / now_ms()

        File access limited to Download/Documents. Same security model as run_code (HIGH risk,
        untrusted source gate applies to call_tool). Output ≤ 64KB per call.

        Limitation: CPython cannot be forcibly interrupted. On timeout, the error returns but
        the interpreter may continue running in background (serialized by single-thread executor).
        Timeout default 20s (max 60s). Code ≤ 100000 chars.
    """.trimIndent()

    override fun getDescriptionCN() = """
        在嵌入式 CPython 3.11 沙箱中执行 Python 3 代码(基于 Chaquopy,无需 Shizuku,所有用户可用)。
        完整标准库可用(json、re、math、datetime、os、time、collections 等)。

        宿主 API(作为全局函数直接调用):
          - print(...) / read_file(path) / write_file(path, content) / list_files(path)
          - exists(path) / mkdir(path) / delete_file(path)
          - fetch(url, opts?) — 同步 HTTP,返回 dict {status, ok, body}(SSRF 防护)
          - call_tool(name, params?) — 调用已注册工具
          - md5(str) / sha256(str) / hmac_sha256(key, msg)
          - base64_encode(str) / base64_decode(str) / uuid() / now_ms()

        文件访问限 Download/Documents。安全模型与 run_code 一致(HIGH 风险,call_tool 走不可信来源闸门)。
        单次输出 ≤ 64KB。

        限制:CPython 无法被强制中断,超时后返回错误但解释器可能继续在后台跑(单线程 executor 串行化)。
        超时默认 20 秒(上限 60 秒),代码 ≤ 100000 字符。
    """.trimIndent()
}
