package com.apk.claw.android.tool.impl

import com.apk.claw.android.ClawApplication
import com.apk.claw.android.R
import com.apk.claw.android.shizuku.ShizukuManager
import com.apk.claw.android.shizuku.ShizukuShellService
import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult

/**
 * 以 freeform（弹窗/小窗）模式启动应用。
 *
 * 参考 Extendroid 实现思路，通过 Shizuku 调用 `am start --windowingMode 5` 实现。
 * 需要用户已启用 Shizuku 权限。
 *
 * 使用场景：
 *  - Agent 同时在多个 App 窗口执行任务
 *  - 用户在主屏做其他事，Agent 在弹窗里自动操作
 */
class LaunchFreeformTool : BaseTool() {

    override fun getName(): String = "launch_freeform"

    override fun getDisplayName(): String =
        ClawApplication.instance.getString(R.string.tool_name_launch_freeform)

    override fun getDescriptionEN(): String =
        "Launch an app in freeform (popup window) mode. " +
        "Use display_id to launch on external display (async casting). " +
        "Requires Shizuku permission."

    override fun getDescriptionCN(): String =
        "以弹窗（小窗）模式启动应用。" +
        "可通过 display_id 在外接屏上启动（异步投屏）。需要 Shizuku 权限。"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter(
            name = "package_name",
            type = "string",
            description = "应用包名，如 com.tencent.mm",
            isRequired = true
        ),
        ToolParameter(
            name = "display_id",
            type = "integer",
            description = "显示器 ID。0=手机主屏，>0=外接屏。默认 0。用 get_window_info 查看可用显示器",
            isRequired = false
        ),
        ToolParameter(
            name = "x",
            type = "integer",
            description = "窗口左上角 X 坐标（像素），默认 100",
            isRequired = false
        ),
        ToolParameter(
            name = "y",
            type = "integer",
            description = "窗口左上角 Y 坐标（像素），默认 200",
            isRequired = false
        ),
        ToolParameter(
            name = "width",
            type = "integer",
            description = "窗口宽度（像素），默认 600",
            isRequired = false
        ),
        ToolParameter(
            name = "height",
            type = "integer",
            description = "窗口高度（像素），默认 900",
            isRequired = false
        )
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        if (!ShizukuManager.isAvailable()) {
            return ToolResult.error(
                "Shizuku is not available. Please install Shizuku app and grant permission first."
            )
        }

        val packageName = params["package_name"] as? String
            ?: return ToolResult.error("Missing required parameter: package_name")

        val displayId = (params["display_id"] as? Number)?.toInt() ?: 0
        val x = (params["x"] as? Number)?.toInt() ?: 100
        val y = (params["y"] as? Number)?.toInt() ?: 200
        val width = (params["width"] as? Number)?.toInt() ?: 600
        val height = (params["height"] as? Number)?.toInt() ?: 900

        val result = if (displayId > 0) {
            // 异步投屏：在外接显示器上启动
            ShizukuShellService.launchFreeformOnDisplay(packageName, displayId, x, y, width, height)
        } else {
            // 主屏幕 freeform
            ShizukuShellService.launchFreeform(packageName, x, y, width, height)
        }

        return when (result) {
            null -> ToolResult.error("Shizuku shell execution failed")
            true -> ToolResult.success(
                "Launched $packageName in freeform mode on display #$displayId at ($x, $y) size ${width}x$height"
            )
            false -> ToolResult.error(
                "Failed to launch $packageName. The app may not support freeform windows or display #$displayId."
            )
        }
    }
}
