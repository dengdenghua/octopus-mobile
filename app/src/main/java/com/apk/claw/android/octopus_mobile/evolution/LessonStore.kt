package com.apk.claw.android.octopus_mobile.evolution

import android.content.Context
import com.apk.claw.android.utils.KVUtils
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 教训持久化存储 —— 将 EvolutionEngine 反思产生的教训持久化到 MMKV.
 *
 * 教训会自动注入到下次 Agent 任务的 System Prompt 中，
 * 形成 "反思 → 学习 → 改进" 的闭环.
 */
class LessonStore(private val context: Context) {

    data class Lesson(
        val id: String,           // 唯一 ID
        val content: String,      // 教训内容（一行祈使句）
        val tag: String?,         // 分类标签（如 "navigation", "input", "browser"）
        val source: String,       // 来源：reflect / evolve / manual
        val createdAt: Long,      // 创建时间戳
        val hitCount: Int = 0,    // 被注入 System Prompt 的次数
        val lastHitAt: Long? = null,
        val effectiveness: Double = 0.5,  // 有效性评分 0.0-1.0
    )

    companion object {
        private const val KEY_LESSONS = "evolution_lessons"
        private const val MAX_LESSONS = 20  // 最多保留 20 条教训
        private val GSON = Gson()
    }

    /** 添加一条教训 */
    fun addLesson(lesson: Lesson) {
        val lessons = getLessons().toMutableList()
        // 去重：相同 content 不重复添加
        if (lessons.any { it.content == lesson.content }) return
        lessons.add(lesson)
        // 超过上限时移除最旧且效果最差的
        if (lessons.size > MAX_LESSONS) {
            val removed = lessons.sortedBy { it.effectiveness * 100 - it.hitCount }.first()
            lessons.remove(removed)
        }
        saveLessons(lessons)
    }

    /** 获取所有活跃教训 */
    fun getLessons(): List<Lesson> {
        val json = KVUtils.getString(KEY_LESSONS, "")
        if (json.isEmpty()) return emptyList()
        return try {
            val type = object : TypeToken<List<Lesson>>() {}.type
            GSON.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 生成注入到 System Prompt 的教训文本 */
    fun buildPromptSection(): String {
        val lessons = getLessons().filter { it.effectiveness >= 0.3 }
        if (lessons.isEmpty()) return ""
        val lines = lessons.mapIndexed { i, l -> "${i + 1}. ${l.content}" }
        return "\n\n## 过往教训（从失败中学习）\n" + lines.joinToString("\n")
    }

    /** 记录教训被使用 */
    fun recordHit(lessonId: String) {
        val lessons = getLessons().toMutableList()
        val idx = lessons.indexOfFirst { it.id == lessonId }
        if (idx >= 0) {
            lessons[idx] = lessons[idx].copy(
                hitCount = lessons[idx].hitCount + 1,
                lastHitAt = System.currentTimeMillis()
            )
            saveLessons(lessons)
        }
    }

    /** 更新教训有效性 */
    fun updateEffectiveness(lessonId: String, delta: Double) {
        val lessons = getLessons().toMutableList()
        val idx = lessons.indexOfFirst { it.id == lessonId }
        if (idx >= 0) {
            val newEff = (lessons[idx].effectiveness + delta).coerceIn(0.0, 1.0)
            lessons[idx] = lessons[idx].copy(effectiveness = newEff)
            saveLessons(lessons)
        }
    }

    /** 移除效果差的教训 */
    fun pruneIneffective(threshold: Double = 0.2) {
        val lessons = getLessons().filter { it.effectiveness >= threshold }
        saveLessons(lessons)
    }

    private fun saveLessons(lessons: List<Lesson>) {
        KVUtils.putString(KEY_LESSONS, GSON.toJson(lessons))
    }
}
