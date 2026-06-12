package com.apk.claw.android.octopus_mobile.browser

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
    fun searchUrl(query: String): String =
        queryTemplate + URLEncoder.encode(query, "UTF-8")
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
}
