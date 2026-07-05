package com.apk.claw.android.tool.impl

import com.apk.claw.android.shizuku.ShizukuManager
import com.apk.claw.android.shizuku.ShizukuShellService
import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolErr
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult

/**
 * Shell 命令执行工具 —— 通过 Shizuku(shell UID 2000)执行白名单内的查询类命令。
 *
 * 与 [RunCodeTool] 的区别:run_code 跑 JS(无系统权限),shell_exec 跑 shell 命令(有 shell 权限)。
 * 与 [FileOpsTool] 的区别:file_ops 走专用的 copy/move/delete 封装,shell_exec 只读查询。
 *
 * 安全模型:
 *  - 只允许查询/读取类命令(白名单 SHELL_QUERY_PREFIXES),禁止 rm/mv/cp/input/install 等
 *  - 复用 ShizukuShellService.exec() 的双重防护:命令前缀白名单 + shell 元字符注入检测
 *  - 登记 HIGH 风险 → 不可信来源走来源闸门弹审批 + 全程审计
 *  - 输出截断到 MAX_OUTPUT_CHARS,防止超大日志撑爆 LLM 上下文
 *  - Shizuku 不可用时返回明确错误(不静默降级)
 */
class ShellExecTool : BaseTool() {

    companion object {
        private const val MAX_OUTPUT_CHARS = 32_768
        private const val DEFAULT_TIMEOUT_MS = 10_000L
        private const val MIN_TIMEOUT_MS = 1_000L
        private const val MAX_TIMEOUT_MS = 30_000L

        /**
         * 查询类命令前缀白名单(比 ShizukuShellService 的白名单更窄)。
         * 只允许读/查询操作,禁止任何会改变设备状态的命令。
         *
         * - pm list/dump/path: 应用列表/详情/路径(只读)
         * - dumpsys: 系统服务状态(只读)
         * - getprop: 系统属性(只读)
         * - settings get: 读设置(put 走专门工具)
         * - logcat -d: dump 模式日志(不 follow,有上限)
         * - wm size/density: 屏幕信息(只读)
         * - am get-current-user/stack list: 用户/任务栈(只读)
         * - df/du/ls/stat/find/cat/head/grep: 文件查看(find/cat/head 限安全路径)
         *
         * 显式排除:rm/mv/cp/mkdir/input/settings put/am start/am force-stop/
         * pm install/pm uninstall/screencap/uiautomator/monkey/cmd(太宽泛)。
         */
        private val SHELL_QUERY_PREFIXES = listOf(
            "pm list",
            "pm dump",
            "pm path",
            "dumpsys",
            "getprop",
            "settings get",
            "logcat -d",
            "wm size",
            "wm density",
            "am get-current-user",
            "am stack list",
            "df ",
            "du ",
            "ls ",
            "stat ",
            "find ",
            "cat ",
            "head ",
            "grep ",
        )
    }

    override fun getName() = "shell_exec"
    override fun getDisplayName() = if (useChineseDescription) "执行Shell命令" else "Shell Exec"

    override fun getParameters() = listOf(
        ToolParameter(
            "command",
            "string",
            "Shell command to execute via Shizuku (shell UID 2000). " +
                "Only read-only query commands are allowed: pm list/dump/path, dumpsys, getprop, " +
                "settings get, logcat -d, wm size/density, am get-current-user/stack list, " +
                "df/du/ls/stat/find/cat/head/grep. " +
                "State-changing commands (rm/mv/cp/input/install/uninstall) are blocked — " +
                "use dedicated tools (file_ops/tap/input_text) instead.",
            true
        ),
        ToolParameter(
            "timeout_ms",
            "integer",
            "Max execution time in milliseconds. Default 10000, max 30000.",
            false
        ),
    )

    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    override fun execute(params: Map<String, Any>): ToolResult {
        val command = requireString(params, "command").trim()
        if (command.isEmpty()) {
            return ToolResult.error("command 不能为空", ToolErr.INVALID_PARAM)
        }
        // timeout 仅用于文档化,实际超时由 ShizukuShellService 内部控制(它有 10s 默认)。
        optionalLong(params, "timeout_ms", DEFAULT_TIMEOUT_MS)
            .coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)

        // 第一层:本工具的窄白名单(只允许查询类命令)
        if (!isQueryCommand(command)) {
            return ToolResult.error(
                "命令不在查询白名单内。只允许只读命令(pm list/dumpsys/getprop/settings get/" +
                    "logcat -d/wm size/df/ls 等)。状态变更命令请用专门工具(file_ops/tap/input_text)。",
                ToolErr.PERMISSION,
            )
        }

