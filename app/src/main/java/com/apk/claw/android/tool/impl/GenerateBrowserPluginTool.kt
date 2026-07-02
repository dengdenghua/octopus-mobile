package com.apk.claw.android.tool.impl

import com.apk.claw.android.ClawApplication
import com.apk.claw.android.account.EffectiveLlm
import com.apk.claw.android.account.LlmRouting
import com.apk.claw.android.octopus_mobile.browser.BrowserPluginHost
import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult
import com.apk.claw.android.utils.OctoHttp
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 「一句话生成浏览器脚本插件」—— 放大浏览器控制这条护城河:NL → 一段注入网页的内容脚本
 * (content script),写进 [BrowserPluginHost] 的本地清单,匹配域名的页面加载后自动运行。
 *
 * 安全:生成物是**注入真实网页**的 JS(能读页面数据),风险面等同 browser_install_extension,
 * 故登记为 HIGH —— 不可信来源(远程/LAN 控制台)调用会走高危来源闸门(审批/BLOCK),
 * 防远端静默给用户网页植入偷数据的脚本。脚本跑在页面上下文,拿不到 app 内部桥。
 */
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
            "What the userscript should do on web pages (e.g. 'hide the sidebar ads on this site', " +
                "'add a copy-code button to code blocks', 'auto-expand all comments').",
            true,
        ),
        ToolParameter(
            "host", "string",
            "Optional site/host to limit the script to (e.g. 'example.com'). Omit for all sites.",
            false,
        ),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val desc = requireString(params, "description").take(2000)
        val host = optionalString(params, "host", "").trim()
        val eff = LlmRouting.effective()
        if (eff.apiKey.isBlank()) return ToolResult.error("未配置模型，无法生成脚本")

        val raw = runCatching { callLlm(eff, pluginPrompt(desc, host)) }
            .getOrElse { return ToolResult.error("生成脚本失败: ${it.message}") }
        val obj = runCatching { JSONObject(extractJson(raw)) }
            .getOrElse { return ToolResult.error("脚本格式解析失败") }

        val name = obj.optString("name").trim().ifBlank { "网页脚本" }.take(40)
        var js = obj.optString("js").trim()
        if (js.isBlank()) return ToolResult.error("脚本正文为空")
        if (js.length > MAX_JS_LEN) js = js.take(MAX_JS_LEN)
        // host 参数优先:把域名转成匹配 host 的正则(转义点);否则用 LLM 给的 host_pattern。
        val hostPattern = when {
            host.isNotBlank() -> hostToRegex(host)
            else -> obj.optString("host_pattern").trim().ifBlank { "*" }
        }

        val saved = runCatching { persist(name, hostPattern, js) }.getOrDefault(false)
        if (!saved) return ToolResult.error("脚本落盘失败")

        val where = if (hostPattern == "*") "所有网页" else "匹配 `$hostPattern` 的网页"
        return ToolResult.success(
            "已生成网页脚本插件「$name」并启用,将在$where 加载后运行(${js.length} 字符)。" +
                "已在打开的页面请重新加载生效;可在浏览器插件设置里管理。",
        )
    }

    /** 追加到 filesDir/registry/browser_plugins.json 并让 BrowserPluginHost 立即重载。 */
    private fun persist(name: String, hostPattern: String, js: String): Boolean {
        val ctx = ClawApplication.instance
        val f = BrowserPluginHost.manifestFile(ctx)
        val root = if (f.isFile) runCatching { JSONObject(f.readText()) }.getOrNull() ?: JSONObject() else JSONObject()
        val arr = root.optJSONArray("plugins") ?: JSONArray()
        arr.put(
            JSONObject()
                .put("id", "genplug_" + System.currentTimeMillis())
                .put("name", name)
                .put("hostPattern", hostPattern)   // 字段名须与 BrowserPluginHost.PluginDef 一致
                .put("js", js)
                .put("enabled", true),
        )
        root.put("plugins", arr)
        if (!root.has("stealthEnabled")) root.put("stealthEnabled", true)
        f.writeText(root.toString())
        BrowserPluginHost.loadFromFile(ctx)   // 立即生效(内存快照更新)
        return true
    }

    /** 把 host(可能带 scheme/path)转成匹配 host 的正则:取 host 段、转义点。 */
    private fun hostToRegex(raw: String): String {
        val h = raw.removePrefix("https://").removePrefix("http://").substringBefore('/').trim()
        return if (h.isBlank()) "*" else h.replace(".", "\\.")
    }

    private fun pluginPrompt(desc: String, host: String) = """
        写一段在网页里运行的**内容脚本(content script)**,实现:「$desc」${if (host.isNotBlank()) "(仅用于 $host)" else ""}
        约束:
        - 纯浏览器 JS,包在 IIFE 里 `(function(){ ... })();`;不要 import、不要外部 CDN。
        - 脚本在页面 DOM 就绪后(相当于 DOMContentLoaded 之后)注入运行;要**防御式**写:元素可能
          不存在,先判空;可用 MutationObserver 等待动态元素。
        - 只操作页面本身,别弹恼人的 alert;失败要静默(try/catch),别把页面搞崩。
        输出**严格 JSON**(不要代码块、不要多余解释):
        {"name":"简短名","host_pattern":"匹配 host 的正则,*=全部","js":"脚本正文"}
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

    private fun extractJson(raw: String): String {
        val fenced = Regex("```(?:json)?\\s*([\\s\\S]*?)```").find(raw)?.groupValues?.get(1)
        val s = (fenced ?: raw).trim()
        val start = s.indexOf('{'); val end = s.lastIndexOf('}')
        return if (start >= 0 && end > start) s.substring(start, end + 1) else s
    }

    override fun getDescriptionEN() = """
        Generate a browser userscript (content script) from a natural-language description and install it
        into the app's browser. It runs automatically on matching sites after page load. Use when the user
        wants to customize/automate a web page — hide elements, add buttons, auto-click, tweak layout.
    """.trimIndent()

    override fun getDescriptionCN() = """
        把一句话描述变成一个**浏览器用户脚本**(内容脚本)并装进 App 浏览器,匹配的网站加载后自动运行。
        适用:用户想改造/自动化某个网页——隐藏元素、加按钮、自动点击、调布局等。
    """.trimIndent()
}
