package com.apk.claw.android.ui.compose.screen

import com.apk.claw.android.utils.KVUtils
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 单个会话的消息持久化（MMKV，按 sessionId 分键）。
 *
 * 「Thinking」是运行期占位，不持久化。每个会话最多保留最近 [MAX_KEEP] 条。
 */
object ChatStore {

    private const val PREFIX = "chat_msgs_"
    private const val MAX_KEEP = 200
    private val gson = Gson()

    private data class Dto(
        val type: String,
        val a: String = "", val b: String = "", val c: String = "", val d: String = "",
    )

    private fun key(sessionId: String) = PREFIX + sessionId

    fun save(sessionId: String, messages: List<ChatMessage>) {
        val dtos = messages.takeLast(MAX_KEEP).mapNotNull { m ->
            when (m) {
                is ChatMessage.UserMessage -> Dto("user", m.text)
                is ChatMessage.AgentMessage -> Dto("agent", m.text)
                is ChatMessage.ToolCall -> Dto("tool", m.icon, m.toolName, m.args, m.result ?: "")
                is ChatMessage.Thinking -> null
            }
        }
        runCatching { KVUtils.putString(key(sessionId), gson.toJson(dtos)) }
    }

    fun load(sessionId: String): List<ChatMessage>? {
        val json = KVUtils.getString(key(sessionId), "")
        if (json.isBlank()) return null
        return runCatching {
            val dtos: List<Dto> = gson.fromJson(json, object : TypeToken<List<Dto>>() {}.type)
            dtos.map { d ->
                when (d.type) {
                    "user" -> ChatMessage.UserMessage(d.a)
                    "tool" -> ChatMessage.ToolCall(d.a, d.b, d.c, d.d.ifBlank { null })
                    else -> ChatMessage.AgentMessage(d.a)
                }
            }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    fun clear(sessionId: String) {
        runCatching { KVUtils.putString(key(sessionId), "") }
    }
}
