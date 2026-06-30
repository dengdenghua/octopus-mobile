package com.apk.claw.android.octopus_mobile.safety

import android.util.Log
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * 工具调用护栏 —— 从母体 runtime/safety/immunity/tool_guardrails.py 移植.
 *
 * 三层防护：
 *  1. **重复失败检测**：同一个工具 + 同样参数连续失败 → warn/block
 *  2. **无进展检测**：幂等工具反复调用同样参数 → warn/block
 *  3. **危险工具分类**：工具分 idempotent / mutating / dangerous 三级
 *
 * 用法：
 * ```kotlin
 * val guardrail = ToolCallGuardrailController()
 * val decision = guardrail.observe("tap", mapOf("x" to 100, "y" to 200), failed = false)
 * if (decision.shouldHalt) { /* 阻止执行 */ }
 * ```
 */
class ToolCallGuardrailController(
    val config: GuardrailConfig = GuardrailConfig()
) {
    companion object {
        private const val TAG = "ToolGuardrail"

        /** 幂等工具（只读，无副作用） */
        val IDEMPOTENT_TOOLS: Set<String> = setOf(
            "get_screen_info", "find_node_info", "get_installed_apps",
            "take_screenshot", "find_text", "wait",
            "browser_get_dom", "browser_screenshot",
            "read_file", "get_clipboard", "get_current_app",
        )

        /** 变异工具（有副作用，但可接受） */
        val MUTATING_TOOLS: Set<String> = setOf(
            "tap", "long_press", "swipe", "input_text", "scroll_to_find",
            "open_app", "system_key", "clipboard", "send_file",
            "browser_navigate", "browser_click", "browser_type",
            "install_app", "set_clipboard", "write_file",
            "preview_html",  // 离屏渲染任意 HTML，副作用限于 WebView 内
        )

        /** 危险工具（需要额外审批） */
        val DANGEROUS_TOOLS: Set<String> = setOf(
            "browser_install_extension",  // 装 CRX 有风险
            "browser_evaluate",           // 在任意已登录页面执行任意 JS（会话/Cookie 窃取）
            "run_code",                   // 在设备上执行 Agent 生成代码（纯计算沙箱，但仍属最危一档）
        )

        fun classifyTool(name: String): ToolKind {
            return when {
                name in IDEMPOTENT_TOOLS -> ToolKind.IDEMPOTENT
                name in MUTATING_TOOLS -> ToolKind.MUTATING
                name in DANGEROUS_TOOLS -> ToolKind.DANGEROUS
                else -> ToolKind.UNKNOWN
            }
        }
    }

    // ── 状态 ──────────────────────────────────────────

    private val exactFailureCounts = ConcurrentHashMap<ToolCallSignature, Int>()
    private val sameToolFailureCounts = ConcurrentHashMap<String, Int>()
    private val noProgress = ConcurrentHashMap<ToolCallSignature, Pair<String, Int>>()
    private val totalCalls = java.util.concurrent.atomic.AtomicInteger(0)

    /** 当护栏发出 BLOCK/HALT 决策时触发，用于 EventBus 解耦通知 */
    @Volatile
    var onBlock: ((toolName: String, reason: String) -> Unit)? = null

    fun reset() {
        exactFailureCounts.clear()
        sameToolFailureCounts.clear()
        noProgress.clear()
        totalCalls.set(0)
    }

    // ── 执行前只读预检 ────────────────────────────────

    /**
     * 执行前预检：若该调用的历史失败已达硬停阈值，则返回 BLOCK/HALT。
     *
     * 与 [observe] 的区别：**只读，不修改任何计数**。供 ToolRegistry 在执行前调用，
     * 避免「预检 + 结果观察」对同一次调用重复计数（尤其是幂等工具的 no-progress 计数）。
     */
    fun precheck(toolName: String, args: Map<String, Any>? = null): GuardrailDecision {
        val sig = ToolCallSignature.fromCall(toolName, args)

        val priorExactCount = exactFailureCounts[sig] ?: 0
        if (config.hardStopEnabled && priorExactCount >= config.exactFailureBlockAfter) {
            Log.w(TAG, "PRE-BLOCK: $toolName exact same call failed ${priorExactCount}x previously")
            onBlock?.invoke(toolName, "exact_failure_block: ${priorExactCount}x")
            return GuardrailDecision(
                action = GuardrailAction.BLOCK,
                code = "exact_failure_block",
                message = "完全相同的调用失败了 ${priorExactCount} 次，已阻止以防止死循环",
                toolName = toolName, count = priorExactCount, signature = sig,
            )
        }
        val priorSameCount = sameToolFailureCounts[toolName] ?: 0
        if (config.hardStopEnabled && priorSameCount >= config.sameToolFailureHaltAfter) {
            Log.w(TAG, "PRE-HALT: $toolName failed ${priorSameCount}x total previously")
            onBlock?.invoke(toolName, "same_tool_halt: ${priorSameCount}x")
            return GuardrailDecision(
                action = GuardrailAction.HALT,
                code = "same_tool_halt",
                message = "工具 '$toolName' 已失败 ${priorSameCount} 次，暂停执行",
                toolName = toolName, count = priorSameCount, signature = sig,
            )
        }
        return GuardrailDecision(action = GuardrailAction.ALLOW, toolName = toolName)
    }

    // ── 核心：观察一次工具调用 ────────────────────────

    fun observe(
        toolName: String,
        args: Map<String, Any>? = null,
        result: String? = null,
        failed: Boolean = false,
    ): GuardrailDecision {
        totalCalls.incrementAndGet()
        val sig = ToolCallSignature.fromCall(toolName, args)

        if (failed) {
            return handleFailure(sig, toolName, result)
        }

        // 成功调用 → 清除该签名/工具的历史失败计数（一次成功即打断失败连击）
        exactFailureCounts.remove(sig)
        sameToolFailureCounts.remove(toolName)

        val kind = classifyTool(toolName)
        if (kind == ToolKind.IDEMPOTENT) {
            return handleNoProgress(sig, toolName, result)
        }

        return GuardrailDecision(action = GuardrailAction.ALLOW, toolName = toolName)
    }

    // ── 失败处理 ──────────────────────────────────────

    private fun handleFailure(
        sig: ToolCallSignature, toolName: String, result: String?
    ): GuardrailDecision {
        val exactCount = (exactFailureCounts[sig] ?: 0) + 1
        exactFailureCounts[sig] = exactCount

        val sameCount = (sameToolFailureCounts[toolName] ?: 0) + 1
        sameToolFailureCounts[toolName] = sameCount

        // 硬停：完全相同的调用失败太多次
        if (config.hardStopEnabled && exactCount >= config.exactFailureBlockAfter) {
            Log.w(TAG, "BLOCK: $toolName exact same call failed ${exactCount}x")
            onBlock?.invoke(toolName, "exact_failure_block: ${exactCount}x")
            return GuardrailDecision(
                action = GuardrailAction.BLOCK,
                code = "exact_failure_block",
                message = "完全相同的调用失败了 ${exactCount} 次，已阻止以防止死循环",
                toolName = toolName, count = exactCount, signature = sig,
            )
        }

        // 硬停：同一个工具失败太多次
        if (config.hardStopEnabled && sameCount >= config.sameToolFailureHaltAfter) {
            Log.w(TAG, "HALT: $toolName failed ${sameCount}x total")
            onBlock?.invoke(toolName, "same_tool_halt: ${sameCount}x")
            return GuardrailDecision(
                action = GuardrailAction.HALT,
                code = "same_tool_halt",
                message = "工具 '$toolName' 已失败 ${sameCount} 次，暂停执行",
                toolName = toolName, count = sameCount, signature = sig,
            )
        }

        // 警告：完全相同调用重复失败
        if (config.warningsEnabled && exactCount >= config.exactFailureWarnAfter) {
            return GuardrailDecision(
                action = GuardrailAction.WARN,
                code = "exact_failure_warn",
                message = "相同调用已失败 ${exactCount} 次，可能陷入死循环",
                toolName = toolName, count = exactCount, signature = sig,
            )
        }

        // 警告：同一工具重复失败
        if (config.warningsEnabled && sameCount >= config.sameToolFailureWarnAfter) {
            return GuardrailDecision(
                action = GuardrailAction.WARN,
                code = "same_tool_warn",
                message = "工具 '$toolName' 已失败 ${sameCount} 次，请尝试不同方法",
                toolName = toolName, count = sameCount, signature = sig,
            )
        }

        return GuardrailDecision(action = GuardrailAction.ALLOW, toolName = toolName, count = exactCount)
    }

    // ── 无进展处理 ────────────────────────────────────

    private fun handleNoProgress(
        sig: ToolCallSignature, toolName: String, result: String?
    ): GuardrailDecision {
        val (prevResult, prevCount) = noProgress[sig] ?: ("" to 0)
        val newCount = prevCount + 1
        val resultStr = (result ?: "").take(200)
        noProgress[sig] = resultStr to newCount

        if (newCount < config.noProgressWarnAfter) {
            return GuardrailDecision(action = GuardrailAction.ALLOW, toolName = toolName, count = newCount)
        }

        if (config.hardStopEnabled && newCount >= config.noProgressBlockAfter) {
            Log.w(TAG, "BLOCK: $toolName no progress after ${newCount}x same args")
            onBlock?.invoke(toolName, "no_progress_block: ${newCount}x")
            return GuardrailDecision(
                action = GuardrailAction.BLOCK,
                code = "no_progress_block",
                message = "幂等工具 '$toolName' 用相同参数调用了 ${newCount} 次，无进展",
                toolName = toolName, count = newCount, signature = sig,
            )
        }

        if (config.warningsEnabled) {
            return GuardrailDecision(
                action = GuardrailAction.WARN,
                code = "no_progress_warn",
                message = "工具 '$toolName' 重复调用 ${newCount} 次无进展，请换方法",
                toolName = toolName, count = newCount, signature = sig,
            )
        }

        return GuardrailDecision(action = GuardrailAction.ALLOW, toolName = toolName, count = newCount)
    }

    fun totalCalls(): Int = totalCalls.get()
}

