package com.apk.claw.android.tool.impl

import com.apk.claw.android.ClawApplication
import com.apk.claw.android.octopus_mobile.codeexec.QuickJsRuntime
import com.apk.claw.android.shizuku.ShizukuManager
import com.apk.claw.android.shizuku.ShizukuShellService
import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult
import java.io.File

/**
 * 代码执行工具 —— 在设备上的 QuickJS **纯计算沙箱**里跑 Agent 生成的 JavaScript。
 *
 * 安全模型(详见 [[octopus-code-exec-feature]]):
 *  - 登记为 HIGH 风险 → 自动走 [com.apk.claw.android.tool.ToolRegistry] 的高危来源闸门
 *    (不可信来源弹 ApprovalFlow 确认)+ 全程审计。无需本工具自己加闸门。
 *  - runner 编译期砍掉 os/std:JS 只能纯计算(Math/JSON/Date/正则/字符串),**碰不到文件、
 *    进程、网络**。即便 LLM 生成 `os.exec(...)` 也是 ReferenceError。
 *  - 经 Shizuku 以 shell UID 执行;脚本以**文件**传入 runner,内容从不上命令行 → 零 shell 注入。
 *  - Shizuku-only:未授权则直接报错引导(这是设计上的「高级功能」边界)。
 */
class RunCodeTool : BaseTool() {

    companion object {
        private const val MAX_CODE_LEN = 100_000
        private const val DEFAULT_TIMEOUT_MS = 10_000L
        private const val MAX_TIMEOUT_MS = 30_000L
    }

    override fun getName(): String = "run_code"

    override fun getDisplayName(): String = if (useChineseDescription) "运行代码" else "Run Code"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter(
            "code",
            "string",
            "JavaScript source to run in a sandboxed QuickJS engine. Pure computation only: NO file, network, or process access. Use console.log(...) to print results (that stdout is the tool's return value).",
            true
        ),
        ToolParameter(
            "timeout_ms",
            "integer",
            "Optional: max execution time in milliseconds. Default 10000, max 30000.",
            false
        )
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        if (!ShizukuManager.isAvailable()) {
            return ToolResult.error("run_code 需要 Shizuku 高级权限(当前未授权)。请在设置中授权 Shizuku 后重试。")
        }
        if (!QuickJsRuntime.isSupportedAbi()) {
            return ToolResult.error("当前设备 CPU 架构暂不支持代码执行(目前仅支持 arm64-v8a)。")
        }

        val code = requireString(params, "code")
        if (code.length > MAX_CODE_LEN) {
            return ToolResult.error("代码过长(${code.length} > $MAX_CODE_LEN 字符)。")
        }
        val timeout = optionalLong(params, "timeout_ms", DEFAULT_TIMEOUT_MS)
            .coerceIn(1000L, MAX_TIMEOUT_MS)

        if (!QuickJsRuntime.ensureReady()) {
            return ToolResult.error("代码执行运行时未就绪(Shizuku 不可用或运行时安装失败)。")
        }

        // 脚本写入 App 外部目录(App 可写、shell 经 ext_data_rw 可读)→ Shizuku cp 进沙箱。
        // 注意:脚本内容是文件,从不进命令行;cp/runQuickJs 只处理固定路径。
        val ctx = ClawApplication.instance
        val jobName = "job_${System.currentTimeMillis()}.js"
        val extJs = File(ctx.getExternalFilesDir(null), "octopus/$jobName")
        val tmpJs = "${ShizukuShellService.RUNTIME_DIR}/$jobName"
        return try {
            extJs.parentFile?.mkdirs()
            extJs.writeText(code)

            val copied = ShizukuShellService.copyFile(extJs.absolutePath, tmpJs)
            if (copied != true) {
                return ToolResult.error("脚本暂存到沙箱失败(Shizuku 不可用或权限不足)。")
            }

            val r = ShizukuShellService.runQuickJs(QuickJsRuntime.RUNNER_PATH, tmpJs, timeout)
                ?: return ToolResult.error("Shizuku 不可用,无法执行。")

            if (r.exitCode == 0) {
                val out = r.stdout.trimEnd()
                ToolResult.success(if (out.isBlank()) "(执行成功,无输出)" else out)
            } else {
                val err = r.stderr.ifBlank { r.stdout }.trimEnd()
                ToolResult.error("代码执行出错(exit=${r.exitCode}):\n$err")
            }
        } catch (e: Exception) {
            ToolResult.error("代码执行异常: ${e.message}")
        } finally {
            runCatching { extJs.delete() }
            runCatching { ShizukuShellService.deleteFile(tmpJs) }
        }
    }

    override fun getDescriptionEN(): String = """
        Execute JavaScript in a sandboxed QuickJS engine on the device (requires Shizuku).
        The sandbox is PURE COMPUTE: full ECMAScript (Math, JSON, Date, RegExp, string/array ops,
        Promise) but NO access to files, network, processes, or the device — by design.
        Use console.log(...) to emit results; the captured stdout becomes the tool result.

        Use this for: calculations, data transforms/parsing, algorithm logic, JSON wrangling,
        text processing — anything that is pure computation over inputs you pass inline in the code.

        Limits: code <= 100000 chars, timeout default 10s (max 30s), output capped at 64KB.
        Example: code = "const a=[3,1,2]; console.log(JSON.stringify(a.sort()))"
    """.trimIndent()

    override fun getDescriptionCN(): String = """
        在设备上的 QuickJS 沙箱里执行 JavaScript(需要 Shizuku 授权)。
        沙箱是「纯计算」:完整 ECMAScript(Math/JSON/Date/正则/字符串数组/Promise),
        但**刻意禁止**访问文件、网络、进程或设备。用 console.log(...) 输出结果,
        其 stdout 即为工具返回值。

        适用:计算、数据转换/解析、算法逻辑、JSON 处理、文本处理——任何对内联输入的纯计算。

        限制:代码 ≤ 100000 字符,超时默认 10 秒(上限 30 秒),输出上限 64KB。
        示例:code = "const a=[3,1,2]; console.log(JSON.stringify(a.sort()))"
    """.trimIndent()
}
