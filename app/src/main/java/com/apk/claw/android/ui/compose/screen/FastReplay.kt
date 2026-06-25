package com.apk.claw.android.ui.compose.screen

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.apk.claw.android.R
import com.apk.claw.android.octopus_mobile.ActionCache
import com.apk.claw.android.octopus_mobile.ActivityLog
import com.apk.claw.android.octopus_mobile.ControlTarget
import com.apk.claw.android.service.ClawAccessibilityService
import com.apk.claw.android.tool.ToolRegistry
import com.apk.claw.android.utils.XLog
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 例程「快路径」确定性重放：把 [ActionCache] 里录好的有效动作逐条放出来——
 * 点击类按锚点文字在**当前**屏重新定位（坐标自适应），其余工具原样执行。
 *
 * 安全原则：任一步对不上（节点找不到 / 工具失败）立即返回 [Outcome.FELL_BACK]，
 * 由调用方（[RoutineRunner]）回退给完整 Agent。所以快路径只会更快，不会乱点。
 * 仅支持本机目标（远程没有本地无障碍树取锚点）。
 *
 * 注意：含 [Thread.sleep]（步间等界面加载），**必须在后台线程调用**。
 */
object FastReplay {
    private const val TAG = "FastReplay"
    private const val SETTLE_MS = 700L   // 步间留给界面加载/跳转的时间
    private const val MAX_STEPS = 40
    private val gson = Gson()

    enum class Outcome { SUCCESS, FELL_BACK, NO_CACHE, UNSUPPORTED }

    fun tryReplay(
        routineId: String,
        prompt: String,
        variables: Map<String, String> = emptyMap(),
    ): Outcome {
        if (ControlTarget.isRemote()) return Outcome.UNSUPPORTED
        val svc = ClawAccessibilityService.getInstance() ?: return Outcome.UNSUPPORTED
        if (!ClawAccessibilityService.isRunning()) return Outcome.UNSUPPORTED
        val seq = ActionCache.get(routineId, prompt) ?: return Outcome.NO_CACHE
        if (seq.steps.isEmpty() || seq.steps.size > MAX_STEPS) return Outcome.NO_CACHE

        val reg = ToolRegistry.getInstance()
        for ((i, rawStep) in seq.steps.withIndex()) {
            // 参数化:把缓存步骤里的 {变量} 替换成本次实际值（args + 锚点文字都替）。
            val step = if (variables.isEmpty()) rawStep else rawStep.copy(
                argsJson = com.apk.claw.android.octopus_mobile.RoutineVariables.substitute(rawStep.argsJson, variables),
                anchorText = com.apk.claw.android.octopus_mobile.RoutineVariables.substitute(rawStep.anchorText, variables),
            )
            val ok = runCatching { replayStep(svc, reg, step) }.getOrDefault(false)
            if (!ok) {
                XLog.i(TAG, "step $i (${step.tool}) mismatch → fall back to agent")
                return Outcome.FELL_BACK
            }
            sleep(SETTLE_MS)
        }
        ActionCache.bumpHit(routineId)
        XLog.i(TAG, "fast-path replayed ${seq.steps.size} steps for $routineId")
        runCatching {
            ActivityLog.record(
                ActivityLog.Entry(
                    id = "act_" + System.currentTimeMillis(),
                    ts = System.currentTimeMillis(),
                    task = prompt,
                    target = ControlTarget.label(),
                    steps = seq.steps.size,
                    outcome = "success",
                    detail = svc.getString(R.string.fast_replay_activity_log_message, seq.steps.size),
                )
            )
        }
        return Outcome.SUCCESS
    }

    private fun replayStep(svc: ClawAccessibilityService, reg: ToolRegistry, step: ActionCache.Step): Boolean {
        return when (step.tool) {
            "tap" -> replayTap(svc, step, longPress = false)
            "long_press" -> replayTap(svc, step, longPress = true)
            else -> {
                // 稳的工具（swipe / input_text / open_app / system_key / scroll_to_find）原样执行
                val tool = reg.getTool(step.tool) ?: return false
                tool.executeWithWaitAfter(parse(step.argsJson)).isSuccess
            }
        }
    }

    /** 点击类：按锚点文字/id 在当前屏重新定位 → 点它现在的位置。找不到 → false（触发回退）。 */
    private fun replayTap(svc: ClawAccessibilityService, step: ActionCache.Step, longPress: Boolean): Boolean {
        val nodes = when {
            step.anchorText.isNotBlank() -> svc.findNodesByText(step.anchorText)
            step.anchorId.isNotBlank() -> svc.findNodesById(step.anchorId)
            else -> return false   // 没锚点（当时点的是空白/画布）→ 不敢盲点，回退
        }
        if (nodes.isNullOrEmpty()) return false
        // 多个同名节点：挑中心最接近原始坐标的那个
        val node = nodes.minByOrNull { centerDist(it, step.ox, step.oy) } ?: return false
        return try {
            if (!longPress && svc.clickNode(node)) {
                true
            } else {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                val cx = rect.centerX()
                val cy = rect.centerY()
                if (longPress) {
                    val dur = num(parse(step.argsJson)["duration_ms"], 1000)
                    svc.performLongPress(cx, cy, dur.toLong())
                } else {
                    svc.performTap(cx, cy)
                }
            }
        } finally {
            ClawAccessibilityService.recycleNodes(nodes)
        }
    }

    private fun centerDist(n: AccessibilityNodeInfo, x: Int, y: Int): Long {
        val r = Rect()
        n.getBoundsInScreen(r)
        val dx = (r.centerX() - x).toLong()
        val dy = (r.centerY() - y).toLong()
        return dx * dx + dy * dy
    }

    private fun sleep(ms: Long) {
        runCatching { Thread.sleep(ms) }
    }

    private fun parse(json: String): Map<String, Any> = try {
        gson.fromJson(json, object : TypeToken<Map<String, Any>>() {}.type) ?: emptyMap()
    } catch (e: Exception) {
        emptyMap()
    }

    private fun num(v: Any?, def: Int): Int = when (v) {
        is Number -> v.toInt()
        is String -> v.toDoubleOrNull()?.toInt() ?: def
        else -> def
    }
}
