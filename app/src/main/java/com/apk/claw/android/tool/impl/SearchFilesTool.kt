package com.apk.claw.android.tool.impl

import com.apk.claw.android.octopus_mobile.safety.PathGuard
import com.apk.claw.android.shizuku.ShizukuShellService
import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult

/**
 * 文件搜索工具 —— AI NAS 核心功能。
 *
 * 支持操作：
 * - name: 按文件名模式搜索（通配符）
 * - content: 按文件内容搜索（grep）
 * - duplicates: 查找重复文件
 *
 * 需要 Shizuku shell 权限。
 */
class SearchFilesTool : BaseTool() {

    override fun getName(): String = "search_files"

    override fun getDisplayName(): String = if (useChineseDescription) "文件搜索" else "Search Files"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter(
            "action",
            "string",
            "Search type: 'name' (by filename pattern), 'content' (by file content), 'duplicates' (find duplicate files).",
            true
        ),
        ToolParameter(
            "path",
            "string",
            "Base directory to search in (e.g. '/sdcard', '/sdcard/Download', '/sdcard/Android/data').",
            true
        ),
        ToolParameter(
            "pattern",
            "string",
            "Search pattern. For 'name': filename glob (e.g. '*.jpg', '*report*'). For 'content': text to search in files.",
            false
        ),
        ToolParameter(
            "file_type",
            "string",
            "Optional: file type filter for content search (e.g. '*.txt', '*.log'). Default '*'.",
            false
        ),
        ToolParameter(
            "min_size_mb",
            "integer",
            "Optional: minimum file size in MB for duplicate search. Default 1.",
            false
        ),
        ToolParameter(
            "max_results",
            "integer",
            "Optional: maximum number of results. Default 30.",
            false
        )
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val action = requireString(params, "action")
        val basePath = requireString(params, "path")
        val maxResults = optionalInt(params, "max_results", 30)

        // 路径安全检查 —— 限制在 /sdcard 沙箱内，拦截越界 grep 读取其他 App 数据。
        PathGuard.underSdcard(basePath).let {
            if (!it.allow) return ToolResult.error("Access denied: $basePath 越界或敏感 (${it.reason})。仅允许 /sdcard 下的路径。")
        }

        return when (action) {
            "name" -> {
                val pattern = requireString(params, "pattern")
                val result = ShizukuShellService.searchFiles(basePath, pattern, maxResults)
                    ?: return ToolResult.error("Shizuku not available.")
                if (result.startsWith("Error:")) {
                    ToolResult.error(result)
                } else if (result.isBlank()) {
                    ToolResult.success("No files found matching '$pattern' in $basePath")
                } else {
                    val count = result.lines().size
                    ToolResult.success("Found $count files matching '$pattern' in $basePath:\n$result")
                }
            }
            "content" -> {
                val text = requireString(params, "pattern")
                val fileType = optionalString(params, "file_type", "*")
                val result = ShizukuShellService.searchByContent(basePath, text, fileType, maxResults)
                    ?: return ToolResult.error("Shizuku not available.")
                if (result.isBlank()) {
                    ToolResult.success("No files containing '$text' found in $basePath")
                } else {
                    val count = result.lines().size
                    ToolResult.success("Found $count files containing '$text' in $basePath:\n$result")
                }
            }
            "duplicates" -> {
                val minSizeMB = optionalInt(params, "min_size_mb", 1)
                val result = ShizukuShellService.findDuplicateFiles(basePath, minSizeMB)
                    ?: return ToolResult.error("Shizuku not available.")
                ToolResult.success("Duplicate files scan in $basePath:\n$result")
            }
            else -> ToolResult.error("Unknown action: $action. Use 'name', 'content', or 'duplicates'.")
        }
    }

    override fun getDescriptionEN(): String = """
        Search files on the device using Shizuku shell access.
        Can search in /sdcard/ and /sdcard/Android/data/ (all apps' data directories).
        
        Actions:
        - name: Search by filename pattern (supports wildcards like '*.jpg', '*report*')
        - content: Search by file content (grep text in files)
        - duplicates: Find duplicate files by size
        
        Examples:
        - Find all photos: action="name", path="/sdcard", pattern="*.jpg"
        - Find PDFs in Download: action="name", path="/sdcard/Download", pattern="*.pdf"
        - Search text in logs: action="content", path="/sdcard", pattern="error", file_type="*.log"
        - Find duplicates: action="duplicates", path="/sdcard", min_size_mb=5
    """.trimIndent()

    override fun getDescriptionCN(): String = """
        使用 Shizuku shell 权限搜索设备文件。
        可以搜索 /sdcard/ 和 /sdcard/Android/data/（所有 App 的数据目录）。
        
        操作：
        - name: 按文件名搜索（支持通配符，如 "*.jpg"、"*报告*"）
        - content: 按文件内容搜索（在文件中 grep 文本）
        - duplicates: 查找重复文件（按文件大小）
        
        示例：
        - 找所有照片: action="name", path="/sdcard", pattern="*.jpg"
        - 找下载目录的 PDF: action="name", path="/sdcard/Download", pattern="*.pdf"
        - 在日志中搜文本: action="content", path="/sdcard", pattern="error", file_type="*.log"
        - 找重复文件: action="duplicates", path="/sdcard", min_size_mb=5
    """.trimIndent()
}
