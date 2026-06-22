package com.apk.claw.android.ui.browser

import com.apk.claw.android.utils.KVUtils
import com.apk.claw.android.utils.XLog
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 书签管理器 —— MMKV 持久化。
 */
class BookmarkManager {

    companion object {
        private const val KEY_BOOKMARKS = "BROWSER_BOOKMARKS"
    }

    private val gson = Gson()
    private var cache: MutableList<BookmarkItem>? = null

    fun getAll(): List<BookmarkItem> {
        if (cache == null) {
            cache = loadFromStorage().toMutableList()
        }
        return cache!!.toList()
    }

    fun add(url: String, title: String) {
        val list = getAll().toMutableList()
        if (list.any { it.url == url }) return
        list.add(BookmarkItem(url, title, System.currentTimeMillis()))
        save(list)
    }

    fun remove(url: String) {
        val list = getAll().toMutableList()
        list.removeAll { it.url == url }
        save(list)
    }

    fun findByUrl(url: String): BookmarkItem? {
        return getAll().find { it.url == url }
    }

    fun isBookmarked(url: String): Boolean {
        return findByUrl(url) != null
    }

    private fun save(list: List<BookmarkItem>) {
        cache = list.toMutableList()
        KVUtils.putString(KEY_BOOKMARKS, gson.toJson(list))
    }

    private fun loadFromStorage(): List<BookmarkItem> {
        val json = KVUtils.getString(KEY_BOOKMARKS, "")
        if (json.isEmpty()) return emptyList()
        return try {
            val type = object : TypeToken<List<BookmarkItem>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            XLog.w("BookmarkManager", "load bookmarks failed", e)
            emptyList()
        }
    }
}

data class BookmarkItem(
    val url: String,
    val title: String,
    val addedTs: Long = System.currentTimeMillis()
)
