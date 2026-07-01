package com.apk.claw.android.octopus_mobile

import android.util.Log
import com.apk.claw.android.octopus_mobile.safety.ToolRiskPolicy
import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolRegistry
import org.json.JSONObject

/**
 * 方案 F · SKILL.md 导出器.
 *
 * 把 ToolRegistry 里所有 BaseTool 转换为 SKILL.md 格式，
 * 上传给 octopus-agent Runtime，让母体知道此设备能做什么.
 *
 * 调用时机：
 *  - 应用启动时（onCreate） → 上传一次
 *  - 工具集变化时（如切换设备类型 TV/MOBILE） → 重新上传
 *  - 母体主动请求时（capability/refresh） → 重新上传
 *
 * SKILL.md 格式（与 SkillManifest.parseFrontmatter 兼容）：
 * ```
 * ---
 * name: android.tap
 * description: 点击屏幕坐标 (x, y)。先调 get_screen_info 获取 bounds 中心点。
 * risk: low
 * timeout_ms: 15000
 * parameters: {"type":"object","properties":{...},"required":[...]}
 * ---
 * ```
 *
 * 设计原则：
 *  - 与 SkillManifest 严格双向兼容（能解析自己生成的）
 *  - 风险等级（risk）按工具名启发式标注
 *  - 超时（timeout_ms）按工具类型启发式设置
 *  - 工具名加 `android.` 前缀，对齐 LLM 命名空间
 */
object SkillExporter {

    private const val TAG = "SkillExporter"
    private const val PREFIX = "android."

    /** 各工具默认超时（ms） */
    private val DEFAULT_TIMEOUT_MS = mapOf(
        "wait" to 60_000,
        "take_screenshot" to 10_000,
        "send_sms" to 30_000,
        "send_file" to 60_000,
        "open_app" to 15_000,
        "navigate" to 30_000,
        "media_player" to 60_000,
        "file_ops" to 30_000,
        "browse_files" to 20_000,
        "search_files" to 20_000,
        "app_backup" to 120_000,
        "run_code" to 30_000,
    )

    /**
     * 从 ToolRegistry 导出所有工具为 SKILL.md 字符串列表.
     *
     * @return List<Pair<name, markdownContent>>，name 用作文件名
     */
    fun exportAllFromRegistry(): List<Pair<String, String>> {
        val tools = ToolRegistry.getInstance().getAllTools()
        return tools.map { exportOne(it) }
    }

    /**
     * 导出单个工具为 SKILL.md.
     */
    fun exportOne(tool: BaseTool): Pair<String, String> {
        val name = PREFIX + tool.getName()
        val description = (tool.getDescriptionCN().takeIf { it.isNotBlank() }
            ?: tool.getDescriptionEN()).replace("\n", " ").trim()
        val risk = inferRisk(tool.getName())
        val timeoutMs = inferTimeoutMs(tool.getName())
        val parametersJson = buildParametersSchemaJson(tool)

        val sb = StringBuilder()
        sb.append("---\n")
        sb.append("name: ").append(name).append("\n")
        sb.append("description: ").append(description.take(200)).append("\n")
        sb.append("risk: ").append(risk).append("\n")
        sb.append("timeout_ms: ").append(timeoutMs).append("\n")
        sb.append("parameters: ").append(parametersJson).append("\n")
        sb.append("---\n")
        return name to sb.toString()
    }

    /**
     * 批量上传 SKILL.md 到 Runtime（通过 device/skills_update envelope）.
     *
     * @param client WebSocket 客户端
     * @param skills 待上传的 SKILL.md 列表
     */
    fun uploadToRuntime(client: OctopusMobileClient, skills: List<Pair<String, String>>) {
        val arr = org.json.JSONArray()
        for ((name, md) in skills) {
            val obj = JSONObject()
            obj.put("name", name)
            obj.put("content", md)
            arr.put(obj)
        }
        val envelope = Envelope.Request(
            method = "device/skills_update",
            params = mapOf(
                "skills" to arr.toString(),
                "device_type" to ToolRegistry.deviceType.name.lowercase(),
            ),
            id = "skills-${System.currentTimeMillis()}",
        )
        try {
            client.send(envelope)
            Log.i(TAG, "uploaded ${skills.size} SKILL.md to runtime")
        } catch (e: Exception) {
            Log.w(TAG, "upload SKILL.md failed: ${e.message}")
        }
    }

    // ==================== 辅助 ====================

    private fun inferRisk(toolName: String): String = ToolRiskPolicy.riskOf(toolName)

    private fun inferTimeoutMs(toolName: String): Int =
        DEFAULT_TIMEOUT_MS[toolName] ?: 15_000

    /**
     * 把 BaseTool 的 ToolParameter 列表转 OpenAI tools 格式的 JSON Schema 字符串.
     */
    private fun buildParametersSchemaJson(tool: BaseTool): String {
        val params = tool.getParametersWithWaitAfter()
        val schema = JSONObject()
        schema.put("type", "object")
        val properties = JSONObject()
        val required = mutableListOf<String>()

        for (p in params) {
            properties.put(p.name, parameterToJsonSchema(p))
            if (p.isRequired) required.add(p.name)
        }
        schema.put("properties", properties)
        if (required.isNotEmpty()) {
            // 必须包成 JSONArray：org.json 序列化 List 时会退化成字符串
            schema.put("required", org.json.JSONArray(required))
        }
        return schema.toString()
    }

    private fun parameterToJsonSchema(p: ToolParameter): JSONObject {
        val obj = JSONObject()
        obj.put("type", mapTypeToJsonSchema(p.type))
        obj.put("description", p.description)
        return obj
    }

    private fun mapTypeToJsonSchema(type: String): String = when (type.lowercase()) {
        "int", "integer" -> "integer"
        "float", "number", "double" -> "number"
        "bool", "boolean" -> "boolean"
        "array", "list" -> "array"
        "object" -> "object"
        else -> "string"
    }
}
