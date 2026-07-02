package com.apk.claw.android.tool.impl

import com.apk.claw.android.account.EffectiveLlm
import com.apk.claw.android.account.LlmRouting
import com.apk.claw.android.octopus_mobile.skill.PromptSkillStore
import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult
import com.apk.claw.android.utils.OctoHttp
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 「一句话生成技能」—— 把 Claude skill 那套移植到端侧:用户描述想教 Agent 会做什么,一次 LLM
 * 调用产出 `{name, description, body(markdown)}` 存进 [PromptSkillStore]。下次相关任务时,技能
 * 正文注入 System Prompt(见 AppViewModel),Agent 用**自己的工具**照着做。
 *
 * 与 generate_app 的差别:技能是纯 markdown 指令(不编译/不进沙箱),所以低危、即时生效。
 */
class GenerateSkillTool : BaseTool() {

    private val http = OctoHttp.shared.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override fun getName() = "generate_skill"
    override fun getDisplayName() = if (useChineseDescription) "生成技能" else "Generate Skill"

    override fun getParameters() = listOf(
        ToolParameter(
            "description", "string",
            "What the skill should teach the agent to do, and roughly when to use it " +
                "(e.g. 'when the user asks to book a meeting, follow these steps to open the calendar app and fill the form').",
            true,
        ),
        ToolParameter("name", "string", "Optional short skill name.", false),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val desc = requireString(params, "description").take(2000)
        val wantName = optionalString(params, "name", "").trim()
        val eff = LlmRouting.effective()
        if (eff.apiKey.isBlank()) return ToolResult.error("未配置模型，无法生成技能")

        val raw = runCatching { callLlm(eff, skillPrompt(desc, wantName)) }
            .getOrElse { return ToolResult.error("生成技能失败: ${it.message}") }

        val obj = runCatching { JSONObject(extractJson(raw)) }
            .getOrElse { return ToolResult.error("技能格式解析失败") }
        var name = obj.optString("name").trim().ifBlank { wantName.ifBlank { "未命名技能" } }
        var skillDesc = obj.optString("description").trim().ifBlank { desc.take(80) }
        var body = obj.optString("body").trim()
        if (body.isBlank()) return ToolResult.error("技能正文为空")

        // v2 自评自优化(一轮):拿**真实工具清单** + 评判标准让 LLM 复核改一版——把编造的工具名换掉、
        // 步骤写具体、触发描述收紧。只在产出合法(非空正文)时采纳,避免越改越糟。
        val toolNames = runCatching {
            com.apk.claw.android.tool.ToolRegistry.getInstance().getAllTools().map { it.getName() }
        }.getOrDefault(emptyList())
        var refined = false
        if (toolNames.isNotEmpty()) {
            runCatching { callLlm(eff, refinePrompt(name, skillDesc, body, toolNames)) }.getOrNull()
                ?.let { runCatching { JSONObject(extractJson(it)) }.getOrNull() }
                ?.let { r ->
                    val rb = r.optString("body").trim()
                    if (rb.isNotBlank()) {
                        name = r.optString("name").trim().ifBlank { name }
                        skillDesc = r.optString("description").trim().ifBlank { skillDesc }
                        body = rb
                        refined = true
                    }
                }
        }

        val id = PromptSkillStore.add(
            PromptSkillStore.PromptSkill(
                id = "skill_" + System.currentTimeMillis(),
                name = name,
                description = skillDesc,
                body = body,
                enabled = true,
                createdAt = System.currentTimeMillis(),
                source = "generated",
            ),
        )
        val refineNote = if (refined) "（已自评优化一轮，校对工具引用）" else ""
        return ToolResult.success(
            "已生成技能「$name」$refineNote 并启用(id=$id)。适用:$skillDesc。" +
                "下次相关任务会自动把它的步骤注入我的上下文;可在「技能」页开关或删除。",
        )
    }

