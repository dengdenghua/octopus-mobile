package com.apk.claw.android.tool.impl

import com.apk.claw.android.octopus_mobile.safety.PathGuard
import com.apk.claw.android.shizuku.ShizukuShellService
import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult

/**
 * 文件浏览工具 —— AI NAS 核心功能。
 *
 * 支持操作：
 * - list: 列出目录内容
 * - info: 获取文件/目录详细信息
 * - size: 计算目录占用空间
 * - storage: 获取整体存储使用情况概览
 *
 * 需要 Shizuku shell 权限才能访问 /sdcard/Android/data/ 等受限目录。
 */
class BrowseFilesTool : BaseTool() {

    override fun getName(): String = "browse_files"

    override fun getDisplayName(): String = if (useChineseDescription) "文件浏览" else "Browse Files"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter(
            "action",
            "string",
            "Action to perform: 'list' (list directory), 'info' (file details), 'size' (directory size), 'storage' (storage overview).",
            true
        ),
        ToolParameter(
            "path",
            "string",
            "File or directory path (e.g. '/sdcard/Download', '/sdcard/Android/data/com.tencent.mm'). Required for list/info/size.",
            false
        ),
        ToolParameter(
            "show_hidden",
            "boolean",
            "Optional: show hidden files when listing. Default false.",
            false
        )
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val action = requireString(params, "action")

        if (action == "storage") {
            val overview = ShizukuShellService.getStorageOverview()
                ?: return ToolResult.error("Shizuku not available. This tool requires Shizuku shell access.")
            return ToolResult.success(overview)
        }

        val path = requireString(params, "path")

        // 路径安全检查 —— 限制在 /sdcard 沙箱内，拦截 /system /proc 及其他 App 私有目录。
        PathGuard.underSdcard(path).let {
            if (!it.allow) return ToolResult.error("Access denied: $path 越界或敏感 (${it.reason})。Shell 仅可访问 /sdcard/ 与 /sdcard/Android/data/。")
        }

        return when (action) {
            "list" -> {
                val showHidden = optionalBoolean(params, "show_hidden", false)
                val result = ShizukuShellService.listFiles(path, showHidden)
                    ?: return ToolResult.error("Shizuku not available.")
                if (result.startsWith("Error:")) {
                    ToolResult.error(result)
                } else {
                    ToolResult.success("Directory: $path\n$result")
                }
            }
            "info" -> {
                val result = ShizukuShellService.getFileInfo(path)
                    ?: return ToolResult.error("Shizuku not available.")
                if (result.startsWith("Error:")) {
                    ToolResult.error(result)
                } else {
                    ToolResult.success(result)
                }
            }
            "size" -> {
                val result = ShizukuShellService.getDirectorySize(path)
                    ?: return ToolResult.error("Shizuku not available or path not found.")
                ToolResult.success(result)
            }
            else -> ToolResult.error("Unknown action: $action. Use 'list', 'info', 'size', or 'storage'.")
        }
    }

    override fun getDescriptionEN(): String = """
        Browse and inspect files on the device using Shizuku shell access.
        Can access /sdcard/ and /sdcard/Android/data/ (other apps' data directories).
        
        Actions:
        - list: List directory contents (path required)
        - info: Get detailed file/directory info (path required)
        - size: Calculate directory size (path required)
        - storage: Get overall storage overview (no path needed)
        
        Examples:
        - Browse downloads: action="list", path="/sdcard/Download"
        - Check WeChat data: action="list", path="/sdcard/Android/data/com.tencent.mm"
        - Get storage overview: action="storage"
    """.trimIndent()

    override fun getDescriptionCN(): String = """
        使用 Shizuku shell 权限浏览和检查设备文件。
        可以访问 /sdcard/ 和 /sdcard/Android/data/（其他 App 的数据目录，普通 App 无法访问）。
        
        操作：
        - list: 列出目录内容（需要 path）
        - info: 获取文件/目录详细信息（需要 path）
        - size: 计算目录占用空间（需要 path）
        - storage: 获取整体存储概览（不需要 path）
        
        示例：
        - 浏览下载: action="list", path="/sdcard/Download"
        - 查看微信数据: action="list", path="/sdcard/Android/data/com.tencent.mm"
        - 存储概览: action="storage"
    """.trimIndent()
}
