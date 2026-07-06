@file:Suppress(
    "PackageNaming", "CyclomaticComplexMethod", "TooGenericExceptionCaught",
    "ReturnCount", "MagicNumber", "MaxLineLength", "UnusedParameter",
)   // 并行作者原文件的存量样式(下划线包/内联阈值/多分支缓解规则),整文件豁免

package com.apk.claw.android.octopus_mobile

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 经验账本（Experience Ledger）——从 octopus-os evolution protocol 移植。
 *
 * 核心思想：记录代码生成中反复出现的错误模式，下次生成时自动注入"缓解策略"到 prompt，
 * 让 LLM 不犯同样的错。类似疫苗——得过一次病，下次就有抗体。
 *
 * 三层记忆：
 * - **Errors（错误模式）**：JS console error / 静态检查失败的模式，带出现次数和最后出现时间
 * - **Mitigations（缓解策略）**：针对错误模式的 prompt 片段，注入到 codePrompt
 * - **Success Patterns（成功模式）**：高分任务的特征（暂不启用，为后续扩展预留）
 *
 * 存储：简单 JSON 文件（app files/experience_ledger.json），不需要 Room 数据库。
 * 上限：错误模式最多保留 MAX_ENTRIES 条，按 (count * recency) 排序淘汰。
 */
object ExperienceLedger {

    private const val TAG = "ExperienceLedger"
    private const val MAX_ENTRIES = 30
    private const val FILE_NAME = "experience_ledger.json"
    private const val ERROR_HALF_LIFE_DAYS = 30.0

    private data class ErrorEntry(
        val pattern: String,        // 错误模式关键词（如 "Cannot read property"）
        val count: Int,             // 出现次数
        val firstSeen: Long,        // 首次发现时间戳
        val lastSeen: Long,         // 最近发现时间戳
        val lastContext: String,    // 最近一次的上下文（错误片段摘要）
        val mitigation: String,     // 对应的缓解策略（注入 prompt 的文本）
    )

    private val entries = mutableListOf<ErrorEntry>()
    private var loaded = false

