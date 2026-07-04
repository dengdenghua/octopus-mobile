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

    /** 默认角色(Octopus 本体,无人设扮演)。历史数据没有 character 字段,视同本体。 */
    const val CHARACTER_DEFAULT = "octopus"

    /**
     * @param character 会话归属的角色 id(TV 模式同一批角色)。会话/历史按角色隔离,
     *   互不可见。旧数据经 Gson 反序列化可能为 null —— 一律经 [charKey] 读。
     */
    data class SessionMeta(
        val id: String,
        var title: String,
        var updatedAt: Long,
        val character: String? = null,
    ) {
        /** 归一化角色 key:null/空(历史数据)→ 默认 Octopus。 */
        fun charKey(): String = character?.takeIf { it.isNotBlank() } ?: CHARACTER_DEFAULT
    }

    /** 「当前会话」指针按角色分键;octopus 沿用旧键,老用户当前会话不丢。 */
    private fun currentKey(character: String): String =
        if (character == CHARACTER_DEFAULT) KEY_CURRENT else KEY_CURRENT + "_" + character

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

    fun currentId(character: String = CHARACTER_DEFAULT): String? =
        KVUtils.getString(currentKey(character), "").takeIf { it.isNotBlank() }

    fun setCurrent(id: String, character: String = CHARACTER_DEFAULT) {
        KVUtils.putString(currentKey(character), id)
    }

    /** 保证该角色至少有一个会话存在,返回**该角色**的会话列表(隔离视图)。 */
    fun ensureAtLeastOne(
        now: Long,
        demo: List<ChatMessage>,
        character: String = CHARACTER_DEFAULT,
    ): MutableList<SessionMeta> {
        val list = index()
        val mine = list.filter { it.charKey() == character }.toMutableList()
        if (mine.isEmpty()) {
            val meta = SessionMeta(newId(), "新对话", now, character)
            list.add(0, meta)
            saveIndex(list)
            setCurrent(meta.id, character)
            ChatStore.save(meta.id, demo)
            return mutableListOf(meta)
        }
        // 遗留 Demo 会话改名只存在于老(octopus)数据。
        if (character == CHARACTER_DEFAULT) {
            val demoSession = mine.firstOrNull { it.title == "Demo" }
            if (demoSession != null) {
                demoSession.title = "新对话"
                demoSession.updatedAt = now
                saveIndex(list)
                ChatStore.clear(demoSession.id)
            }
        }
        return mine
    }

    /** 新建该角色的空会话并设为其当前会话,返回其 meta。 */
    fun create(now: Long, character: String = CHARACTER_DEFAULT): SessionMeta {
        val list = index()
        val meta = SessionMeta(newId(), "新对话", now, character)
        list.add(0, meta)
        saveIndex(list)
        setCurrent(meta.id, character)
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
