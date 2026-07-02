package com.apk.claw.android.ui.browser

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 浏览器标签(多窗口)的**共享数据源** —— 竖屏 [BrowserActivity] 与桌面模式浏览器共用同一组标签,
 * 打通两个浏览前台(在竖屏开的标签,进桌面也在)。只存数据(id/url/title + 当前 id),
 * 具体导航由各宿主拿当前标签的 url 自己驱动引擎(单引擎、切换即导航)。
 */
object BrowserTabsStore {

    data class Tab(val id: Long, val url: String, val title: String)

    private val _tabs = MutableStateFlow<List<Tab>>(emptyList())
    val tabs: StateFlow<List<Tab>> = _tabs

    private val _currentId = MutableStateFlow(-1L)
    val currentId: StateFlow<Long> = _currentId

    private var seq = 0L

    fun list(): List<Tab> = _tabs.value
    fun count(): Int = _tabs.value.size
    fun current(): Tab? = _tabs.value.firstOrNull { it.id == _currentId.value }

    /** 保证至少有一个标签;返回当前标签。 */
    @Synchronized
    fun ensureAtLeastOne(defaultTitle: String): Tab {
        if (_tabs.value.isEmpty()) return newTab(defaultTitle)
        return current() ?: _tabs.value.first().also { _currentId.value = it.id }
    }

    /** 新建空白标签并设为当前;返回它。 */
    @Synchronized
    fun newTab(title: String): Tab {
        val t = Tab(seq++, "", title)
        _tabs.value = _tabs.value + t
        _currentId.value = t.id
        return t
    }

    @Synchronized
    fun select(id: Long) {
        if (_tabs.value.any { it.id == id }) _currentId.value = id
    }

    /** 关闭标签;若清空则自动补一个新标签;若关的是当前标签则切到最后一个。 */
    @Synchronized
    fun close(id: Long, defaultTitle: String) {
        val wasCurrent = id == _currentId.value
        _tabs.value = _tabs.value.filter { it.id != id }
        if (_tabs.value.isEmpty()) { newTab(defaultTitle); return }
        if (wasCurrent) _currentId.value = _tabs.value.last().id
    }

    /** 更新当前标签的 url/title(导航完成时调用)。 */
    @Synchronized
    fun updateCurrent(url: String, title: String) {
        val id = _currentId.value
        _tabs.value = _tabs.value.map { if (it.id == id) it.copy(url = url, title = title) else it }
    }
}
