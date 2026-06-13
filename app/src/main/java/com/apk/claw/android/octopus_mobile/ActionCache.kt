package com.apk.claw.android.octopus_mobile

import com.apk.claw.android.utils.KVUtils
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 例程「快路径」动作缓存 —— 例程成功跑过一次后，把这次的有效 UI 动作序列存下来，
 * 下次直接确定性重放，省掉 LLM 推理（更快、更省）。
 *
 * 关键设计（健壮性）：点击类动作**不存死坐标**，而是存「当时点中的那个节点的文字/id」(锚点)，
 * 重放时按文字在**当前**屏幕重新定位再点。录制见 [ActionRecorder]，重放见
 * [com.apk.claw.android.ui.compose.screen.FastReplay]。任一步对不上即放弃快路径、回退给
 * 完整 Agent —— 所以快路径只会「更快」，不会「乱点」。
 *
 * 存储沿用 [RoutineStore] 同款：MMKV 一个 key 存 Gson 序列化的列表，按 routineId 查。
 */
object ActionCache {
    private const val KEY = "agent_action_cache"
    private val gson = Gson()

    /**
     * 一个动作步骤。
     * @param tool 工具名（tap / long_press / swipe / input_text / open_app / system_key / scroll_to_find）
     * @param argsJson LLM 当时给的原始 JSON 参数
     * @param anchorText 点击类：当时点中节点的可见文字 / 描述（重放据此重新定位）
     * @param anchorId 点击类：当时点中节点的 viewId（文字为空时的兜底定位）
     * @param ox / oy 原始坐标（多个同名节点时用来挑中心最接近的那个）
     */
    data class Step(
        val tool: String,
        val argsJson: String,
        val anchorText: String = "",
        val anchorId: String = "",
        val ox: Int = 0,
        val oy: Int = 0,
    )

    data class Sequence(
        val routineId: String,
        val promptHash: Int,   // prompt 变了缓存即作废，避免拿旧动作跑新指令
        val steps: List<Step>,
        val createdAt: Long,
        val hitCount: Int = 0,
    )

    fun all(): List<Sequence> {
        val json = KVUtils.getString(KEY, "")
        if (json.isEmpty()) return emptyList()
        return try {
            gson.fromJson(json, object : TypeToken<List<Sequence>>() {}.type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 取某例程的有效缓存；prompt 不匹配（指令已改）则视作无缓存。 */
    fun get(routineId: String, prompt: String): Sequence? {
        val s = all().find { it.routineId == routineId } ?: return null
        return if (s.promptHash == prompt.hashCode()) s else null
    }

    fun has(routineId: String, prompt: String): Boolean = get(routineId, prompt) != null

    fun put(seq: Sequence) {
        save(all().filterNot { it.routineId == seq.routineId } + seq)
    }

    fun remove(routineId: String) {
        save(all().filterNot { it.routineId == routineId })
    }

    fun bumpHit(routineId: String) {
        save(all().map { if (it.routineId == routineId) it.copy(hitCount = it.hitCount + 1) else it })
    }

    private fun save(list: List<Sequence>) {
        KVUtils.putString(KEY, gson.toJson(list))
    }
}
