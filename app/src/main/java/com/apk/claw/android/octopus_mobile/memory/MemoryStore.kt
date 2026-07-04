package com.apk.claw.android.octopus_mobile.memory

import com.apk.claw.android.utils.KVUtils
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 跨会话记忆存储 —— 将用户偏好、历史交互摘要持久化到 MMKV.
 *
 * 记忆会自动注入到下次 Agent 任务的 System Prompt 中，
 * 让 Agent "记住" 用户的习惯和偏好.
 *
 * 三类记忆：
 *  - PREFERENCE: 用户偏好（"我用饿了么不用美团"、"我坐地铁不打车"）
 *  - CONTEXT: 任务上下文（"刚才帮我订的那家店是海底捞"）
 *  - FACT: 用户事实（"我叫小明"、"我的公司在中关村"）
 *
 * 只依赖 [KVUtils](MMKV 未初始化时退回内存 map),无 Android 依赖,可纯 JVM 单测。
 */
class MemoryStore {

    data class Memory(
        val id: String,
        val content: String,
        val type: MemoryType,
        val source: String,       // 来源：user_explicit / task_inferred / manual
        val createdAt: Long,
        val lastReferencedAt: Long,
        val referenceCount: Int = 0,
        val confidence: Double = 1.0,  // 置信度 0.0-1.0
    )

    enum class MemoryType {
        PREFERENCE,  // 用户偏好
        CONTEXT,     // 任务上下文
        FACT         // 用户事实
    }

    companion object {
        private const val KEY_MEMORIES = "agent_memories"
        private const val MAX_MEMORIES = 50
        private val GSON = Gson()
    }

    /** 添加一条记忆 */
    fun addMemory(memory: Memory) {
        val memories = getMemories().toMutableList()
        // 去重：相似内容不重复添加
        if (memories.any { it.content == memory.content }) return
        memories.add(memory)
        // 超过上限时移除最旧且最少引用的
        if (memories.size > MAX_MEMORIES) {
            val removed = memories
                .filter { it.type != MemoryType.PREFERENCE }  // 优先保留偏好
                .minByOrNull { it.referenceCount * 100 + (System.currentTimeMillis() - it.lastReferencedAt) / 86400000 }
            if (removed != null) memories.remove(removed) else memories.removeAt(0)
        }
        saveMemories(memories)
    }

    /** 获取所有记忆 */
    fun getMemories(): List<Memory> {
        val json = KVUtils.getString(KEY_MEMORIES, "")
        if (json.isEmpty()) return emptyList()
        return try {
            val type = object : TypeToken<List<Memory>>() {}.type
            GSON.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 按类型获取记忆 */
    fun getMemoriesByType(type: MemoryType): List<Memory> {
        return getMemories().filter { it.type == type }
    }

    /** 删除一条记忆 */
    fun removeMemory(id: String) {
        val memories = getMemories().toMutableList()
        if (memories.removeAll { it.id == id }) saveMemories(memories)
    }

    /** 清空全部记忆 */
    fun clearAll() {
        saveMemories(emptyList())
    }

    /** 生成注入到 System Prompt 的记忆文本 */
    fun buildPromptSection(): String {
        val memories = getMemories().sortedByDescending { it.confidence }
        if (memories.isEmpty()) return ""

        val prefs = memories.filter { it.type == MemoryType.PREFERENCE }
        val facts = memories.filter { it.type == MemoryType.FACT }
        val contexts = memories.filter { it.type == MemoryType.CONTEXT }
            .filter { System.currentTimeMillis() - it.lastReferencedAt < 24 * 3600 * 1000 }  // 只保留24h内的上下文

        val sb = StringBuilder()
        if (prefs.isNotEmpty()) {
            sb.append("\n### 用户偏好\n")
            prefs.forEach { sb.append("- ${it.content}\n") }
        }
        if (facts.isNotEmpty()) {
            sb.append("\n### 用户信息\n")
            facts.forEach { sb.append("- ${it.content}\n") }
        }
        if (contexts.isNotEmpty()) {
            sb.append("\n### 近期上下文\n")
            contexts.forEach { sb.append("- ${it.content}\n") }
        }

        return if (sb.isNotEmpty()) "\n\n## 关于用户（跨会话记忆）$sb" else ""
    }

    /** 记录记忆被引用 */
    fun recordReference(memoryId: String) {
        val memories = getMemories().toMutableList()
        val idx = memories.indexOfFirst { it.id == memoryId }
        if (idx >= 0) {
            memories[idx] = memories[idx].copy(
                referenceCount = memories[idx].referenceCount + 1,
                lastReferencedAt = System.currentTimeMillis()
            )
            saveMemories(memories)
        }
    }

    /**
     * 从**用户指令**中提取偏好/事实。
     *
     * 只扫用户说的话,不扫 Agent 的回答 —— 回答里的"我是/我用"是 Agent 的第一人称
     * (如"我是你的手机助手"),混进来会被当成用户事实存下(踩过的真 bug)。
     */
    fun extractFromTask(task: String) {
        // 简单的规则匹配提取偏好（不依赖 LLM，零成本）
        val preferencePatterns = mapOf(
            "(?:我用|我喜欢|我习惯用|我一般用)(.+?)(?:不?用|代替|而不是)".toRegex() to MemoryType.PREFERENCE,
            "(?:我不?用|我不?喜欢|别用)(.+)".toRegex() to MemoryType.PREFERENCE,
            "(?:我叫|我的名字是|我是)(.+)".toRegex() to MemoryType.FACT,
            "(?:我的公司|我在.*上班|我的地址|我住)(.+)".toRegex() to MemoryType.FACT,
        )
        for ((pattern, type) in preferencePatterns) {
            val match = pattern.find(task) ?: continue
            val content = match.value.trim()
            if (content.length < 2 || content.length > 100) continue
            val id = "mem_${System.currentTimeMillis()}_${content.hashCode()}"
            val now = System.currentTimeMillis()
            addMemory(
                Memory(
                    id = id,
                    content = content,
                    type = type,
                    source = "task_inferred",
                    createdAt = now,
                    lastReferencedAt = now,
                    confidence = 0.7  // 规则提取置信度中等
                )
            )
        }
    }

    /** 清理过期的上下文记忆 */
    fun pruneExpiredContexts(maxAgeMs: Long = 24 * 3600 * 1000) {
        val memories = getMemories().toMutableList()
        val now = System.currentTimeMillis()
        val pruned = memories.filter {
            it.type != MemoryType.CONTEXT || (now - it.lastReferencedAt) < maxAgeMs
        }
        if (pruned.size != memories.size) {
            saveMemories(pruned)
        }
    }

    private fun saveMemories(memories: List<Memory>) {
        KVUtils.putString(KEY_MEMORIES, GSON.toJson(memories))
    }
}
