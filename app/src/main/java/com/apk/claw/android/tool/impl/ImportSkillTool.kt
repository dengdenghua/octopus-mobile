package com.apk.claw.android.tool.impl

import com.apk.claw.android.octopus_mobile.skill.PromptSkillStore
import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult

/**
 * 导入一份现成的技能(如 Claude 的 SKILL.md)—— 直接把「指令包」放进 [PromptSkillStore]。
 *
 * 技能本体是纯 markdown(不编译/不进沙箱),所以 Claude 风格的 skill **内容能直接吃**:解析出
 * frontmatter 的 name/description,正文原样保留,并加一句「适配提示」让 Agent 执行时把里面写的
 * Read/Bash/点击 等换成本项目的等价工具(tap_by_vision/input_text/run_code/file_ops…)。
 * 无 LLM 调用、无积分消耗——纯本地解析 + 落库。
 */
class ImportSkillTool : BaseTool() {

    override fun getName() = "import_skill"
    override fun getDisplayName() = if (useChineseDescription) "导入技能" else "Import Skill"

    override fun getParameters() = listOf(
        ToolParameter(
            "content", "string",
            "The skill content to import — a Claude SKILL.md (with --- frontmatter ---) or plain " +
                "markdown instructions. Frontmatter name/description are extracted if present.",
            true,
        ),
        ToolParameter("name", "string", "Optional skill name override.", false),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val content = requireString(params, "content")
        if (content.isBlank()) return ToolResult.error("技能内容为空")
        val (fmName, fmDesc, body) = parseSkillMd(content)
        if (body.isBlank()) return ToolResult.error("解析后正文为空")

        val name = optionalString(params, "name", "").ifBlank { fmName ?: firstHeading(body) ?: "导入技能" }
        val desc = (fmDesc ?: firstLine(body) ?: name).take(200)

        val id = PromptSkillStore.add(
            PromptSkillStore.PromptSkill(
                id = "skill_" + System.currentTimeMillis(),
                name = name.take(40),
                description = desc,
                body = ADAPTER_PREAMBLE + body,
                enabled = true,
                createdAt = System.currentTimeMillis(),
                source = "imported",
            ),
        )
        return ToolResult.success(
            "已导入技能「$name」并启用(id=$id)。适用:$desc。" +
                "执行时我会把其中的工具/命令名换成本设备的等价工具;可在「技能」页开关或删除。",
        )
    }

    /** 解析 SKILL.md:有 `---` frontmatter 则抽 name/description,正文取其后;否则整段当正文。 */
    private fun parseSkillMd(raw: String): Triple<String?, String?, String> {
        val t = raw.trim()
        if (t.startsWith("---")) {
            val end = t.indexOf("\n---", 3)
            if (end > 0) {
                val fm = t.substring(3, end)
                val body = t.substring(end + 4).trimStart('\n', '-', ' ', '\t').trim()
                val name = Regex("(?m)^\\s*name:\\s*(.+)$").find(fm)?.groupValues?.get(1)?.trim()?.trim('"', '\'')
                val desc = Regex("(?m)^\\s*description:\\s*(.+)$").find(fm)?.groupValues?.get(1)?.trim()?.trim('"', '\'')
                return Triple(name?.takeIf { it.isNotBlank() }, desc?.takeIf { it.isNotBlank() }, body)
            }
        }
        return Triple(null, null, t)
    }

    private fun firstHeading(body: String): String? =
        Regex("(?m)^#+\\s*(.+)$").find(body)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }

    private fun firstLine(body: String): String? =
        body.lineSequence().map { it.trim().trimStart('#', ' ') }.firstOrNull { it.isNotBlank() }

    override fun getDescriptionEN() = """
        Import a ready-made skill (e.g. a Claude SKILL.md, or plain markdown instructions) into the
        local skill library. Frontmatter name/description are parsed; the body is kept and an
        adapter note is prepended so tool references get mapped to this device's tools.
        Use when the user pastes a skill / SKILL.md and asks to import or add it.
    """.trimIndent()

    override fun getDescriptionCN() = """
        把一份现成技能(如 Claude 的 SKILL.md,或纯 markdown 指令)导入本地技能库。
        自动解析 frontmatter 的 name/description,正文原样保留,并加一句适配提示让执行时
        把工具名换成本设备的等价工具。适用:用户粘贴一份技能/SKILL.md 让你导入时。
    """.trimIndent()

    companion object {
        private const val ADAPTER_PREAMBLE =
            "> 注:本技能原为其它 Agent 编写。执行时把其中提到的工具/命令名换成你自己的等价工具" +
                "(如 Read/Write/Bash → run_code/file_ops;点击/输入 → tap_by_vision/input_text)。\n\n"
    }
}
