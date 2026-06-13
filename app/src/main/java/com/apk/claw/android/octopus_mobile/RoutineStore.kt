package com.apk.claw.android.octopus_mobile

import com.apk.claw.android.utils.KVUtils
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 例程库 —— 把一次对话指令存成可复用的「例程」(routine)。
 *
 * 编排路线 A（语义重放）：例程只存「原始自然语言指令 + 目标设备」，
 * 重放时让 Agent 当场重新看屏规划，而不是死录坐标 —— 抗界面改版、跨设备分辨率。
 *
 * 持久化在 MMKV（与 [ActivityLog] 同范式）。
 */
object RoutineStore {

    private const val KEY = "agent_routines"
    private const val MAX_KEEP = 100
    private val gson = Gson()

    data class Routine(
        val id: String,
        val name: String,
        val prompt: String,
        val targetId: String,      // "local" 或 deviceId
        val targetLabel: String,   // "本机" 或 设备名（展示用）
        val createdAt: Long,
        val lastRunAt: Long = 0L,
        val runCount: Int = 0,
        // 定时（可空：null=未定时。用可空 Int 避免 Gson 给老数据填 0 误判为 00:00 已定时）
        val scheduleHour: Int? = null,
        val scheduleMinute: Int? = null,
        val scheduleDaily: Boolean = false,
    ) {
        val isScheduled: Boolean get() = scheduleHour != null && scheduleMinute != null
    }

    fun all(): List<Routine> {
        val json = KVUtils.getString(KEY, "")
        if (json.isEmpty()) return emptyList()
        return try {
            val type = object : TypeToken<List<Routine>>() {}.type
            gson.fromJson<List<Routine>>(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun add(routine: Routine) {
        val list = all().toMutableList()
        list.add(0, routine)  // 最新在前
        save(if (list.size > MAX_KEEP) list.subList(0, MAX_KEEP) else list)
    }

    fun remove(id: String) {
        save(all().filterNot { it.id == id })
    }

    /** 按 id 替换整条（用于更新定时等）。 */
    fun update(routine: Routine) {
        save(all().map { if (it.id == routine.id) routine else it })
    }

    /** 记录一次运行：更新最近运行时间与次数。 */
    fun touch(id: String) {
        val now = System.currentTimeMillis()
        save(all().map { if (it.id == id) it.copy(lastRunAt = now, runCount = it.runCount + 1) else it })
    }

    private fun save(list: List<Routine>) {
        KVUtils.putString(KEY, gson.toJson(list))
    }
}
