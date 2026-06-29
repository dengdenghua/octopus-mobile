package com.apk.claw.android.octopus_mobile

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.apk.claw.android.service.ClawAccessibilityService
import com.apk.claw.android.utils.XLog
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 录制一次例程 Agent 运行中的**有效 UI 动作**，全程成功后落成 [ActionCache] 快路径。
 *
 * 接入点（[com.apk.claw.android.ui.compose.screen.ChatAgentBridge] 的 AgentCallback）：
 *  - [onToolCall]   工具执行**前**：点击类在此刻抓「点中的那个节点」做锚点（点击后屏幕就变了，
 *                   只能在点之前抓）。
 *  - [onToolResult] 工具执行**后**：成功才把这步收进序列；失败则整段作废（不覆盖旧的好缓存）。
 *  - [commit]       全程无失败且有动作 → 存入 [ActionCache]。
 *
 * 只录会改变界面的动作；截图 / 看树 / look_at_screen / find_node_info 等感知类不录
 * （重放不做推理，不需要它们）。
 */
class ActionRecorder {
    private val gson = Gson()
    private val steps = mutableListOf<ActionCache.Step>()
    private var pending: ActionCache.Step? = null
    private var failed = false

    fun onToolCall(tool: String, argsJson: String) {
        pending = when (tool) {
            "tap", "long_press" -> captureTapStep(tool, argsJson)
            "swipe", "input_text", "open_app", "system_key", "scroll_to_find" ->
                ActionCache.Step(tool = tool, argsJson = argsJson)
            else -> null   // 感知 / 元工具不录
        }
    }

    fun onToolResult(@Suppress("UNUSED_PARAMETER") tool: String, success: Boolean) {
        val p = pending ?: return
        pending = null
        if (success) steps.add(p) else failed = true
    }

    fun commit(routineId: String, prompt: String) {
        if (failed || steps.isEmpty()) return
        runCatching {
            ActionCache.put(
                ActionCache.Sequence(
                    routineId = routineId,
                    promptHash = prompt.hashCode(),
                    steps = steps.toList(),
                    createdAt = System.currentTimeMillis(),
                )
            )
            XLog.i(TAG, "recorded fast-path for $routineId: ${steps.size} steps")
        }
    }

    /** 点击前解析坐标落在哪个节点 → 取其文字/id 作锚点（重放时据此自适应定位）。 */
    private fun captureTapStep(tool: String, argsJson: String): ActionCache.Step {
        val args = parse(argsJson)
        val x = num(args["x"])
        val y = num(args["y"])
        var text = ""
        var id = ""
        runCatching {
            val root = ClawAccessibilityService.getInstance()?.rootInActiveWindow
            if (root != null) {
                try {
                    val label = findLabelAt(root, x, y)
                    text = label.first
                    id = label.second
                } finally {
                    root.recycle()
                }
            }
        }
        return ActionCache.Step(tool = tool, argsJson = argsJson, anchorText = text, anchorId = id, ox = x, oy = y)
    }

    /** 在树里找「包含点 (x,y)、且自身带文字/描述/id」的**最小**节点，返回 (text, id)。 */
    private fun findLabelAt(root: AccessibilityNodeInfo, x: Int, y: Int): Pair<String, String> {
        var bestText = ""
        var bestId = ""
        var bestArea = Int.MAX_VALUE
        val rect = Rect()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null) return
            n.getBoundsInScreen(rect)
            if (rect.contains(x, y)) {
                val t = (n.text?.toString() ?: n.contentDescription?.toString() ?: "").trim()
                val vid = n.viewIdResourceName?.toString() ?: ""
                val hasLabel = t.isNotEmpty() || vid.isNotEmpty()
                val area = rect.width() * rect.height()
                if (hasLabel && area in 1 until bestArea) {
                    bestText = t
                    bestId = vid
                    bestArea = area
                }
            }
            for (i in 0 until n.childCount) {
                val child = n.getChild(i)
                walk(child)
                child?.recycle()
            }
        }
        walk(root)
        return bestText to bestId
    }

    private fun parse(json: String): Map<String, Any> = try {
        gson.fromJson(json, object : TypeToken<Map<String, Any>>() {}.type) ?: emptyMap()
    } catch (e: Exception) {
        emptyMap()
    }

    private fun num(v: Any?): Int = when (v) {
        is Number -> v.toInt()
        is String -> v.toDoubleOrNull()?.toInt() ?: 0
        else -> 0
    }

    companion object {
        private const val TAG = "ActionRecorder"
    }
}
