package com.apk.claw.android.tool.impl

import com.apk.claw.android.ClawApplication
import com.apk.claw.android.account.EffectiveLlm
import com.apk.claw.android.account.LlmRouting
import com.apk.claw.android.plugin.PluginManifest
import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult
import com.apk.claw.android.utils.OctoHttp
import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 生成一个独立可运行的单文件 H5 应用 —— 秒哒式"一句话建应用"的本地版（Phase 2：本地持久化为小程序）。
 *
 * 内部串两次 LLM 调用（规划 → 编码），而不是走 DefaultAgentService 的单轮 ReAct 循环：
 * 那套循环目前没有"分阶段暂停/换 system prompt"的机制，与其改核心循环，不如让这个工具
 * 自己在 execute() 里做一个迷你两段流水线。
 *
 * 复用 [LlmRouting.effective] 拿到跟主 Agent 完全相同的中转/BYO 路由结果，不需要用户
 * 额外配置模型。生成结果一份两用：① 走 preview_html 一样的通道推给网页控制台即时预览；
 * ② 落盘成 `filesDir/generated_apps/{id}/` 下的 mini-app（manifest+index.html），
 * 通过 [com.apk.claw.android.plugin.PluginManager] 挂进 [com.apk.claw.android.plugin.MiniAppRegistry]，
 * 用户可在「小程序」列表里随时重新打开——不需要再造一个新的"生成应用"列表 UI。
 *
 * 生成的 manifest 默认不声明任何 allow_tools/allow_device/allow_pay（等同零桥权限，纯前端沙箱 +
 * localStorage），刻意不给生成内容任何设备自动化/支付能力——这是有意的最小化范围，不是遗漏。
 * 仍然没有云端后端/多设备同步——只适合个人单机使用的小工具/小游戏/可视化。
 */
class GenerateAppTool : BaseTool() {

    companion object {
        private const val MAX_DESC_LEN = 2000
        private const val PREVIEW_HEIGHT = 700
        private const val MANIFEST_VERSION = "1.0.0"
    }

    private val http = OctoHttp.shared.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    override fun getName() = "generate_app"
    override fun getDisplayName() = if (useChineseDescription) "生成应用" else "Generate App"

    override fun getParameters() = listOf(
        ToolParameter(
            "description", "string",
            "Natural language description of the app to build (purpose, key features, look & feel).",
            true,
        ),
        ToolParameter("app_name", "string", "Short app name/title shown as the preview title.", false),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val description = requireString(params, "description").take(MAX_DESC_LEN)
        val appName = optionalString(params, "app_name", "").ifBlank { "我的应用" }

        val eff = LlmRouting.effective()
        if (eff.apiKey.isBlank()) return ToolResult.error("未配置模型，无法生成应用")

        val plan = runCatching { callLlm(eff, planPrompt(description)) }
            .getOrElse { return ToolResult.error("规划阶段失败: ${it.message}") }

        val raw = runCatching { callLlm(eff, codePrompt(appName, description, plan)) }
            .getOrElse { return ToolResult.error("生成代码阶段失败: ${it.message}") }

        val html = extractHtml(raw)
        if (html.isBlank()) return ToolResult.error("生成结果为空或不是有效 HTML")

        val appId = "gen_" + System.currentTimeMillis()
        val saved = runCatching { persistAsMiniApp(appId, appName, html) }.getOrDefault(false)
        val savedNote = if (saved) "，已存为小程序「$appName」，可在「小程序」里随时重新打开" else ""

        val payload = "$PREVIEW_HEIGHT\n$html"
        return ToolResult.successWithHtml(
            "已生成应用「$appName」（${html.length} 字符，规划: ${plan.take(80)}）$savedNote。" +
                "预览已自动推送到控制台，不需要再调用 preview_html 展示同一个应用。",
            payload,
        )
    }

    /**
     * 落盘成 mini-app 插件目录，交给 [PluginManager] 认领。目录/manifest 只由这个工具写入，
     * 见 [com.apk.claw.android.plugin.PluginManager] 里对 generated 来源的信任规则注释。
     */
    private fun persistAsMiniApp(appId: String, appName: String, html: String): Boolean {
        val ctx = ClawApplication.instance
        val dir = File(ctx.filesDir, "generated_apps/$appId")
        if (!dir.exists() && !dir.mkdirs()) return false
        File(dir, "index.html").writeText(html)
        val manifest = PluginManifest(
            id = appId,
            name = appName,
            version = MANIFEST_VERSION,
            type = "mini-app",
            description = "由 Agent 生成",
            page = "index.html",
            // allow_tools/allow_device/allow_pay 均留空/false 默认值：生成内容零桥权限。
        )
        File(dir, "manifest.json").writeText(Gson().toJson(manifest))
        ctx.pluginManager.refreshNonDexPlugins()
        return true
    }

