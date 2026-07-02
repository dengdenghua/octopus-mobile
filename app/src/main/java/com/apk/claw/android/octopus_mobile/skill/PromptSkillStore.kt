package com.apk.claw.android.octopus_mobile.skill

import com.apk.claw.android.utils.KVUtils
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 提示词技能库 —— 把「Claude skill」那套移植到端侧:一个技能 = 一段 markdown 指令包,
 * 相关时注入 Agent 的 System Prompt,Agent 再用**它自己的工具**去执行。
 *
 * 与 Claude Code 的区别在于「loader 在哪」:Claude 把加载器内建在 harness 里,这里由本 store +
 * [com.apk.claw.android.AppViewModel] 的 `dynamicPromptSuffix` 注入充当加载器。技能本体是纯
 * markdown(不编译/不进沙箱),所以简单的 Claude skill 内容改改工具名就能直接放进来用。
 *
 * v1:注入所有 **enabled** 的技能(封顶 [MAX_SECTION_CHARS]),由用户在技能页开关控制哪些生效;
 * 按 prompt 相关性只注命中项是 v1.1 的事。
 */
object PromptSkillStore {

    data class PromptSkill(
        val id: String,
        val name: String,
        /** 触发描述:一句话说明「什么时候该用这个技能」(给人看,也帮以后做相关性匹配)。 */
        val description: String,
        /** 指令正文(markdown):告诉 Agent 具体怎么做,可引用已注册工具名。 */
        val body: String,
        val enabled: Boolean = true,
        val createdAt: Long = 0L,
        val source: String = "generated",  // generated / imported / manual
    )

    private const val KEY = "prompt_skills"
    private const val MAX_SKILLS = 40
    /** 注入 System Prompt 的技能段总字数上限,防止把上下文撑爆。 */
    private const val MAX_SECTION_CHARS = 6000
    private val GSON = Gson()

    fun all(): List<PromptSkill> {
        val json = KVUtils.getString(KEY, "")
        if (json.isEmpty()) return emptyList()
        return runCatching {
            GSON.fromJson<List<PromptSkill>>(json, object : TypeToken<List<PromptSkill>>() {}.type) ?: emptyList()
        }.getOrDefault(emptyList())
    }

    private fun save(list: List<PromptSkill>) {
        KVUtils.putString(KEY, GSON.toJson(list.takeLast(MAX_SKILLS)))
    }

    /** 添加/覆盖(同名则替换,视为「更新技能」)。返回最终 id。 */
    fun add(skill: PromptSkill): String {
        val list = all().toMutableList()
        val idx = list.indexOfFirst { it.name == skill.name }
        if (idx >= 0) list[idx] = skill.copy(id = list[idx].id) else list.add(skill)
        save(list)
        return if (idx >= 0) list[idx].id else skill.id
    }

    fun delete(id: String) = save(all().filterNot { it.id == id })

    fun setEnabled(id: String, enabled: Boolean) {
        save(all().map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    /** 注入 System Prompt 的技能段:拼所有 enabled 技能的正文,封顶字数。 */
    fun buildPromptSection(): String {
        val on = all().filter { it.enabled }
        if (on.isEmpty()) return ""
        val sb = StringBuilder("\n\n## 已启用技能（相关时遵循其步骤，用你已有的工具执行）\n")
        for (s in on) {
            val block = "\n### ${s.name}\n适用：${s.description}\n${s.body}\n"
            if (sb.length + block.length > MAX_SECTION_CHARS + 200) break
            sb.append(block)
        }
        return sb.toString().take(MAX_SECTION_CHARS + 400)
    }
}
