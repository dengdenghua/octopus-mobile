package com.apk.claw.android.octopus_mobile

import android.content.Context
import org.json.JSONObject
import java.io.IOException

/**
 * SKILL.md 解析器.
 *
 * 把 30 个 SKILL.md 文件解析为 [SkillSpec] 列表.
 * 单一事实源：本地 LLM 和远程母体都吃这份.
 *
 * 支持两种 SKILL.md 格式（自动识别）:
 *
 * 1. **精简版（方案 F 默认）** —— 5 字段、JSON Schema 单行:
 *    ```
 *    ---
 *    name: android.tap
 *    description: 点击屏幕坐标 (x, y)。先调 get_screen_info 获取 bounds 中心点。
 *    risk: low
 *    timeout_ms: 15000
 *    parameters: {"type": "object", "properties": {"x": {"type": "integer"}, "y": {"type": "integer"}}, "required": ["x", "y"]}
 *    ---
 *    ```
 *
 * 2. **长版（兼容旧 Phase 0 写法）** —— 多行 description + list-of-objects parameters:
 *    ```
 *    ---
 *    name: android.tap
 *    description: |
 *      Tap at coordinate (x, y). Use this to click buttons.
 *    parameters:
 *      - name: x
 *        type: integer
 *        required: true
 *    ---
 *    ```
 *
 * 实现策略：
 *  - **零外部依赖**（不引入 SnakeYAML）
 *  - **行级 + JSON 解析器**（用 org.json 解析 parameters 单行 JSON）
 *  - **description 支持 | 块 / plain continuation / 单行**
 */
object SkillManifest {

    /**
     * 从 assets 目录加载所有 SKILL.md.
     *
     * @param context  Android Context
     * @param assetDir 资产目录（默认 "skills"）
     */
    fun loadFromAssets(
        context: Context,
        assetDir: String = "skills"
    ): List<SkillSpec> {
        val manager = context.assets
        val specs = mutableListOf<SkillSpec>()
        try {
            val files = manager.list(assetDir) ?: return emptyList()
            for (file in files) {
                if (file.endsWith(".md")) {
                    val content = manager.open("$assetDir/$file").bufferedReader().use { it.readText() }
                    parseFrontmatter(content)?.let { specs.add(it) }
                }
            }
        } catch (e: IOException) {
            // 资产目录不存在 → 返回空
        }
        return specs
    }

    /**
     * 解析单个 SKILL.md 的 frontmatter.
     * 返回 null 表示解析失败.
     */
    fun parseFrontmatter(content: String): SkillSpec? {
        if (!content.startsWith("---")) return null
        val end = content.indexOf("\n---", startIndex = 3)
        if (end < 0) return null
        val frontmatter = content.substring(3, end).trim()
        val lines = frontmatter.lines()

        var name = ""
        var description = ""
        var risk: String? = null
        var timeoutMs: Int? = null
        var parametersJson: String? = null   // 方案 F 单行 JSON Schema
        val params = mutableListOf<Map<String, Any?>>()   // 长版 list-of-objects

        var inParams = false
        var currentParam: MutableMap<String, Any?>? = null
        var inDescription = false
        val descLines = mutableListOf<String>()
        // 累积"已读 description 第一行"位置（用于检测 plain continuation）
        var descFirstLineConsumed = false

        for (line in lines) {
            // 1) parameters 单行 JSON Schema 检测
            if (line.trimStart().startsWith("parameters:")) {
                inParams = false
                inDescription = false
                val value = line.substringAfter(":").trim()
                if (value.startsWith("{")) {
                    // 方案 F 精简版 —— 单行 JSON Schema
                    parametersJson = value
                    // 继续下一行（可能 attributes 还没结束）
                    continue
                } else if (value.isEmpty()) {
                    // 长版 —— 进入 list 模式
                    inParams = true
                    currentParam = null
                    continue
                }
            }

            // 2) risk 字段
            if (line.trimStart().startsWith("risk:") && !inParams) {
                inDescription = false
                risk = line.substringAfter(":").trim()
                continue
            }

            // 3) timeout_ms 字段
            if (line.trimStart().startsWith("timeout_ms:") && !inParams) {
                inDescription = false
                timeoutMs = line.substringAfter(":").trim().toIntOrNull()
                continue
            }

            // 4) name 字段
            if (line.startsWith("name:")) {
                inParams = false
                inDescription = false
                name = line.substringAfter(":").trim()
                continue
            }

            // 5) description 字段
            if (line.startsWith("description:")) {
                inParams = false
                inDescription = true
                val first = line.substringAfter(":").trim()
                descFirstLineConsumed = true
                if (first.isNotEmpty() && first != "|") {
                    descLines.add(first)
                }
                // | 块 / 空 description 都不预填，留到下面
                continue
            }

            // 6) 在 description 区，处理多行 / continuation
            if (inDescription && descFirstLineConsumed) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) {
                    continue
                }
                // 任何新的顶层 key 都结束 description
                if (line.startsWith("name:") ||
                    line.startsWith("parameters:") ||
                    line.startsWith("risk:") ||
                    line.startsWith("timeout_ms:") ||
                    line.startsWith("---")
                ) {
                    inDescription = false
                    continue
                }
                // plain continuation：缩进的非空行
                if (line.firstOrNull()?.isWhitespace() == true) {
                    // 控制总长度（≤ 200 字符）
                    if (descLines.joinToString(" ").length < 200) {
                        descLines.add(trimmed)
                    }
                    continue
                } else {
                    // 顶层非空行 = 结束 description
                    inDescription = false
                }
            }