    private fun planPrompt(description: String) = """
        你是资深产品经理。用户想要一个应用：“$description”
        用简短条目列出：核心功能点（3-6条）、需要哪些界面元素、需要哪些内存状态数据。
        直接输出条目列表，不要输出代码，不要输出多余解释。
    """.trimIndent()

    private fun codePrompt(appName: String, description: String, plan: String) = """
        你是资深前端工程师。根据以下产品方案，实现一个单文件 HTML 应用（内联 CSS/JS，默认不依赖
        外部资源；确有需要用图表库等时才用 CDN）。

        应用名称：$appName
        用户需求：$description
        产品方案：
        $plan

        要求：
        - 输出必须是完整可运行的单个 HTML 文档（从 <!DOCTYPE html> 到 </html>），移动端友好、界面美观
        - 只输出 HTML 代码本身，不要用 markdown 代码块包裹，不要输出任何解释文字
    """.trimIndent()

    private fun callLlm(eff: EffectiveLlm, userPrompt: String): String {
        val url = eff.baseUrl.trimEnd('/') + "/chat/completions"
        val body = JSONObject().apply {
            put("model", eff.model)
            put("temperature", 0.3)
            put(
                "messages",
                JSONArray().put(JSONObject().put("role", "user").put("content", userPrompt)),
            )
        }
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${eff.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { resp ->
            val respBody = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("HTTP ${resp.code}: ${respBody.take(200)}")
            val message = JSONObject(respBody)
                .getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            return message.optString("content", "").takeIf { it.isNotBlank() }
                ?: message.optString("reasoning_content", "")
        }
    }

    /** LLM 偶尔仍会用 ```html 代码块包一层，或前后加几句寒暄——这里剥掉，只留 HTML 本体。 */
    private fun extractHtml(raw: String): String {
        val fenced = Regex("```(?:html)?\\s*([\\s\\S]*?)```").find(raw)?.groupValues?.get(1)
        val candidate = (fenced ?: raw).trim()
        val start = candidate.indexOf("<!DOCTYPE", ignoreCase = true).takeIf { it >= 0 }
            ?: candidate.indexOf("<html", ignoreCase = true)
        return if (start >= 0) candidate.substring(start) else candidate
    }

    override fun getDescriptionEN() = """
        Generate a complete, working single-file HTML app from a natural language description.
        Runs an internal two-stage pipeline (plan the features first, then write the code) inside
        this one tool call, then renders the result immediately in the web console preview (same
        channel as preview_html) AND saves it as a mini-app the user can reopen later from the
        "小程序" (mini-apps) list — no separate publish step needed. The preview is already shown
        after this call returns — do NOT call preview_html afterward for the same app, that would
        just render a redundant second (and worse, since it bypasses the planning stage) version.
        Still no cloud backend/multi-device sync — client-only apps only (games, calculators,
        tools, visualizations), local storage at most. Prefer this over preview_html when the user
        asks you to "build/generate an app" rather than just preview a snippet of code.
    """.trimIndent()

    override fun getDescriptionCN() = """
        根据自然语言描述生成一个完整可运行的单文件 HTML 应用。
        在这一次工具调用内部跑一个两段流水线（先规划功能，再写代码），结果直接在网页控制台
        预览渲染（与 preview_html 同一通道），同时自动存成一个小程序，用户之后可以在
        「小程序」列表里随时重新打开，不需要额外发布步骤。工具返回时预览已经展示完毕——
        **不要**在这之后再调用 preview_html 展示同一个应用，那只会生成一个多余且更差的版本
        （跳过了规划步骤）。仍然没有云端后端/多设备同步——只适合纯前端应用（小游戏、计算器、
        工具、可视化），最多能用本地存储。当用户要求"做一个应用/生成一个应用"而不只是预览
        一段代码时，优先用这个而不是 preview_html。
    """.trimIndent()
}
