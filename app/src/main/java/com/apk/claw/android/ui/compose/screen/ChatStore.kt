package com.apk.claw.android.ui.compose.screen

import com.apk.claw.android.utils.KVUtils
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 对话历史持久化（MMKV）。
 *
 * 把 [ChatMessage] 列表序列化进 KVUtils，重启 App / 切走再回来都不丢。
 * 「Thinking」是运行期占位，不持久化。
 */
object ChatStore {

    private const val KEY = "chat_history_v1"
    private val gson = Gson()

    /** 扁平 DTO：用 type 区分消息种类，避免 sealed class 的多态序列化问题。 */
    private data class Dto(
        val type: String,
        val a: String = "",   // user/agent: text;  tool: icon
        val b: String = "",   // tool: 工具名
        val c: String = "",   // tool: 参数
        val d: String = "",   // tool: 结果
    )

    fun save(messages: List<ChatMessage>) {
        val dtos = messages.mapNotNull { m ->
            when (m) {
                is ChatMessage.UserMessage -> Dto("user", m.text)
                is ChatMessage.AgentMessage -> Dto("agent", m.text)
                is ChatMessage.ToolCall -> Dto("tool", m.icon, m.toolName, m.args, m.result ?: "")
                is ChatMessage.Thinking -> null   // 运行期占位，不存
            }
        }
        runCatching { KVUtils.putString(KEY, gson.toJson(dtos)) }
    }

    /** 读取历史；无历史返回 null（首启回退到演示数据）。 */
    fun load(): List<ChatMessage>? {
        val json = KVUtils.getString(KEY, "")
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

    fun clear() {
        runCatching { KVUtils.putString(KEY, "") }
    }
}
