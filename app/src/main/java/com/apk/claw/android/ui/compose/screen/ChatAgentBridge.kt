package com.apk.claw.android.ui.compose.screen

import android.os.Handler
import android.os.Looper
import com.apk.claw.android.agent.AgentCallback
import com.apk.claw.android.agent.AgentConfig
import com.apk.claw.android.agent.DefaultAgentService
import com.apk.claw.android.floating.LiveControlOverlay
import com.apk.claw.android.tool.ToolRegistry
import com.apk.claw.android.tool.ToolResult
import com.apk.claw.android.utils.KVUtils

/**
 * 把 Compose 对话页直接接到真实 Agent（[DefaultAgentService]）。
 *
 * 这样用户在 App 内对话框输入自然语言指令，就能真正驱动 LLM + 工具执行，
 * 无需先去某个 IM 平台配置机器人 —— 这是让应用「真的能跑」的本地入口。
 *
 * 设计：
 *  - 配置直接取自 [KVUtils]（与渠道/编排器同一份存储），所以在「LLM 配置」里
 *    填好 DeepSeek/OpenAI 的 key/baseUrl/model 后，这里立即生效。
 *  - 所有 [AgentCallback] 事件都切回主线程，方便直接更新 Compose 状态。
 *  - 关闭视觉（VLM）：deepseek-chat 等纯文本模型不支持图片输入。
 */
object ChatAgentBridge {

    private val service = DefaultAgentService()
    private val main = Handler(Looper.getMainLooper())

    /** 是否已配置可用的 LLM（有 API Key 即认为可跑）。 */
    fun isConfigured(): Boolean = KVUtils.getLlmApiKey().isNotBlank()

    /** 中断当前正在运行的任务。 */
    fun cancel() {
        service.cancel()
        LiveControlOverlay.hide()
    }

    private fun buildConfig(): AgentConfig {
        var baseUrl = KVUtils.getLlmBaseUrl().trim()
        if (baseUrl.isEmpty()) baseUrl = "https://api.deepseek.com/v1"
        return AgentConfig.Builder()
            .apiKey(KVUtils.getLlmApiKey())
            .baseUrl(baseUrl)
            .modelName(KVUtils.getLlmModelName().ifBlank { "deepseek-chat" })
            .temperature(0.1)
            .maxIterations(40)
            .enableVision(false)
            .streaming(true)   // 逐字流式输出
            .build()
    }

    /**
     * 运行一次任务。所有回调均在主线程触发。
     *
     * @param onTool 一次工具执行完成（图标、工具名、参数、结果摘要）
     * @param onText Agent 中间思考文本
     * @param onDone 任务完成（最终回答）
     * @param onError 出错（含未配置 / LLM 调用失败）
     */
    fun run(
        prompt: String,
        onTool: (icon: String, name: String, args: String, result: String?) -> Unit,
        onText: (String) -> Unit,
        onDone: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        service.updateConfig(buildConfig())
        // 实时控制层：任务期间悬浮显示当前步骤 + 停止键（即使 Agent 跳出本 App 也可见）
        LiveControlOverlay.show("💭 准备中…") { cancel() }
        service.executeTask(prompt, object : AgentCallback {
            override fun onLoopStart(round: Int) {
                LiveControlOverlay.updateStep("💭 思考中…")
            }

            override fun onContent(round: Int, content: String) {
                // 流式:每个 token 都回调(保留空白,避免词间粘连)
                if (content.isNotEmpty()) main.post { onText(content) }
            }

            override fun onToolCall(round: Int, toolId: String, toolName: String, parameters: String) {}

            override fun onToolResult(
                round: Int, toolId: String, toolName: String, parameters: String, result: ToolResult
            ) {
                val summary = if (result.isSuccess) "✓ " + (result.data ?: "") else "✗ " + (result.error ?: "")
                val icon = iconFor(toolName)
                val friendly = ToolRegistry.getInstance().getDisplayName(toolName)
                LiveControlOverlay.updateStep("$icon $friendly")
                main.post { onTool(icon, friendly, parameters, summary.take(48)) }
            }

            override fun onComplete(round: Int, finalAnswer: String, totalTokens: Int) {
                LiveControlOverlay.finish(true, "完成")
                main.post { onDone(finalAnswer) }
            }

            override fun onError(round: Int, error: Exception, totalTokens: Int) {
                LiveControlOverlay.finish(false, error.message?.take(20) ?: "出错")
                main.post { onError(error.message ?: "调用失败") }
            }

            override fun onSystemDialogBlocked(round: Int, totalTokens: Int) {
                LiveControlOverlay.finish(false, "需手动处理")
                main.post { onError("检测到系统弹窗，已暂停（需手动处理）") }
            }
        })
    }

    /** 工具名 → 直观图标(未命中用通用扳手)。 */
    private fun iconFor(tool: String): String = when {
        tool.contains("screenshot") -> "📸"
        tool.contains("screen") || tool.contains("window") || tool.contains("node") || tool.contains("find") -> "🔍"
        tool.startsWith("tap") || tool.contains("click") -> "👆"
        tool.contains("long_press") -> "✊"
        tool.contains("swipe") || tool.contains("scroll") -> "👋"
        tool.contains("input") || tool.contains("text") -> "⌨️"
        tool.contains("installed_apps") || tool.contains("usage") -> "📋"
        tool.contains("open_app") || tool.contains("launch") || tool.contains("store") -> "📱"
        tool.contains("home") -> "🏠"
        tool.contains("back") -> "↩️"
        tool.contains("key") || tool.contains("recent") -> "⎋"
        tool.contains("browser") || tool.contains("navigate") -> "🌐"
        tool.contains("sms") || tool.contains("send") || tool.contains("file") -> "📤"
        tool.contains("calendar") -> "📅"
        tool.contains("clipboard") -> "📋"
        tool.contains("wait") -> "⏳"
        tool.contains("finish") -> "✅"
        else -> "🔧"
    }
}