            // 7) 在 params 区，处理 list-of-objects
            if (inParams) {
                if (line.trimStart().startsWith("- name:")) {
                    currentParam = mutableMapOf("name" to line.substringAfter("name:").trim())
                    params.add(currentParam!!)
                } else if (currentParam != null) {
                    val t = line.trim()
                    if (t.startsWith("type:")) {
                        currentParam!!["type"] = t.substringAfter(":").trim()
                    } else if (t.startsWith("required:")) {
                        currentParam!!["required"] = t.substringAfter(":").trim() == "true"
                    } else if (t.startsWith("default:")) {
                        currentParam!!["default"] = t.substringAfter(":").trim()
                    } else if (t.startsWith("description:")) {
                        currentParam!!["description"] = t.substringAfter(":").trim()
                    }
                }
            }
        }

        description = descLines.joinToString(" ").take(200)
        if (name.isEmpty()) return null

        // 解析 parameters —— 优先 JSON Schema 单行，否则从 list-of-objects 构建
        val schema: JSONObject = parametersJson?.let { raw ->
            try {
                JSONObject(raw)
            } catch (e: Exception) {
                // 解析失败 → 当作空 schema
                JSONObject().put("type", "object").put("properties", JSONObject())
            }
        } ?: buildJsonSchemaFromList(name, description, params)

        return SkillSpec(
            id = name,
            description = description,
            parametersSchema = schema
        )
    }

    /** 从 list-of-objects 构造 OpenAI tools 格式的 parameters JSON Schema. */
    private fun buildJsonSchemaFromList(
        name: String,
        description: String,
        params: List<Map<String, Any?>>
    ): JSONObject {
        val schema = JSONObject()
        schema.put("type", "object")
        val properties = JSONObject()
        val required = mutableListOf<String>()

        for (p in params) {
            val pname = p["name"] as? String ?: continue
            val ptype = (p["type"] as? String ?: "string").let {
                when (it) {
                    "int", "integer" -> "integer"
                    "float", "number" -> "number"
                    "bool", "boolean" -> "boolean"
                    "array", "list" -> "array"
                    "object" -> "object"
                    else -> "string"
                }
            }
            val pdesc = p["description"] as? String ?: ""
            val prop = JSONObject()
            prop.put("type", ptype)
            prop.put("description", pdesc)
            if (p["default"] != null) {
                prop.put("default", p["default"])
            }
            properties.put(pname, prop)
            if (p["required"] == true) required.add(pname)
        }

        schema.put("properties", properties)
        if (required.isNotEmpty()) {
            schema.put("required", required.toList())
        }
        return schema
    }

    /**
     * 同步给母体（远程决策用）.
     * Phase 1 接入：把所有 SkillSpec 转 JSON 上传.
     */
    fun toJsonForRemote(skills: List<SkillSpec>): String {
        val arr = org.json.JSONArray()
        for (s in skills) {
            val obj = JSONObject()
            obj.put("id", s.id)
            obj.put("description", s.description)
            obj.put("parameters_schema", s.parametersSchema)
            arr.put(obj)
        }
        return arr.toString()
    }
}
