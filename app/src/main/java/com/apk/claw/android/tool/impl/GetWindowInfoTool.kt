package com.apk.claw.android.tool.impl

import com.apk.claw.android.ClawApplication
import com.apk.claw.android.R
import com.apk.claw.android.shizuku.ShizukuManager
import com.apk.claw.android.shizuku.ShizukuShellService
import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult

/**
 * 获取当前运行的任务窗口列表。
 *
 * 返回所有正在运行的任务及其窗口信息（taskId、packageName、windowingMode）。
 * Agent 可用此工具了解当前屏幕上的窗口布局，然后决定是否需要调整窗口。
 */
class GetWindowInfoTool : BaseTool() {

    override fun getName(): String = "get_window_info"

    override fun getDisplayName(): String =
        ClawApplication.instance.getString(R.string.tool_name_get_window_info)

    override fun getDescriptionEN(): String =
        "Get information about all running task windows including taskId and packageName. " +
        "Requires Shizuku permission. Use this to find task IDs for resize_window."

    override fun getDescriptionCN(): String =
        "获取所有运行中的任务窗口信息，包括 taskId 和 packageName。" +
        "需要 Shizuku 权限。可配合 resize_window 使用。"

    override fun getParameters(): List<ToolParameter> = emptyList()

    override fun execute(params: @JvmSuppressWildcards Map<String, Any>): ToolResult {
        if (!ShizukuManager.isAvailable()) {
            return ToolResult.error("Shizuku is not available")
        }

        val tasks = ShizukuShellService.getRunningTasks()
            ?: return ToolResult.error("Failed to get running tasks")

        val screenSize = ShizukuShellService.getScreenSize()
        val screenInfo = screenSize?.let { "Screen: ${it.first}x${it.second}\n" } ?: ""

        return ToolResult.success("$screenInfo\nRunning tasks:\n$tasks")
    }
}
