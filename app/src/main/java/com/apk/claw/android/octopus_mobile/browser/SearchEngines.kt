package com.apk.claw.android.octopus_mobile.browser

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 搜索引擎定义。发现页搜索框与内置浏览器共用同一份配置，
 * 用户选择持久化在 KVUtils.getSearchEngine()。
 */
data class SearchEngine(
    val id: String,
    /** 下拉菜单显示名 */
    val label: String,
    /** 搜索框上的短标记 */
    val tag: String,
    private val queryTemplate: String,
    /** 引擎首页（浏览器 Home 按钮 / 空查询时使用） */
    val home: String,
) {
    /** 官方图标（favicon），运行时加载，避免打包版权 logo。 */
    val favicon: String get() = "$home/favicon.ico"

    fun searchUrl(query: String): String =
        queryTemplate + URLEncoder.encode(query, "UTF-8")

    /** 若 url 是本引擎的搜索结果页，返回解码后的关键词，否则 null */
    fun extractQuery(url: String): String? {
        if (!url.startsWith(queryTemplate)) return null
        val raw = url.substring(queryTemplate.length).substringBefore('&')
        if (raw.isEmpty()) return null
        return try { URLDecoder.decode(raw, "UTF-8") } catch (e: Exception) { raw }
    }
}

object SearchEngines {
    val ALL = listOf(
        SearchEngine("google", "Google", "G", "https://www.google.com/search?q=", "https://www.google.com"),
        SearchEngine("bing", "Bing", "b", "https://www.bing.com/search?q=", "https://www.bing.com"),
        SearchEngine("baidu", "百度", "百", "https://www.baidu.com/s?wd=", "https://www.baidu.com"),
        SearchEngine("duckduckgo", "DuckDuckGo", "D", "https://duckduckgo.com/?q=", "https://duckduckgo.com"),
    )

    val DEFAULT = ALL[0]

    fun byId(id: String?): SearchEngine = ALL.firstOrNull { it.id == id } ?: DEFAULT

    /** 任一引擎能从该 url 解出关键词则返回，否则 null（用于地址栏 omnibox 显示） */
    fun extractQuery(url: String): String? {
        for (e in ALL) e.extractQuery(url)?.let { return it }
        return null
    }
}