    fun init(filesDir: File) {
        if (loaded) return
        synchronized(entries) {
            if (loaded) return
            val file = File(filesDir, FILE_NAME)
            if (file.exists()) {
                runCatching {
                    val root = JSONObject(file.readText())
                    val arr = root.optJSONArray("errors") ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        entries.add(ErrorEntry(
                            pattern = obj.getString("pattern"),
                            count = obj.getInt("count"),
                            firstSeen = obj.getLong("firstSeen"),
                            lastSeen = obj.getLong("lastSeen"),
                            lastContext = obj.optString("lastContext", ""),
                            mitigation = obj.optString("mitigation", ""),
                        ))
                    }
                    Log.i(TAG, "Loaded ${entries.size} error patterns from ledger")
                }.onFailure {
                    Log.w(TAG, "Failed to load ledger: ${it.message}")
                }
            }
            loaded = true
        }
    }

    /**
     * 记录一次错误，更新经验账本。
     * 如果是新模式，自动推导出缓解策略。
     */
    fun recordError(errorMessage: String, context: String = "") {
        if (!loaded) return
        val pattern = extractPattern(errorMessage)
        if (pattern.isBlank()) return

        synchronized(entries) {
            val now = System.currentTimeMillis()
            val existing = entries.find { it.pattern == pattern }
            if (existing != null) {
                val idx = entries.indexOf(existing)
                entries[idx] = existing.copy(
                    count = existing.count + 1,
                    lastSeen = now,
                    lastContext = context.take(200).ifBlank { existing.lastContext },
                )
            } else {
                entries.add(ErrorEntry(
                    pattern = pattern,
                    count = 1,
                    firstSeen = now,
                    lastSeen = now,
                    lastContext = context.take(200),
                    mitigation = deriveMitigation(pattern, errorMessage),
                ))
                if (entries.size > MAX_ENTRIES) {
                    evict()
                }
            }
            EvolutionMetrics.ledgerError()
            save()
        }
    }

    /**
     * 记录成功修复的模式——减少该模式的权重（表示修复策略有效）。
     */
    fun recordRepair(errorMessage: String) {
        if (!loaded) return
        val pattern = extractPattern(errorMessage)
        synchronized(entries) {
            val existing = entries.find { it.pattern == pattern } ?: return
            val idx = entries.indexOf(existing)
            entries[idx] = existing.copy(count = (existing.count - 1).coerceAtLeast(1))
            EvolutionMetrics.ledgerRepair()
            save()
        }
    }

    /**
     * 生成注入到 codePrompt 的"经验教训"段落。
     * 返回空字符串表示没有需要注入的教训。
     */
    fun getMitigationsSection(): String {
        if (!loaded) return ""
        synchronized(entries) {
            val now = System.currentTimeMillis()
            val active = entries
                .map { it to scoreEntry(it, now) }
                .filter { it.second > 0.3 }
                .sortedByDescending { it.second }
                .take(8)
                .map { it.first }

            if (active.isEmpty()) return ""

            val sb = StringBuilder()
            sb.appendLine("## 经验教训（来自之前错误，请务必避免）")
            sb.appendLine("以下是之前常见的错误模式，请严格遵循对应的规避方法：")
            active.forEachIndexed { i, e ->
                sb.appendLine("${i + 1}. **${e.pattern}**")
                sb.appendLine("   - 规避：${e.mitigation}")
            }
            EvolutionMetrics.mitigationInjected()
            return sb.toString()
        }
    }

    private fun scoreEntry(e: ErrorEntry, now: Long): Double {
        val ageDays = (now - e.lastSeen) / (1000.0 * 60 * 60 * 24)
        val freshness = Math.pow(0.5, ageDays / ERROR_HALF_LIFE_DAYS)
        val frequency = (e.count.toDouble() / (e.count + 3.0)).coerceIn(0.0, 1.0)
        return freshness * 0.6 + frequency * 0.4
    }

    private fun extractPattern(msg: String): String {
        val normalized = msg.trim().take(200)
        val patterns = listOf(
            Regex("(TypeError:.*?)\\s*(?:\\n|$)") to { m: MatchResult -> m.groupValues[1].take(80) },
            Regex("(ReferenceError:.*?)\\s*(?:\\n|$)") to { m: MatchResult -> m.groupValues[1].take(80) },
            Regex("(SyntaxError:.*?)\\s*(?:\\n|$)") to { m: MatchResult -> m.groupValues[1].take(80) },
            Regex("(Cannot read propert[^.]+)") to { m: MatchResult -> m.groupValues[1].take(80) },
            Regex("(is not a function)") to { _: MatchResult -> "xxx is not a function" },
            Regex("(is not defined)") to { _: MatchResult -> "xxx is not defined" },
            Regex("(null pointer|NullPointerException)") to { _: MatchResult -> "Null pointer access" },
            Regex("(Maximum call stack)") to { _: MatchResult -> "Maximum call stack exceeded (infinite recursion)" },
            Regex("(ContentSecurityPolicy|CSP)") to { _: MatchResult -> "Content Security Policy violation" },
        )
        for ((regex, extractor) in patterns) {
            val match = regex.find(normalized)
            if (match != null) return extractor(match)
        }
        return normalized.lineSequence().firstOrNull()?.take(60)?.ifBlank { "" } ?: ""
    }

    private fun deriveMitigation(pattern: String, fullError: String): String {
        return when {
            "Cannot read propert" in pattern || "Cannot read properties" in pattern ->
                "访问对象属性前必须判空：用 obj?.prop 或 if(obj) obj.prop；渲染列表前检查数组是否为 null/undefined，默认 []。"
            "is not defined" in pattern ->
                "所有变量和函数必须在使用前声明（let/const/function），不要引用不存在的 DOM id（用 document.getElementById 后判空）。"
            "is not a function" in pattern ->
                "调用方法前确认对象上有该方法；事件回调确保是函数引用而非函数调用结果；API 回调检查 octopus.xxx 是否存在。"
            "SyntaxError" in pattern || "Unexpected token" in pattern ->
                "模板字符串中反引号、大括号必须配对；JSON.parse 前确保数据是合法 JSON；script 标签内不要出现 </script 字面量。"
            "TypeError" in pattern ->
                "所有 DOM 查询、storage 读取、API 返回值都做类型检查和兜底；数字计算用 Number() 转换后判断 isNaN。"
            "NullPointerException" in pattern || "null pointer" in pattern ->
                "Java/JS 互操作时注意可能返回 null 的 API，必须做 null 检查。"
            "Maximum call stack" in pattern || "infinite" in pattern ->
                "递归函数必须有终止条件；事件监听不要在回调中重复绑定自身；watch/observe 不要在回调中触发自己监听的数据变化。"
            "Content Security Policy" in pattern || "CSP" in pattern ->
                "不要用 eval()、new Function()、innerHTML 插入 <script> 标签；事件用 addEventListener 绑定而非 inline onclick。"
            "Not allowed to load" in pattern || "blocked" in pattern ->
                "外部资源使用 https 协议；不要加载混合内容（http in https page）；iframe 需注意 X-Frame-Options。"
            else ->
                "仔细检查代码逻辑，对边界条件（空数据、网络失败、首次加载无数据）做防御处理，加 try-catch 包裹易出错的操作。"
        }
    }

    private fun evict() {
        val now = System.currentTimeMillis()
        entries.sortByDescending { scoreEntry(it, now) }
        while (entries.size > MAX_ENTRIES) {
            entries.removeAt(entries.lastIndex)
        }
    }

    private fun save() {
        try {
            val ctx = com.apk.claw.android.ClawApplication.instance
            val file = File(ctx.filesDir, FILE_NAME)
            val root = JSONObject()
            val arr = JSONArray()
            entries.forEach { e ->
                arr.put(JSONObject().apply {
                    put("pattern", e.pattern)
                    put("count", e.count)
                    put("firstSeen", e.firstSeen)
                    put("lastSeen", e.lastSeen)
                    put("lastContext", e.lastContext)
                    put("mitigation", e.mitigation)
                })
            }
            root.put("errors", arr)
            root.put("version", 1)
            file.writeText(root.toString(2))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save ledger: ${e.message}")
        }
    }

    @Suppress("unused")
    fun hashKey(vararg parts: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val combined = parts.joinToString("|")
        val bytes = digest.digest(combined.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }.take(16)
    }
}
