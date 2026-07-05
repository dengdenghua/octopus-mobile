package com.apk.claw.android.tool.impl

import com.apk.claw.android.ClawApplication
import com.apk.claw.android.account.EffectiveLlm
import com.apk.claw.android.account.LlmRouting
import com.apk.claw.android.octopus_mobile.browser.BrowserPluginHost
import com.apk.claw.android.octopus_mobile.browser.UserscriptParser
import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult
import com.apk.claw.android.utils.OctoHttp
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class GenerateBrowserPluginTool : BaseTool() {

    companion object {
        private const val MAX_JS_LEN = 40_000
    }

    private val http = OctoHttp.shared.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    override fun getName() = "generate_plugin"
    override fun getDisplayName() = if (useChineseDescription) "生成网页脚本" else "Generate Web Plugin"

    override fun getParameters() = listOf(
        ToolParameter(
            "description", "string",
            "What the userscript should do on web pages (e.g. 'hide sidebar ads', " +
                "'add copy-code buttons to code blocks', 'auto-expand all comments').",
            true,
        ),
        ToolParameter(
            "host", "string",
            "Optional site/host match pattern (e.g. 'https://example.com/*', '*.github.com'). Omit for all sites.",
            false,
        ),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val desc = requireString(params, "description").take(2000)
        val host = optionalString(params, "host", "").trim()
        val eff = LlmRouting.effective()
        if (eff.apiKey.isBlank()) return ToolResult.error("未配置模型，无法生成脚本")

        val raw = runCatching { callLlm(eff, userscriptPrompt(desc, host)) }
            .getOrElse { return ToolResult.error("生成脚本失败: ${it.message}") }

        val scriptText = extractUserscript(raw)
        if (scriptText.isBlank()) return ToolResult.error("脚本格式解析失败：未找到 ==UserScript== 块")

        val parsed = UserscriptParser.parse(scriptText, fallbackId = "genplug_${System.currentTimeMillis()}")
            ?: return ToolResult.error("脚本元数据解析失败")

        var js = parsed.js
        if (js.isBlank()) return ToolResult.error("脚本正文为空")
        if (js.length > MAX_JS_LEN) js = js.take(MAX_JS_LEN)

        val slug = parsed.id.ifBlank { "genplug_${System.currentTimeMillis()}" }
        val saved = runCatching { persistAsUserscript(slug, scriptText) }.getOrDefault(false)
        if (!saved) return ToolResult.error("脚本落盘失败")

        val matchDesc = when {
            parsed.match.isNotEmpty() -> parsed.match.joinToString(", ")
            host.isNotBlank() -> host
            else -> "所有网页"
        }
        return ToolResult.success(
            "已生成油猴脚本「${parsed.name}」(v${parsed.version})，将在$matchDesc 匹配的页面上${
                when (parsed.runAt) {
                    com.apk.claw.android.octopus_mobile.browser.Userscript.RunAt.DOCUMENT_START -> "加载前"
                    com.apk.claw.android.octopus_mobile.browser.Userscript.RunAt.DOCUMENT_END -> "加载后立即"
                    else -> "加载空闲时"
                }
            }运行(${js.length} 字符)。" +
                "刷新已打开的页面生效;脚本文件保存在插件目录中,可在「浏览器→脚本」里管理。"
        )
    }

    private fun persistAsUserscript(slug: String, scriptText: String): Boolean {
        val ctx = ClawApplication.instance
        val dir = File(ctx.filesDir, "generated_apps/$slug").apply { mkdirs() }
        val fileName = "$slug.user.js"
        File(dir, fileName).writeText(scriptText, Charsets.UTF_8)

        val manifest = JSONObject()
            .put("id", slug)
            .put("name", slug)
            .put("version", "1.0.0")
            .put("type", "browser-script")
            .put("script", fileName)
        File(dir, "manifest.json").writeText(manifest.toString(2), Charsets.UTF_8)

        ctx.pluginManager.refreshNonDexPlugins()
        return true
    }

    @Deprecated("legacy inline-JSON persistence; use persistAsUserscript")
    private fun persist(name: String, hostPattern: String, js: String): Boolean {
        val ctx = ClawApplication.instance
        val f = BrowserPluginHost.manifestFile(ctx)
        val root = if (f.isFile) runCatching { JSONObject(f.readText()) }.getOrNull() ?: JSONObject() else JSONObject()
        val arr = root.optJSONArray("plugins") ?: JSONArray()
        arr.put(
            JSONObject()
                .put("id", "genplug_" + System.currentTimeMillis())
                .put("name", name)
                .put("hostPattern", hostPattern)
                .put("js", js)
                .put("enabled", true),
        )
        root.put("plugins", arr)
        if (!root.has("stealthEnabled")) root.put("stealthEnabled", true)
        f.writeText(root.toString())
        BrowserPluginHost.loadFromFile(ctx)
        return true
    }

    private fun userscriptPrompt(desc: String, host: String) = """
写一段标准 Tampermonkey/油猴 userscript(.user.js 格式),实现:「$desc」${if (host.isNotBlank()) " (适用站点: $host)" else ""}

输出完整的 .user.js 文件内容,以 ==UserScript== 开头、==/UserScript== 结尾。要求:
1. 元数据块必须包含 @name @version @description @match(必填,格式如 "https://*.example.com/*" 或 "*://*/*"),
   可选 @author @run-at(document-start/document-end/document-idle,默认 document-idle) @grant。
2. @grant 根据脚本需要声明;常用 API:GM_addStyle(注入CSS)/GM_setValue/GM_getValue(存储)/GM_xmlhttpRequest(跨域请求)/
   GM_setClipboard(写剪贴板)/GM_notification(通知)/GM_openInTab(新标签)。不跨域不需要 GM_xmlhttpRequest。
3. 脚本正文写在 IIFE 里:(function(){ ... })();。
4. 防御式编程:DOM 元素可能不存在先判空,用 MutationObserver 等动态元素,全部 try/catch 包裹,不要用 alert。
5. ${if (host.isNotBlank()) "@match 必须精确匹配目标站点,不要用 *://*/*" else "如用户没指定站点,@match 用 *://*/*"}
6. 只输出 userscript 源代码,不要 markdown 代码块、不要额外解释、不要 JSON。
""".trimIndent()

    private fun callLlm(eff: EffectiveLlm, prompt: String): String {
        val url = eff.baseUrl.trimEnd('/') + "/chat/completions"
        val body = JSONObject().apply {
            put("model", eff.model)
            put("temperature", 0.3)
            put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
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

    private fun extractUserscript(raw: String): String {
        val fenced = Regex("```(?:javascript|js)?\\s*([\\s\\S]*?)```").find(raw)?.groupValues?.get(1)
        val s = (fenced ?: raw).trim()
        val startMarker = "==UserScript=="
        val endMarker = "==/UserScript=="
        val start = s.indexOf(startMarker)
        val end = s.indexOf(endMarker)
        if (start < 0 || end < start) return ""
        return s.substring(start)
    }

    override fun getDescriptionEN() = """
        Generate a Tampermonkey-style userscript (.user.js) from a natural-language description and install it.
        The script supports standard GM_* APIs (GM_addStyle/GM_xmlhttpRequest/GM_setValue/GM_notification/etc.)
        and auto-runs on matched sites. Use for customizing/automating web pages.
    """.trimIndent()

    override fun getDescriptionCN() = """
        把一句话描述变成一个**标准油猴脚本**(.user.js)并安装到浏览器。
        脚本支持 GM_addStyle/GM_xmlhttpRequest/GM_setValue/GM_notification 等 GM_* API,
        @match 匹配的站点加载后按 @run-at 时机自动运行。用于改造/自动化网页。
    """.trimIndent()
}
