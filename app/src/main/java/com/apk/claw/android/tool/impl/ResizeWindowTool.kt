package com.apk.claw.android.tool.impl

import com.apk.claw.android.ClawApplication
import com.apk.claw.android.R
import com.apk.claw.android.shizuku.ShizukuManager
import com.apk.claw.android.shizuku.ShizukuShellService
import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult

/**
 * 调整窗口大小和位置。
 *
 * 通过 Shizuku 调用 `am task resize` 命令实现。
 * 可用于调整 freeform 模式下的弹窗窗口，也可调整全屏应用窗口。
 */
class ResizeWindowTool : BaseTool() {

    override fun getName(): String = "resize_window"

    override fun getDisplayName(): String =
        ClawApplication.instance.getString(R.string.tool_name_resize_window)

    override fun getDescriptionEN(): String =
        "Resize and reposition a window. Use task_id=-1 for the top/active window. " +
        "Requires Shizuku permission."

    override fun getDescriptionCN(): String =
        "调整窗口大小和位置。task_id=-1 表示当前最顶层窗口。需要 Shizuku 权限。"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter(
            name = "task_id",
            type = "integer",
            description = "任务 ID，-1 表示当前最顶层窗口",
            isRequired = false
        ),
        ToolParameter(
            name = "x",
            type = "integer",
            description = "窗口左上角 X 坐标（像素）",
            isRequired = true
        ),
        ToolParameter(
            name = "y",
            type = "integer",
            description = "窗口左上角 Y 坐标（像素）",
            isRequired = true
        ),
        ToolParameter(
            name = "width",
            type = "integer",
            description = "窗口宽度（像素）",
            isRequired = true
        ),
        ToolParameter(
            name = "height",
            type = "integer",
            description = "窗口高度（像素）",
            isRequired = true
        )
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        if (!ShizukuManager.isAvailable()) {
            return ToolResult.error("Shizuku is not available")
        }

        val taskId = (params["task_id"] as? Number)?.toInt() ?: -1
        val x = (params["x"] as? Number)?.toInt()
            ?: return ToolResult.error("Missing required parameter: x")
        val y = (params["y"] as? Number)?.toInt()
            ?: return ToolResult.error("Missing required parameter: y")
        val width = (params["width"] as? Number)?.toInt()
            ?: return ToolResult.error("Missing required parameter: width")
        val height = (params["height"] as? Number)?.toInt()
            ?: return ToolResult.error("Missing required parameter: height")

        return when (val result = ShizukuShellService.moveTask(taskId, x, y, width, height)) {
            null -> ToolResult.error("Shizuku shell execution failed")
            true -> ToolResult.success("Window resized to ($x, $y, ${width}x$height)")
            false -> ToolResult.error("Failed to resize window. Task may not exist or not support resizing.")
        }
    }
}
