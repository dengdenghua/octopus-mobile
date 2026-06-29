package com.apk.claw.android.agent

import com.apk.claw.android.utils.KVUtils
import com.apk.claw.android.utils.XLog
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage

/**
 * 任务检查点持久化 —— App 被系统杀或崩溃后，下次启动可恢复未完成的任务上下文。
 *
 * 工作原理：
 * 1. runAgentLoop 开始时保存 checkpoint（goal + messages + 迭代进度）
 * 2. 每轮迭代结束后更新 checkpoint
 * 3. 任务完成/取消/出错时清除 checkpoint
 * 4. App 重启后检查是否有 pending checkpoint，提示用户恢复
 *
 * 消息序列化格式：
 * - SystemMessage → {"type":"system","text":"..."}
 * - UserMessage    → {"type":"user","text":"..."}
 * - AiMessage      → {"type":"ai","text":"...","toolRequests":[{...}]}
 * - ToolResult     → {"type":"tool_result","id":"...","toolName":"...","text":"..."}
 */
object TaskCheckpoint {

    private const val TAG = "TaskCheckpoint"
    private const val KEY_CHECKPOINT = "KEY_TASK_CHECKPOINT"
    private val GSON = Gson()

    data class CheckpointData(
        val goal: String,
        val iterations: Int,
        val goalRepairsLeft: Int,
        val untrusted: Boolean,
        val timestamp: Long,
        val messages: List<ChatMessage>,
    )

    // ==================== 保存 / 加载 / 清除 ====================

    fun save(
        goal: String,
        iterations: Int,
        goalRepairsLeft: Int,
        untrusted: Boolean,
        messages: List<ChatMessage>,
    ) {
        try {
            val messagesJson = JsonArray().apply {
                messages.forEach { msg -> add(serializeMessage(msg)) }
            }
            val json = JsonObject().apply {
                addProperty("goal", goal)
                addProperty("iterations", iterations)
                addProperty("goalRepairsLeft", goalRepairsLeft)
                addProperty("untrusted", untrusted)
                addProperty("timestamp", System.currentTimeMillis())
                add("messages", messagesJson)
            }
            KVUtils.putString(KEY_CHECKPOINT, json.toString())
            XLog.i(TAG, "Checkpoint saved: goal='${goal.take(40)}...', iterations=$iterations, messages=${messages.size}")
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to save checkpoint", e)
        }
    }

    fun load(): CheckpointData? {
        try {
            val raw = KVUtils.getString(KEY_CHECKPOINT)
            if (raw.isEmpty()) return null
            val json = JsonParser.parseString(raw).asJsonObject
            val messages = repairDanglingToolCalls(deserializeMessages(json.getAsJsonArray("messages")))
            return CheckpointData(
                goal = json.get("goal")?.asString ?: return null,
                iterations = json.get("iterations")?.asInt ?: 0,
                goalRepairsLeft = json.get("goalRepairsLeft")?.asInt ?: 2,
                untrusted = json.get("untrusted")?.asBoolean ?: false,
                timestamp = json.get("timestamp")?.asLong ?: 0,
                messages = messages,
            )
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to load checkpoint", e)
            return null
        }
    }

    fun clear() {
        KVUtils.putString(KEY_CHECKPOINT, "")
        XLog.i(TAG, "Checkpoint cleared")
    }

    fun hasPending(): Boolean {
        return KVUtils.getString(KEY_CHECKPOINT).isNotEmpty()
    }

    // ==================== 消息序列化 ====================

    private fun serializeMessage(msg: ChatMessage): JsonObject {
        return when (msg) {
            is SystemMessage -> JsonObject().apply {
                addProperty("type", "system")
                addProperty("text", msg.text())
            }
            is UserMessage -> JsonObject().apply {
                addProperty("type", "user")
                addProperty("text", msg.singleText())
            }
            is AiMessage -> JsonObject().apply {
                addProperty("type", "ai")
                addProperty("text", msg.text() ?: "")
                if (msg.hasToolExecutionRequests()) {
                    val reqs = JsonArray()
                    msg.toolExecutionRequests().forEach { req ->
                        reqs.add(JsonObject().apply {
                            addProperty("id", req.id() ?: "")
                            addProperty("name", req.name() ?: "")
                            addProperty("arguments", req.arguments() ?: "{}")
                        })
                    }
                    add("toolRequests", reqs)
                }
            }
            is ToolExecutionResultMessage -> JsonObject().apply {
                addProperty("type", "tool_result")
                addProperty("id", msg.id() ?: "")
                addProperty("toolName", msg.toolName() ?: "")
                addProperty("text", msg.text() ?: "")
            }
            else -> JsonObject().apply {
                addProperty("type", "unknown")
                addProperty("text", msg.toString())
            }
        }
    }

