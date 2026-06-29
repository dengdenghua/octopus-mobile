package com.apk.claw.android.tool.impl

import com.apk.claw.android.ClawApplication
import com.apk.claw.android.R
import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult
import java.util.Collections

class WaitTool : BaseTool() {

    override fun getName(): String = "wait"

    override fun getDisplayName(): String =
        ClawApplication.instance.getString(R.string.tool_name_wait)

    override fun getDescriptionEN(): String =
        "Wait for a specified number of milliseconds. Useful for waiting for UI transitions, animations, or loading to complete."

    override fun getDescriptionCN(): String =
        "等待指定的毫秒数。适用于等待UI过渡、动画或加载完成。"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter("duration_ms", "integer", "Duration to wait in milliseconds", true)
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val duration = requireLong(params, "duration_ms")
        if (duration < 0 || duration > 30000) {
            return ToolResult.error("Duration must be between 0 and 30000 milliseconds")
        }
        return if (sleepInterruptible(duration)) {
            ToolResult.success("Waited for ${duration}ms")
        } else {
            ToolResult.error("Wait was cancelled")
        }
    }
}
