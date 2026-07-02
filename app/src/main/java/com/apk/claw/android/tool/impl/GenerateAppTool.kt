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
        /** 生成后自动修复的最大轮数(每轮:离屏渲染抓错 → LLM 修 → 再渲染)。 */
        private const val MAX_REPAIR = 2
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

        var html = extractHtml(raw)
        if (html.isBlank()) return ToolResult.error("生成结果为空或不是有效 HTML")

        // ── 闭环:离屏渲染抓 console error → 有错让 LLM 修复(最多 MAX_REPAIR 轮,只采纳更优版本)──
        // 这是"写-跑-看-改"的最小闭环:agent 不再 one-shot 碰运气,生成后先自检再存。
        var repairRounds = 0
        var errors = runCatching { HtmlLinter.lint(ClawApplication.instance, html).errors }.getOrDefault(emptyList())
        while (errors.isNotEmpty() && repairRounds < MAX_REPAIR) {
            repairRounds++
            val fixedRaw = runCatching { callLlm(eff, repairPrompt(html, errors)) }.getOrNull() ?: break
            val fixedHtml = extractHtml(fixedRaw).takeIf { it.isNotBlank() } ?: break
            val fixedErrors = runCatching { HtmlLinter.lint(ClawApplication.instance, fixedHtml).errors }.getOrDefault(emptyList())
            if (fixedErrors.size > errors.size) break  // 修得更糟就别采纳,保留上一版
            html = fixedHtml; errors = fixedErrors
            if (errors.isEmpty()) break
        }

        // ── 视觉验证(VLM):渲染截图 → 判「界面是否实现了用户需求」→ 未达标则按视觉反馈修 1 轮 ──
        // 补齐 console-error 抓不到的"无报错但功能没实现"。全程 fail-open(GoalVerifier 未配置 VLM
        // 时判达成,不触发误修)。
        var visualNote = ""
        if (com.apk.claw.android.octopus_mobile.VisionAnalyzer.isConfigured()) {
            val shot = runCatching { HtmlLinter.lint(ClawApplication.instance, html, capture = true).screenshot }.getOrNull()
            if (shot != null) {
                val verdict = runCatching {
                    kotlinx.coroutines.runBlocking {
                        com.apk.claw.android.octopus_mobile.GoalVerifier.verify(description, shot)
                    }
                }.getOrNull()
                if (verdict != null && !verdict.achieved) {
                    val fixedRaw = runCatching { callLlm(eff, repairPromptVisual(html, description, verdict.reason)) }.getOrNull()
                    val fixedHtml = fixedRaw?.let { extractHtml(it) }?.takeIf { it.isNotBlank() }
                    if (fixedHtml != null) {
                        val fe = runCatching { HtmlLinter.lint(ClawApplication.instance, fixedHtml).errors }.getOrDefault(emptyList())
                        if (fe.size <= errors.size) { html = fixedHtml; errors = fe; visualNote = "，并按视觉检查修正了功能实现" }
                    }
                }
                runCatching { shot.recycle() }
            }
        }

        // actions 从最终 html 解析(extractHtml 保留了 </html> 之后的 OCTOPUS_ACTIONS 注释)
        val actions = parseActions(html)

        val appId = "gen_" + System.currentTimeMillis()
        val saved = runCatching { persistAsMiniApp(appId, appName, html, actions) }.getOrDefault(false)
        val savedNote = if (saved) "，已存为小程序「$appName」，可在「小程序」里随时重新打开" else ""
        val lintNote = when {
            repairRounds == 0 && errors.isEmpty() -> "，自检无控制台错误"
            errors.isEmpty() -> "，自动修复 $repairRounds 轮后无控制台错误"
            else -> "，自动修复 $repairRounds 轮，仍有 ${errors.size} 处控制台错误(可让我继续修)"
        }

        val payload = "$PREVIEW_HEIGHT\n$html"
        return ToolResult.successWithHtml(
            "已生成应用「$appName」（${html.length} 字符）$lintNote$visualNote$savedNote。" +
                "预览已自动推送到控制台，不需要再调用 preview_html 展示同一个应用。",
            payload,
        )
    }

    /**
     * 落盘成 mini-app 插件目录，交给 [PluginManager] 认领。目录/manifest 只由这个工具写入，
     * 见 [com.apk.claw.android.plugin.PluginManager] 里对 generated 来源的信任规则注释。
     */
    private fun persistAsMiniApp(
        appId: String,
        appName: String,
        html: String,
        actions: List<com.apk.claw.android.plugin.PluginActionDef>,
    ): Boolean {
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
            // actions:生成的 app 自声明可被 Agent 调用的动作(list_apps 发现 / app_action 派发)。
            actions = actions,
        )
        File(dir, "manifest.json").writeText(Gson().toJson(manifest))
        ctx.pluginManager.refreshNonDexPlugins()
        return true
    }

    /** 从 LLM 原始输出解析 `<!--OCTOPUS_ACTIONS:[...]-->` 声明;失败/缺失则空。 */
    private fun parseActions(raw: String): List<com.apk.claw.android.plugin.PluginActionDef> {
        val m = Regex("""<!--\s*OCTOPUS_ACTIONS:\s*(\[.*?])\s*-->""", RegexOption.DOT_MATCHES_ALL).find(raw)
            ?: return emptyList()
        return runCatching {
            val type = object : com.google.gson.reflect.TypeToken<List<com.apk.claw.android.plugin.PluginActionDef>>() {}.type
            Gson().fromJson<List<com.apk.claw.android.plugin.PluginActionDef>>(m.groupValues[1], type) ?: emptyList()
        }.getOrDefault(emptyList())
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
        - **让应用可被 AI 助手操作**:实现 `window.octopus.onAgentAction = function(actionType, params){ ... }`,
          为应用的每个关键操作提供一个 action 分支(处理后 return 一句简短字符串表示结果);在关键的用户
          操作处调用 `octopus.reportAction(actionType, params)` 上报(先判断 `window.octopus` 是否存在)。
        - 在 </html> **之后**追加一行 HTML 注释,声明你实现了哪些 action(供宿主发现,数组可为空):
          <!--OCTOPUS_ACTIONS:[{"name":"动作名","description":"一句话说明","params":[{"name":"参数名","type":"string","description":"说明","required":true}]}]-->
        - 只输出 HTML 代码本身(可含上面那行注释)，不要用 markdown 代码块包裹，不要输出任何解释文字
    """.trimIndent()

    /** 视觉修复 prompt:渲染截图经 VLM 判定"没实现需求"时,把用户需求 + 判定原因喂回让 LLM 修功能。 */
    private fun repairPromptVisual(html: String, description: String, reason: String) = """
        你是资深前端工程师。下面这个单文件 HTML 应用渲染出来后,经视觉检查发现没有完整实现用户需求。
        用户想要：$description
        视觉检查发现的问题：$reason
        请修改代码,让界面真正实现用户需求(布局 / 交互 / 内容)。
        - 只输出完整 HTML(从 <!DOCTYPE html> 到 </html>),保留原有 <!--OCTOPUS_ACTIONS:...--> 注释(若有)。
        - 不要用 markdown 代码块包裹,不要输出任何解释文字。

        当前代码：
        $html
    """.trimIndent()

    /** 修复 prompt:把当前 HTML + 无头渲染抓到的 console error 一起喂回,让 LLM 只改错不改功能。 */
    private fun repairPrompt(html: String, errors: List<String>) = """
        你是资深前端工程师。下面这个单文件 HTML 应用在无头浏览器渲染时报了下列 JS 控制台错误,请修复。
        要求：
        - 只修这些错误,保持原有功能与界面不变。
        - 只输出修复后的完整 HTML(从 <!DOCTYPE html> 到 </html>),并保留原有的
          <!--OCTOPUS_ACTIONS:...--> 注释(若有)。
        - 不要用 markdown 代码块包裹,不要输出任何解释文字。

        控制台错误：
        ${errors.joinToString("\n") { "- $it" }}

        当前代码：
        $html
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
