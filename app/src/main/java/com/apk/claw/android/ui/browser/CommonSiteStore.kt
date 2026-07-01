package com.apk.claw.android.ui.browser

import com.apk.claw.android.utils.KVUtils
import com.apk.claw.android.utils.XLog
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 浏览器首页"常用网站"小节的数据源 —— MMKV 持久化，跟 [BookmarkManager] 一个套路。
 *
 * 首次读取(还没有存储记录)时用内置默认站点填充；一旦用户增删过一次，[save] 落了盘，
 * 之后一律以存储为准，哪怕删空了也不会再回落默认值(空 JSON 数组 "[]" 与"从未写过"能分清)。
 *
 * 增：走浏览器内"收藏"弹窗的"添加到主页"，不在首页单独做加号入口——加的时候页面已经打开，
 * 标题/网址都是现成的，不用用户手填。删：首页 tile 长按。
 *
 * 单例([object]):所有调用方共享同一份内存缓存,避免每个 `CommonSiteStore()` 实例各自
 * 维护一份 cache 导致增删后其它实例仍读到旧值(与 [BookmarkManager] 同一方案)。
 * 读写方法加 @Synchronized 防 read-modify-write 竞态。
 */
object CommonSiteStore {

    private const val KEY_SITES = "BROWSER_COMMON_SITES"

    private val gson = Gson()
    private var cache: MutableList<CommonSiteItem>? = null

    @Synchronized
    fun getAll(): List<CommonSiteItem> {
        if (cache == null) {
            cache = loadFromStorage().toMutableList()
        }
        return cache!!.toList()
    }

    @Synchronized
    fun add(url: String, title: String) {
        val list = getAll().toMutableList()
        if (list.any { it.url == url }) return
        list.add(CommonSiteItem(url, title, System.currentTimeMillis()))
        save(list)
    }

    @Synchronized
    fun remove(url: String) {
        val list = getAll().toMutableList()
        list.removeAll { it.url == url }
        save(list)
    }

    fun contains(url: String): Boolean = getAll().any { it.url == url }

    private fun save(list: List<CommonSiteItem>) {
        cache = list.toMutableList()
        KVUtils.putString(KEY_SITES, gson.toJson(list))
    }

    private fun loadFromStorage(): List<CommonSiteItem> {
        val json = KVUtils.getString(KEY_SITES, "")
        if (json.isEmpty()) return defaultSites()
        return try {
            val type = object : TypeToken<List<CommonSiteItem>>() {}.type
            gson.fromJson(json, type) ?: defaultSites()
        } catch (e: Exception) {
            XLog.w("CommonSiteStore", "load common sites failed", e)
            defaultSites()
        }
    }

    private fun defaultSites(): List<CommonSiteItem> = listOf(
        CommonSiteItem("https://chat.deepseek.com", "DeepSeek"),
        CommonSiteItem("https://tongyi.aliyun.com/qianwen", "通义千问"),
        CommonSiteItem("https://www.youtube.com", "YouTube"),
        CommonSiteItem("https://github.com", "GitHub"),
        CommonSiteItem("https://www.bilibili.com", "Bilibili"),
        CommonSiteItem("https://www.perplexity.ai", "Perplexity"),
    )
}

data class CommonSiteItem(
    val url: String,
    val title: String,
    val addedTs: Long = System.currentTimeMillis(),
)