// ── 数据类 ────────────────────────────────────────────

enum class ToolKind { IDEMPOTENT, MUTATING, DANGEROUS, UNKNOWN }

enum class GuardrailAction { ALLOW, WARN, BLOCK, HALT }

data class GuardrailConfig(
    val warningsEnabled: Boolean = true,
    val hardStopEnabled: Boolean = true,  // 手机版默认开（比母体更保守）
    val exactFailureWarnAfter: Int = 2,
    val exactFailureBlockAfter: Int = 5,
    val sameToolFailureWarnAfter: Int = 3,
    val sameToolFailureHaltAfter: Int = 8,
    val noProgressWarnAfter: Int = 2,
    val noProgressBlockAfter: Int = 5,
)

data class GuardrailDecision(
    val action: GuardrailAction,
    val code: String = "allow",
    val message: String = "",
    val toolName: String = "",
    val count: Int = 0,
    val signature: ToolCallSignature? = null,
) {
    val allowsExecution: Boolean get() = action in listOf(GuardrailAction.ALLOW, GuardrailAction.WARN)
    val shouldHalt: Boolean get() = action in listOf(GuardrailAction.BLOCK, GuardrailAction.HALT)
}

data class ToolCallSignature(
    val toolName: String,
    val argsHash: String,
) {
    companion object {
        fun fromCall(toolName: String, args: Map<String, Any>?): ToolCallSignature {
            val canonical = canonicalArgs(args ?: emptyMap())
            return ToolCallSignature(toolName = toolName, argsHash = sha256(canonical))
        }

        private fun canonicalArgs(args: Map<String, Any>): String {
            val sorted = args.toSortedMap()
            return sorted.entries.joinToString(",") { "${it.key}:${it.value}" }
        }

        private fun sha256(text: String): String {
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(text.toByteArray(Charsets.UTF_8))
            return digest.take(8).joinToString("") { "%02x".format(it) }
        }
    }
}
