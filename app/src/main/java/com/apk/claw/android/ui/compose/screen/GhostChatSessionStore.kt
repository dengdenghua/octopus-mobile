package com.apk.claw.android.ui.compose.screen

import com.apk.claw.android.utils.KVUtils
import com.google.gson.Gson

internal object GhostChatSessionStore {
    private const val PREFIX = "ghost_chat_persona_"
    private val gson = Gson()

    fun openSession(feed: UniverseFeedDto): String {
        val now = System.currentTimeMillis()
        val meta = SessionStore.create(now)
        val title = titleFor(feed)
        SessionStore.updateMeta(meta.id, title, now)
        save(meta.id, feed)
        ChatStore.save(
            meta.id,
            listOf(
                ChatMessage.AgentMessage(
                    text = "已连接 ${feed.characterName} / ${feed.codename}。你现在正在和 ECHO 宇宙中的 Ghost 对话。",
                    id = now,
                )
            ),
        )
        return meta.id
    }

    fun load(sessionId: String): UniverseFeedDto? {
        if (sessionId.isBlank()) return null
        val json = KVUtils.getString(key(sessionId), "")
        if (json.isBlank()) return null
        return runCatching { gson.fromJson(json, UniverseFeedDto::class.java) }.getOrNull()
    }

    fun clear(sessionId: String) {
        if (sessionId.isNotBlank()) KVUtils.putString(key(sessionId), "")
    }

    fun titleFor(feed: UniverseFeedDto): String = "Ghost · ${feed.characterName}".take(24)

    private fun save(sessionId: String, feed: UniverseFeedDto) {
        runCatching { KVUtils.putString(key(sessionId), gson.toJson(feed)) }
    }

    private fun key(sessionId: String): String = PREFIX + sessionId
}
