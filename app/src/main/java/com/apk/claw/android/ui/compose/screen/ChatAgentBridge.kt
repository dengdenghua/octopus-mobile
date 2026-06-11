package com.apk.claw.android.ui.compose.screen

import android.os.Handler
import android.os.Looper
import com.apk.claw.android.agent.AgentCallback
import com.apk.claw.android.agent.AgentConfig
import com.apk.claw.android.agent.DefaultAgentService
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

    private fun buildConfig(): AgentConfig {
        var baseUrl = KVUtils.getLlmBaseUrl().trim()
        if (baseUrl.isEmpty()) baseUrl = "https://api.openai.com/v1"
        return AgentConfig.Builder()
            .apiKey(KVUtils.getLlmApiKey())
            .baseUrl(baseUrl)
            .modelName(KVUtils.getLlmModelName().ifBlank { "deepseek-chat" })
            .temperature(0.1)
            .maxIterations(40)
            .enableVision(false)
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
        service.executeTask(prompt, object : AgentCallback {
            override fun onLoopStart(round: Int) {}

            override fun onContent(round: Int, content: String) {
                if (content.isNotBlank()) main.post { onText(content) }
            }

            override fun onToolCall(round: Int, toolId: String, toolName: String, parameters: String) {}

            override fun onToolResult(
                round: Int, toolId: String, toolName: String, parameters: String, result: ToolResult
            ) {
                val summary = if (result.isSuccess) "✓ " + (result.data ?: "") else "✗ " + (result.error ?: "")
                main.post { onTool("🔧", toolName, parameters, summary.take(48)) }
            }

            override fun onComplete(round: Int, finalAnswer: String, totalTokens: Int) {
                main.post { onDone(finalAnswer) }
            }

            override fun onError(round: Int, error: Exception, totalTokens: Int) {
                main.post { onError(error.message ?: "调用失败") }
            }

            override fun onSystemDialogBlocked(round: Int, totalTokens: Int) {
                main.post { onError("检测到系统弹窗，已暂停（需手动处理）") }
            }
        })
    }
}