    /** 自评/优化提示:给出**唯一合法工具清单**,让 LLM 复核并改进技能。 */
    private fun refinePrompt(name: String, desc: String, body: String, toolNames: List<String>) = """
        复核并改进下面这条给「手机 Agent」用的技能。**硬要求**:body 里出现的工具名必须全部来自这个
        清单(其它一律换成清单里的等价工具,或删掉那步);清单之外的工具一律视为不存在:
        ${toolNames.joinToString("、")}

        同时:每步尽量点名要用的工具、写具体可执行;触发描述(description)要具体,别太泛。
        输出**严格 JSON**(不要代码块、不要多余解释):{"name":"...","description":"...","body":"..."}

        当前技能:
        name: $name
        description: $desc
        body:
        $body
    """.trimIndent()

    /** 生成提示:让 LLM 产出 {name, description, body},body 里引用**本项目真实工具名**。 */
    private fun skillPrompt(desc: String, wantName: String) = """
        你在为一个「手机上的 AI Agent」写一条**技能**。技能=一段简明的操作指南,会在相关任务时注入
        Agent 的系统提示,Agent 照着做。用户想要的技能:「$desc」${if (wantName.isNotBlank()) "(建议名:$wantName)" else ""}

        Agent 可用的工具(在 body 里就用这些名字,别编不存在的):
        - 界面感知:get_screen_info、look_at_screen(截图)、find_node_info
        - 界面操作:tap_by_vision(按视觉找元素点)、input_text、system_key(返回/home等)、open_app
        - 浏览器:browser_navigate、browser_click、browser_type、browser_get_dom
        - 计算/生成:run_code(跑 JS)、generate_image(文生图)、generate_app(生成小程序)
        - 其它:clipboard、take_screenshot、list_apps、app_action(调其它小程序)、search_files、file_ops

        输出**严格 JSON**(不要 markdown 代码块、不要多余解释):
        {
          "name": "简短技能名(≤12字)",
          "description": "一句话:什么时候该用这个技能(用于触发判断)",
          "body": "markdown 指令正文:分步骤写清怎么做,每步尽量点名要用的工具;只讲怎么做,别啰嗦。≤600字"
        }
    """.trimIndent()

    private fun callLlm(eff: EffectiveLlm, prompt: String): String {
        val url = eff.baseUrl.trimEnd('/') + "/chat/completions"
        val body = JSONObject().apply {
            put("model", eff.model)
            put("temperature", 0.3)
            put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
        }
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${eff.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { resp ->
            val respBody = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("HTTP ${resp.code}: ${respBody.take(200)}")
            val message = JSONObject(respBody)
                .getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            return message.optString("content", "").takeIf { it.isNotBlank() }
                ?: message.optString("reasoning_content", "")
        }
    }

    /** LLM 偶尔用 ```json 包一层或前后带话,这里剥出 {...} 本体。 */
    private fun extractJson(raw: String): String {
        val fenced = Regex("```(?:json)?\\s*([\\s\\S]*?)```").find(raw)?.groupValues?.get(1)
        val s = (fenced ?: raw).trim()
        val start = s.indexOf('{'); val end = s.lastIndexOf('}')
        return if (start >= 0 && end > start) s.substring(start, end + 1) else s
    }

    override fun getDescriptionEN() = """
        Create a reusable *skill* (a short markdown how-to) from a natural-language description.
        The skill is saved locally and, on relevant future tasks, its steps are injected into the
        agent's context so it follows them using existing device tools. Use when the user says
        "make/teach a skill for X", "remember how to do X", or wants to capture a repeatable workflow.
    """.trimIndent()

    override fun getDescriptionCN() = """
        把一句话描述变成一条可复用的**技能**(一小段 markdown 操作指南),存到本地技能库。
        以后遇到相关任务,技能步骤会自动注入 Agent 上下文,让它用现有设备工具照着做。
        适用:用户说「教你一个 X 技能 / 记住怎么做 X / 把这套流程存成技能」时。
    """.trimIndent()
}
