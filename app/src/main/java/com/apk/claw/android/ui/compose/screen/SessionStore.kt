package com.apk.claw.android.ui.compose.screen

import com.apk.claw.android.utils.KVUtils
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.concurrent.atomic.AtomicLong

/**
 * 多会话管理:维护会话索引(id/标题/更新时间)与「当前会话」,消息本身交给 [ChatStore]。
 *
 * 索引与当前 id 存 MMKV;首次使用自动建一个默认空会话。
 */
object SessionStore {

    private const val KEY_INDEX = "chat_sessions_index"
    private const val KEY_CURRENT = "chat_current_session"
    private val gson = Gson()
    private val seq = AtomicLong(0L)

    data class SessionMeta(val id: String, var title: String, var updatedAt: Long)

    /** 生成会话 id(用计数器 + KVUtils 自增,避开被禁用的 Date/random)。 */
    private fun newId(): String {
        val n = KVUtils.getString("chat_session_seq", "0").toLongOrNull() ?: 0L
        val next = maxOf(n, seq.incrementAndGet()) + 1
        KVUtils.putString("chat_session_seq", next.toString())
        return "s$next"
    }

    fun index(): MutableList<SessionMeta> {
        val json = KVUtils.getString(KEY_INDEX, "")
        if (json.isBlank()) return mutableListOf()
        return runCatching {
            gson.fromJson<List<SessionMeta>>(json, object : TypeToken<List<SessionMeta>>() {}.type).toMutableList()
        }.getOrNull()?.toMutableList() ?: mutableListOf()
    }

    fun saveIndex(list: List<SessionMeta>) {
        runCatching { KVUtils.putString(KEY_INDEX, gson.toJson(list)) }
    }

    fun currentId(): String? = KVUtils.getString(KEY_CURRENT, "").takeIf { it.isNotBlank() }
    fun setCurrent(id: String) { KVUtils.putString(KEY_CURRENT, id) }

    /** 保证至少有一个会话存在,返回完整索引。 */
    fun ensureAtLeastOne(now: Long, demo: List<ChatMessage>): MutableList<SessionMeta> {
        val list = index()
        if (list.isEmpty()) {
            val meta = SessionMeta(newId(), "新对话", now)
            list.add(meta)
            saveIndex(list)
            setCurrent(meta.id)
            ChatStore.save(meta.id, demo)
        } else {
            val demoSession = list.firstOrNull { it.title == "Demo" }
            if (demoSession != null) {
                demoSession.title = "新对话"
                demoSession.updatedAt = now
                saveIndex(list)
                ChatStore.clear(demoSession.id)
            }
        }
        return list
    }

    /** 新建空会话并设为当前,返回其 meta。 */
    fun create(now: Long): SessionMeta {
        val list = index()
        val meta = SessionMeta(newId(), "新对话", now)
        list.add(0, meta)
        saveIndex(list)
        setCurrent(meta.id)
        return meta
    }

    fun updateMeta(id: String, title: String, now: Long) {
        val list = index()
        list.find { it.id == id }?.let { it.title = title; it.updatedAt = now }
        saveIndex(list)
    }

    fun delete(id: String) {
        val list = index().filterNot { it.id == id }
        saveIndex(list)
        ChatStore.clear(id)
    }
}