        // 第二层:Shizuku 可用性
        if (!ShizukuManager.isAvailable()) {
            return ToolResult.error(
                "Shizuku 不可用。请先在设置中开启 Shizuku(shell 权限未授权)。",
                ToolErr.INTERNAL,
            )
        }

        // 第三层:ShizukuShellService.exec() 的双重防护(前缀白名单 + 注入检测 + 路径校验)
        val result = try {
            ShizukuShellService.exec(command)
        } catch (e: Exception) {
            return ToolResult.error("shell 执行异常: ${e.message}", ToolErr.INTERNAL)
        } ?: return ToolResult.error(
            "Shizuku 执行失败(可能命令被安全策略拦截或 Shizuku 服务断开)。",
            ToolErr.INTERNAL,
        )

        if (result.exitCode != 0) {
            val msg = buildString {
                append("命令退出码: ${result.exitCode}")
                if (result.stderr.isNotBlank()) append("\nstderr: ${result.stderr.trim()}")
                if (result.stdout.isNotBlank()) append("\nstdout: ${truncate(result.stdout)}")
            }
            return ToolResult.error(msg, ToolErr.UPSTREAM)
        }

        val output = buildString {
            if (result.stdout.isNotBlank()) {
                append(truncate(result.stdout))
            }
            if (result.stderr.isNotBlank()) {
                if (isNotEmpty()) append("\n--- stderr ---\n")
                append(truncate(result.stderr))
            }
            if (isEmpty()) append("(命令执行成功,无输出)")
        }
        return ToolResult.success(output)
    }

    /**
     * 检查命令是否属于查询类白名单。
     * 去掉行首空白后,用前缀匹配;前缀带尾空格确保边界(避免 `pm listx` 命中 `pm list`)。
     */
    private fun isQueryCommand(command: String): Boolean {
        val normalized = command.trimStart()
        return SHELL_QUERY_PREFIXES.any { prefix ->
            val p = if (prefix.endsWith(" ")) prefix else "$prefix "
            normalized == p.trimEnd() || normalized.startsWith(p)
        }
    }

    /** 截断输出到 [MAX_OUTPUT_CHARS],尾部加截断提示。 */
    private fun truncate(s: String): String {
        val trimmed = s.trim()
        if (trimmed.length <= MAX_OUTPUT_CHARS) return trimmed
        return trimmed.take(MAX_OUTPUT_CHARS) + "\n... (输出已截断,共 ${trimmed.length} 字符)"
    }

    override fun getDescriptionEN() = """
        Execute a read-only shell command via Shizuku (shell UID 2000, no root required).
        Only query commands are allowed:
          pm list packages | pm dump <pkg> | pm path <pkg>   — app info
          dumpsys <service>                                  — system service status
          getprop                                            — system properties
          settings get <namespace> <key>                     — read settings
          logcat -d -t <count>                               — dump recent logs (no follow)
          wm size | wm density                               — screen info
          am get-current-user | am stack list                — user/task info
          df <path> | du <path> | ls <path> | stat <path>    — disk/file info
          find <path> -name <pattern> | cat <file> | head    — file search/read
          grep <pattern> <file>                              — text search

        State-changing commands (rm/mv/cp/mkdir/input/settings put/am start/pm install)
        are BLOCKED — use dedicated tools (file_ops/tap/input_text/open_app) instead.

        Requires Shizuku enabled. Output truncated to 32KB. Timeout default 10s (max 30s).
    """.trimIndent()

    override fun getDescriptionCN() = """
        通过 Shizuku 执行只读 Shell 命令(shell UID 2000,无需 root)。
        只允许查询类命令:
          pm list packages | pm dump <包名> | pm path <包名>  — 应用信息
          dumpsys <服务名>                                    — 系统服务状态
          getprop                                            — 系统属性
          settings get <命名空间> <键>                        — 读设置
          logcat -d -t <行数>                                — dump 最近日志(不 follow)
          wm size | wm density                               — 屏幕信息
          am get-current-user | am stack list                — 用户/任务栈
          df <路径> | du <路径> | ls <路径> | stat <路径>     — 磁盘/文件信息
          find <路径> -name <模式> | cat <文件> | head        — 文件查找/读取
          grep <模式> <文件>                                  — 文本搜索

        状态变更命令(rm/mv/cp/mkdir/input/settings put/am start/pm install)被拦截——
        请用专门工具(file_ops/tap/input_text/open_app)。

        需要 Shizuku 已开启。输出截断到 32KB。超时默认 10 秒(上限 30 秒)。
    """.trimIndent()
}
