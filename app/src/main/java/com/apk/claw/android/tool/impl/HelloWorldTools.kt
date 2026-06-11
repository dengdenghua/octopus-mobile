package com.apk.claw.android.tool.impl

import android.os.Build
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.R
import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

/**
 * 100 行扩展示例：3 个一行工具合集.
 *
 * 这个文件演示如何在一个文件里放多个简单工具.
 * 每个工具是一个匿名内部类，70 行能搞定.
 *
 * 教学要点：
 *  - getName() → 工具 ID（LLM 用这个调）
 *  - getDescriptionEN/CN → 决定 LLM 何时调用
 *  - getParameters() → 输入参数 schema
 *  - execute() → 实际执行
 */
class HelloWorldTools {

    /** 工具 1: current_time —— 获取当前时间 */
    class CurrentTimeTool : BaseTool() {
        override fun getName() = "current_time"
        override fun getDisplayName() =
            ClawApplication.instance.getString(R.string.tool_name_current_time)

        override fun getDescriptionEN() =
            "Get the current device time. Use this when the task depends on 'now' or 'today' (e.g., 'what's today')."

        override fun getDescriptionCN() =
            "获取设备当前时间。当任务依赖'现在'或'今天'时使用（如'今天几号'）。"

        override fun getParameters(): List<ToolParameter> = listOf(
            ToolParameter("format", "string",
                "Format: 'iso' | 'date' | 'time' | 'timestamp'", isRequired = false)
        )

        override fun execute(params: Map<String, Any>): ToolResult {
            val format = (params["format"] as? String) ?: "iso"
            val now = System.currentTimeMillis()
            val result = when (format) {
                "date" -> SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(now))
                "time" -> SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(now))
                "timestamp" -> now.toString()
                else -> SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.getDefault())
                    .format(Date(now))
            }
            return ToolResult.success("Current time: $result")
        }
    }

    /** 工具 2: device_info —— 获取设备信息 */
    class DeviceInfoTool : BaseTool() {
        override fun getName() = "device_info"
        override fun getDisplayName() =
            ClawApplication.instance.getString(R.string.tool_name_device_info)

        override fun getDescriptionEN() =
            "Get device info: model, Android version, screen size, battery, current app. " +
            "Use this when the user asks about the device or to debug screen size issues."

        override fun getDescriptionCN() =
            "获取设备信息：型号、安卓版本、屏幕尺寸、电池、当前 App。" +
            "用户询问设备信息或调试屏幕相关问题时使用。"

        override fun getParameters(): List<ToolParameter> = emptyList()

        override fun execute(params: Map<String, Any>): ToolResult {
            val info = buildString {
                appendLine("Brand: ${Build.BRAND}")
                appendLine("Model: ${Build.MODEL}")
                appendLine("Device: ${Build.DEVICE}")
                appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
                appendLine("Manufacturer: ${Build.MANUFACTURER}")
            }
            return ToolResult.success(info)
        }
    }

    /** 工具 3: hello_world —— 演示工具，最简单的一个 */
    class HelloWorldTool : BaseTool() {
        override fun getName() = "hello_world"
        override fun getDisplayName() =
            ClawApplication.instance.getString(R.string.tool_name_hello_world)

        override fun getDescriptionEN() =
            "Say hello to a given name. This is a demo tool showing the minimum implementation. " +
            "Use this for testing the tool calling pipeline."

        override fun getDescriptionCN() =
            "向指定名字打招呼。演示工具，展示最小实现。用于测试工具调用管道。"

        override fun getParameters(): List<ToolParameter> = listOf(
            ToolParameter("name", "string", "Name to greet", isRequired = true)
        )

        override fun execute(params: Map<String, Any>): ToolResult {
            val name = (params["name"] as? String)?.takeIf { it.isNotBlank() }
                ?: return ToolResult.error("'name' is required and must be non-empty")
            return ToolResult.success("Hello, $name! 👋\n\n(You just called your first custom tool.)")
        }
    }

    companion object {
        /** 一次性注册所有 hello world 工具 */
        fun registerAll() {
            val registry = com.apk.claw.android.tool.ToolRegistry.getInstance()
            registry.register(CurrentTimeTool())
            registry.register(DeviceInfoTool())
            registry.register(HelloWorldTool())
        }
    }
}
