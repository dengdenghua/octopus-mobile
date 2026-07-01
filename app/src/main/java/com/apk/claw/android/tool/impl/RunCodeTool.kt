package com.apk.claw.android.tool.impl

import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult

/**
 * 代码执行工具 —— 在 Rhino JS 引擎（JVM 内）执行 Agent 生成的脚本。
 *
 * 宿主 API（脚本直接调用）:
 *  - print(…) / console.log(…)   → 输出到工具结果
 *  - readFile(path)               → 读文件（限 Download/Documents）
 *  - writeFile(path, content)     → 写文件（同上）
 *  - fetch(url, options?)         → 同步 HTTP，返回 {status, ok, body}
 *  - callTool(name, params?)      → 调用任意已注册工具
 *
 * 安全模型：登记为 HIGH 风险 → ToolRegistry 高危来源闸门自动拦截不可信来源；
 * 沙箱本身不可调用系统 shell，文件访问限定安全路径，超时强制中断。
 */
class RunCodeTool : BaseTool() {

    companion object {
        private const val MAX_CODE_LEN = 100_000
        private const val DEFAULT_TIMEOUT_MS = 20_000L
        private const val MAX_TIMEOUT_MS = 60_000L
    }

    private val sandbox = ScriptSandbox()

    override fun getName() = "run_code"
    override fun getDisplayName() = if (useChineseDescription) "运行代码" else "Run Code"

    override fun getParameters() = listOf(
        ToolParameter(
            "code", "string",
            "JavaScript to execute in a sandboxed Rhino engine on the device. " +
            "Built-in host APIs: print()/console.log() for output; readFile(path)/writeFile(path,content) " +
            "for file I/O (Download and Documents only); fetch(url, options?) for HTTP; " +
            "callTool(name, params) to call any registered device tool. " +
            "All execution is synchronous. Use print() or console.log() to emit results.",
            true
        ),
        ToolParameter(
            "timeout_ms", "integer",
            "Max execution time in milliseconds. Default 20000, max 60000.",
            false
        ),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val code = requireString(params, "code")
        if (code.length > MAX_CODE_LEN)
            return ToolResult.error("代码过长（${code.length} > $MAX_CODE_LEN 字符）。")
        val timeout = optionalLong(params, "timeout_ms", DEFAULT_TIMEOUT_MS)
            .coerceIn(1_000L, MAX_TIMEOUT_MS)
        return sandbox.execute(code, timeout)
    }

    override fun getDescriptionEN() = """
        Execute JavaScript on the device using a Rhino engine (no Shizuku required).
        Built-in host APIs available in the script:
          print("msg") / console.log("msg")    — captured output becomes the tool result
          readFile("/sdcard/Download/foo.txt")  — read a file (Download/Documents only)
          writeFile("/sdcard/Download/out.txt", "content") — write a file
          fetch("https://api.example.com/data", {method:"POST", body:"...", headers:{...}})
              — synchronous HTTP request, returns {status, ok, body}
          callTool("tap", {x:500, y:300})       — call any registered device tool
          callTool("input_text", {text:"hello"})
          callTool("take_screenshot", {})

        Use for: data transforms, file processing, API calls, UI automation scripts,
        calculations, text manipulation, multi-step device interactions.
        Timeout default 20s (max 60s). Code ≤ 100000 chars. Output ≤ 64KB.
    """.trimIndent()

    override fun getDescriptionCN() = """
        在设备上用 Rhino 引擎执行 JavaScript（无需 Shizuku，所有用户可用）。
        脚本内置宿主 API:
          print("msg") / console.log("msg")       → 输出捕获，即工具返回值
          readFile("/sdcard/Download/foo.txt")     → 读文件（限 Download/Documents 目录）
          writeFile("/sdcard/Download/out.txt", "内容") → 写文件
          fetch("https://api.example.com/data", {method:"POST", body:"...", headers:{...}})
              → 同步 HTTP 请求，返回 {status, ok, body}
          callTool("tap", {x:500, y:300})          → 调用已注册设备工具
          callTool("input_text", {text:"你好"})
          callTool("take_screenshot", {})

        适用：数据处理、文件读写、接口调用、UI 自动化脚本、计算、多步设备交互。
        超时默认 20 秒（上限 60 秒），代码 ≤ 100000 字符，输出 ≤ 64KB。
    """.trimIndent()
}
