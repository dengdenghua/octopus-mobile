package com.apk.claw.android.octopus_mobile.cerebrum

import java.util.UUID

/**
 * 思维模式 —— 从母体 runtime/core/cerebrum/thinking_mode.py 移植.
 *
 * 三种模式：
 *  - **react**（快速）：直接 ReAct 循环，适合简单操作
 *  - **thinking**（慢速）：先建计划再执行，适合复杂任务
 *  - **deep**（深度）：建议深度研究，适合调研/报告
 *
 * 手机版简化：去掉 Deep Research 依赖，保留计划脚手架.
 *
 * 用法：
 * ```kotlin
 * val plan = ThinkingMode.buildPlan("帮我查一下明天北京的天气")
 * // plan.needsSearch = true, plan.suggestDeep = false
 *
 * val plan2 = ThinkingMode.buildPlan("帮我打开微信发消息给张三")
 * // plan2.needsSearch = false, plan2.suggestDeep = false
 * ```
 */
object ThinkingMode {

    // ── 数据类 ────────────────────────────────────────

    data class PlanStep(
        val title: String,
        val detail: String,
        var status: String = "pending",  // pending / in_progress / completed
    )

    data class ThinkingPlan(
        val id: String,
        val mode: String,          // "react" / "thinking" / "deep"
        val goal: String,
        val assumptions: List<String>,
        val steps: List<PlanStep>,
        val risks: List<String>,
        val needsSearch: Boolean,
        val suggestDeep: Boolean,
        val createdAt: String,
    ) {
        fun toPromptGuidance(): String {
            val stepLines = steps.mapIndexed { i, s ->
                "  ${i + 1}. [${s.status}] ${s.title}: ${s.detail}"
            }.joinToString("\n")

            val flagLines = mutableListOf<String>()
            if (needsSearch) flagLines.add("需要搜索/实时信息")
            if (suggestDeep) flagLines.add("建议深度研究")

            return """
<octopus-thinking-mode>
模式: $mode
目标: $goal

步骤:
$stepLines

风险:
${risks.joinToString("\n") { "- $it" }}

标记: ${if (flagLines.isEmpty()) "无" else flagLines.joinToString(", ")}
</octopus-thinking-mode>
""".trimIndent()
        }
    }

    // ── 关键词模式 ────────────────────────────────────

    private val SEARCH_PATTERNS = listOf(
        "latest", "today", "current", "recent", "news", "price", "stock",
        "weather", "law", "policy", "regulation", "release", "search",
        "source", "website", "url", "http://", "https://",
        "最新", "今天", "现在", "新闻", "价格", "股价", "天气", "政策", "法规",
        "搜索", "来源", "网站",
    )

    private val DEEP_RESEARCH_PATTERNS = listOf(
        "market research", "deep research", "industry report",
        "competitive analysis", "competitor", "compare vendors", "white paper",
        "多来源", "深度研究", "市场调研", "调研", "行业报告", "竞品",
        "竞争分析", "对比", "报告",
    )

    // ── 构建计划 ──────────────────────────────────────

    fun buildPlan(goal: String, mode: String = "react"): ThinkingPlan {
        val cleanGoal = goal.trim()
        val needsSearch = looksLikeUrl(cleanGoal) || containsAny(cleanGoal, SEARCH_PATTERNS)
        val suggestDeep = containsAny(cleanGoal, DEEP_RESEARCH_PATTERNS)

        val assumptions = mutableListOf(
            "使用当前 agent 角色和可用记忆作为稳定上下文",
            "辅助角色仅在本轮虚拟存在，不创建独立记忆",
        )

        val risks = mutableListOf(
            "不要暴露隐藏的思维链，只展示简洁的推理检查点",
        )
        if (needsSearch) {
            risks.add("答案可能依赖实时信息，断言前需验证")
        }
        if (suggestDeep) {
            risks.add("请求具有广泛研究/报告形态，深度研究可能产生更好结果")
        }

        val evidenceDetail = if (needsSearch) {
            "检查当前来源或提供的 URL，再做出时效性声明"
        } else {
            "检查当前线程、选定的 agent 记忆和提供的材料"
        }

        val steps = listOf(
            PlanStep("明确任务", "重述目标、约束和预期输出形式", "in_progress"),
            PlanStep("收集上下文", evidenceDetail),
            PlanStep("推理比较", "比较可能的解释和权衡后再决定"),
            PlanStep("验证", "检查过时事实、遗漏假设和矛盾"),
            PlanStep("回答", "先给结果，再给简洁理由和下一步"),
        )

        // 自动选模式
        val effectiveMode = when {
            suggestDeep -> "deep"
            needsSearch || mode == "thinking" -> "thinking"
            else -> mode
        }

        return ThinkingPlan(
            id = "think-${UUID.randomUUID().toString().take(12)}",
            mode = effectiveMode,
            goal = cleanGoal,
            assumptions = assumptions,
            steps = steps,
            risks = risks,
            needsSearch = needsSearch,
            suggestDeep = suggestDeep,
            createdAt = java.text.SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US
            ).format(java.util.Date()),
        )
    }

    /**
     * 更新计划步骤状态.
     */
    fun updateStepStatus(plan: ThinkingPlan, iteration: Int, final: Boolean): ThinkingPlan {
        val updatedSteps = plan.steps.mapIndexed { index, step ->
            when {
                final -> step.copy(status = "completed")
                index < iteration -> step.copy(status = "completed")
                index == iteration -> step.copy(status = "in_progress")
                else -> step
            }
        }
        return plan.copy(steps = updatedSteps)
    }

    // ── 内部 ──────────────────────────────────────────

    private fun containsAny(text: String, patterns: List<String>): Boolean {
        val lowered = text.lowercase()
        return patterns.any { it.lowercase() in lowered }
    }

    private fun looksLikeUrl(text: String): Boolean {
        return Regex("https?://\\S+|www\\.\\S+", RegexOption.IGNORE_CASE).containsMatchIn(text)
    }
}