    private fun deserializeMessages(arr: JsonArray): List<ChatMessage> {
        val result = mutableListOf<ChatMessage>()
        arr.forEach { element ->
            val obj = element.asJsonObject
            when (obj.get("type")?.asString) {
                "system" -> result.add(SystemMessage.from(obj.get("text")?.asString ?: ""))
                "user" -> result.add(UserMessage.from(obj.get("text")?.asString ?: ""))
                "ai" -> {
                    val text = obj.get("text")?.asString ?: ""
                    val reqsArr = obj.getAsJsonArray("toolRequests")
                    if (reqsArr != null && reqsArr.size() > 0) {
                        val reqs = reqsArr.map { req ->
                            val reqObj = req.asJsonObject
                            val builder = ToolExecutionRequest.builder()
                                .name(reqObj.get("name")?.asString ?: "")
                                .arguments(reqObj.get("arguments")?.asString ?: "{}")
                            val id = reqObj.get("id")?.asString
                            if (!id.isNullOrEmpty()) builder.id(id)
                            builder.build()
                        }
                        result.add(if (text.isNotEmpty()) {
                            AiMessage.from(text, reqs)
                        } else {
                            AiMessage.from(reqs)
                        })
                    } else {
                        result.add(AiMessage.from(text))
                    }
                }
                "tool_result" -> {
                    val id = obj.get("id")?.asString ?: ""
                    val toolName = obj.get("toolName")?.asString ?: ""
                    val text = obj.get("text")?.asString ?: ""
                    // ToolExecutionResultMessage 需要 toolRequestId 和 toolName
                    result.add(ToolExecutionResultMessage.from(id, toolName, text))
                }
            }
        }
        return result
    }

    /**
     * 修复"悬空 tool_call":崩溃可能发生在 LLM 产出带 toolExecutionRequests 的 AiMessage 之后、
     * 对应 ToolExecutionResultMessage 持久化之前。恢复时若把这种历史直接发给 LLM,多数 provider
     * (OpenAI / Anthropic 风格)会拒绝"assistant 的 tool_call 后没有 tool result"的请求,
     * 导致第一次 chatWithRetry 抛不可重试错误、恢复的任务一上来就死。
     *
     * 这里为每个没有匹配结果的 tool 请求,紧跟其 AiMessage 之后补一个占位 ToolExecutionResultMessage,
     * 使消息序列对 provider 合法,循环可从占位结果之后正常继续。结果按 toolRequestId 唯一匹配,
     * 已有结果的请求不会被重复补。
     */
    internal fun repairDanglingToolCalls(messages: List<ChatMessage>): List<ChatMessage> {
        val existingResultIds = messages.asSequence()
            .filterIsInstance<ToolExecutionResultMessage>()
            .mapNotNull { it.id() }
            .filter { it.isNotEmpty() }
            .toHashSet()

        var repaired = 0
        val out = ArrayList<ChatMessage>(messages.size)
        for (msg in messages) {
            out.add(msg)
            if (msg is AiMessage && msg.hasToolExecutionRequests()) {
                for (req in msg.toolExecutionRequests()) {
                    val id = req.id()
                    val matched = !id.isNullOrEmpty() && id in existingResultIds
                    if (!matched) {
                        out.add(
                            ToolExecutionResultMessage.from(
                                id ?: "",
                                req.name() ?: "",
                                "[interrupted: tool result was not persisted before the app was killed]"
                            )
                        )
                        repaired++
                    }
                }
            }
        }
        if (repaired > 0) {
            XLog.w(TAG, "Repaired $repaired dangling tool-call(s) in resumed checkpoint")
        }
        return out
    }
}
