package com.apk.claw.android.tool.impl

import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult
import com.apk.claw.android.utils.OctoHttp
import com.apk.claw.android.utils.XLog
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

/**
 * 搜图工具 —— 给「产品设计工作流」[[octopus-design-workflow]] 配**真实素材**。两条来源都不需要 API key:
 *  - type=photo(默认):Openverse 免费 CC 图库,返回可商用的真实照片 URL。
 *  - type=logo:Clearbit Logo API 按域名取品牌 logo;域名给不出就退回图库搜「xx logo」。
 *
 * 与 [GenerateImageTool] 的分工:这里是**搜现成真实图**(照片/品牌 logo),不烧积分;
 * 要 AI 画的插画/海报/头像用 generate_image。返回的 URL 供 generate_app 的 assets 参数焊进页面。
 */
@Suppress("TooManyFunctions") // 工具本体 + 一堆小的私有 HTTP/解析辅助,拆开反而更碎
class SearchImageTool : BaseTool() {

    override fun getName(): String = "search_image"

    override fun getDisplayName(): String = if (useChineseDescription) "搜图" else "Search Image"

    override fun getDescriptionEN(): String =
        "Search the web for REAL images to embed in pages/apps (no credits). type=photo searches free " +
            "commercial-use stock photos (Openverse); type=logo fetches a brand logo by domain (Clearbit). " +
            "Returns image URLs. For AI-drawn illustrations/posters use generate_image instead."

    override fun getDescriptionCN(): String =
        "联网搜真实图片用于嵌进网页/应用(不烧积分)。type=photo 搜免费可商用图库照片;type=logo 按域名取品牌 logo。" +
            "返回图片链接;要 AI 生成的插画/海报请改用 generate_image。"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter("query", "string", "搜索关键词(中英文均可),如 '拿铁 咖啡'、'mountain sunrise'、'github'。", true),
        ToolParameter("type", "string", "photo(默认,搜图库照片)或 logo(取品牌 logo)。", false),
        ToolParameter("domain", "string", "type=logo 时的品牌域名,如 'github.com';缺省则按 query 猜测。", false),
        ToolParameter("count", "string", "返回数量,默认 3、最多 6(仅 photo 生效)。", false),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val query = params["query"]?.toString()?.trim().orEmpty()
        if (query.isEmpty()) return ToolResult.error("缺少 query(搜索关键词)")
        val type = params["type"]?.toString()?.trim()?.lowercase().orEmpty()
        return if (type == "logo") {
            searchLogo(query, params["domain"]?.toString()?.trim().orEmpty())
        } else {
            val count = (params["count"]?.toString()?.toIntOrNull() ?: DEFAULT_COUNT).coerceIn(1, MAX_COUNT)
            searchPhotos(query, count)
        }
    }

    @Suppress("ReturnCount")
    private fun searchPhotos(query: String, count: Int): ToolResult {
        val url = "$OPENVERSE/v1/images/?q=${enc(query)}&page_size=$count&license_type=commercial&mature=false"
        val body = httpGet(url) ?: return ToolResult.error("搜图失败:网络错误或图库暂不可用")
        val results = parsePhotos(body, query)
        if (results.isEmpty()) return ToolResult.error("没搜到「$query」相关的图片,可换个关键词或改用 generate_image")
        val md = results.mapIndexed { i, (u, t) -> "${i + 1}. ![$t]($u)\n   $u" }.joinToString("\n")
        return ToolResult.success("搜到 ${results.size} 张「$query」的真实图片(URL 可直接用到 <img src>):\n$md")
    }

    private fun parsePhotos(body: String, query: String): List<Pair<String, String>> = runCatching {
        val arr = JSONObject(body).optJSONArray("results") ?: return@runCatching emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val u = o.optString("url").ifBlank { o.optString("thumbnail") }
            if (u.isBlank()) null else u to o.optString("title").ifBlank { query }
        }
    }.getOrDefault(emptyList())

    @Suppress("ReturnCount")
    private fun searchLogo(query: String, domainRaw: String): ToolResult {
        val domain = domainRaw.ifBlank { guessDomain(query) }
        // 质量优先:Clearbit 透明 PNG(不稳,可能不可达)→ Google favicon(本仓已在用,稳)→ 退图库搜。
        val clearbit = "$CLEARBIT/$domain?size=200"
        if (httpOk(clearbit)) return logoResult(query, domain, clearbit)
        val favicon = "$FAVICON?sz=128&domain=${enc(domain)}"
        if (httpOk(favicon)) return logoResult(query, domain, favicon)
        return searchPhotos("$query logo", DEFAULT_COUNT)
    }

    private fun logoResult(query: String, domain: String, url: String): ToolResult =
        ToolResult.success("品牌 logo($domain,URL 可直接用到 <img src>):\n![$query logo]($url)\n$url")

    private fun guessDomain(query: String): String {
        val q = query.trim().lowercase()
        return if (q.contains('.')) q.substringBefore(' ') else q.replace(" ", "") + ".com"
    }

    private fun httpGet(url: String): String? = runCatching {
        val req = Request.Builder().url(url)
            .header("User-Agent", UA).header("Accept", "application/json").get().build()
        http.newCall(req).execute().use { resp -> if (resp.isSuccessful) resp.body?.string() else null }
    }.onFailure { XLog.w(TAG, "search GET failed: ${it.message}") }.getOrNull()

    private fun httpOk(url: String): Boolean = runCatching {
        val req = Request.Builder().url(url).header("User-Agent", UA).head().build()
        http.newCall(req).execute().use { it.isSuccessful }
    }.getOrDefault(false)

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    companion object {
        private const val TAG = "SearchImageTool"
        private const val OPENVERSE = "https://api.openverse.org"
        private const val CLEARBIT = "https://logo.clearbit.com"
        private const val FAVICON = "https://www.google.com/s2/favicons"
        private const val UA = "OctopusMobile/1.0 (design-workflow image search)"
        private const val DEFAULT_COUNT = 3
        private const val MAX_COUNT = 6
        private val http = OctoHttp.shared
    }
}
