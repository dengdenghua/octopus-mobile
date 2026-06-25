package com.apk.claw.android.octopus_mobile

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.floating.LiveControlOverlay
import com.apk.claw.android.service.ClawAccessibilityService
import com.apk.claw.android.utils.XLog
import com.google.gson.Gson
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID

/**
 * 示范教学录制 —— 用户「演示一遍」造技能（对标 Codex record-and-replay 的录制头：
 * easier to show than describe）。母体/手机原有的学习与缓存都是 agent 跑成功后**自动**录的；
 * 这里补上**用户主动示范**这一头。
 *
 * 流程：点 rec → [start] 开全局录制 + 悬浮「结束」条 → 用户在任意 App 手动操作一遍 →
 * 无障碍服务把每次点按/长按/输入经 [ingest] 录成带锚点的 [ActionCache.Step] → 点结束 →
 * [stop] 落成 [RoutineStore] 例程 + [ActionCache] 快路径序列，之后即可被
 * [com.apk.claw.android.ui.compose.screen.FastReplay] 确定性重放（对不上自动回退 Agent）。
 *
 * 采集机制：监听 `TYPE_VIEW_CLICKED / TYPE_VIEW_LONG_CLICKED / TYPE_VIEW_TEXT_CHANGED`
 * 无障碍事件，从 `event.source` 取点中节点的文字/id/中心点做锚点。这是无障碍服务能被动观察
 * 用户触摸的唯一干净途径（真手指点标准/Compose 可点元素都会发这些事件）。
 * 注：`onMotionEvent`/`setMotionEventSources`(API34) 只面向非触屏输入源(鼠标/手柄/触控笔)，
 * 不投递手指触屏点按(实测 `sendMotionEventsEnabled` 不会对 SOURCE_TOUCHSCREEN 置位)，故未用。
 *
 * 隐私：只录「锚点文字/id + 坐标」，**不存截图**；只在录制态采集；不录本 App 自己的界面。
 * 仅支持本机目标（重放要本地无障碍树取锚点）。
 */
object DemoRecorder {
    private const val TAG = "DemoRecorder"
    private const val MAX_STEPS = 40
    private const val OWN_PKG = "com.octopus.mobile"

    private val main = Handler(Looper.getMainLooper())
    private val gson = Gson()
    private val steps = mutableListOf<ActionCache.Step>()

    @Volatile
    private var recording = false

    fun isRecording(): Boolean = recording

    /** rec 按钮与悬浮「结束」共用入口：未录→开始，正在录→结束保存。 */
    fun toggle() {
        if (recording) stop() else start()
    }

    fun start() {
        if (recording) return
        if (!ClawAccessibilityService.isRunning()) {
            toast("请先开启无障碍服务，再录制示范")
            return
        }
        synchronized(steps) { steps.clear() }
        recording = true
        XLog.i(TAG, "demo recording started")
        LiveControlOverlay.show("● 录制示范中 · 去要教的 App 走一遍，完成点结束") { stop() }
        toast("开始录制：去你要教的 App 操作一遍，完成点悬浮条「结束」")
    }

    /** 无障碍事件入口：在 [ClawAccessibilityService.onAccessibilityEvent] 里调。 */
    fun ingest(event: AccessibilityEvent) {
        if (!recording) return
        val pkg = event.packageName?.toString().orEmpty()
        if (pkg.isEmpty() || pkg == OWN_PKG) return            // 不录自家界面/空包事件
        synchronized(steps) {
            if (steps.size >= MAX_STEPS) return
            when (event.eventType) {
                AccessibilityEvent.TYPE_VIEW_CLICKED -> capture(event, "tap")
                AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> capture(event, "long_press")
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> captureText(event)
                else -> {}
            }
        }
    }

    /** 点击/长按 → 取点中节点的文字/id/中心点做锚点（重放时据此自适应定位）。 */
    private fun capture(event: AccessibilityEvent, tool: String) {
        val node = event.source ?: return
        val rect = Rect().also { node.getBoundsInScreen(it) }
        if (rect.isEmpty) return
        val text = (node.text?.toString() ?: node.contentDescription?.toString() ?: "").trim()
        val id = node.viewIdResourceName?.toString().orEmpty()
        if (text.isBlank() && id.isBlank()) return             // 无锚点的重放不了，跳过
        val cx = rect.centerX()
        val cy = rect.centerY()
        steps.add(ActionCache.Step(tool, """{"x":$cx,"y":$cy}""", text, id, cx, cy))
        onStepAdded(text.ifBlank { id })
    }

    /** 文本输入 → 记最终全文；同一字段连续输入合并成一步。 */
    private fun captureText(event: AccessibilityEvent) {
        val node = event.source ?: return
        val newText = event.text.joinToString("").trim()
        if (newText.isBlank()) return
        val id = node.viewIdResourceName?.toString().orEmpty()
        val args = gson.toJson(mapOf("text" to newText))
        val last = steps.lastOrNull()
        if (last != null && last.tool == "input_text" && id.isNotBlank() && last.anchorId == id) {
            steps[steps.size - 1] = last.copy(argsJson = args)
        } else {
            val rect = Rect().also { node.getBoundsInScreen(it) }
            steps.add(ActionCache.Step("input_text", args, "", id, rect.centerX(), rect.centerY()))
        }
        onStepAdded("输入「$newText」")
    }

    private fun onStepAdded(label: String) {
        val n = steps.size
        runCatching { LiveControlOverlay.updateStep("● 已录 $n 步 · 最近：$label") }
    }

    /** 结束 → 落成例程 + 快路径缓存。 */
    fun stop() {
        if (!recording) return
        recording = false
        val captured = synchronized(steps) { steps.toList().also { steps.clear() } }
        runCatching { LiveControlOverlay.hide() }
        if (captured.isEmpty()) {
            toast("没录到可重放的动作（试着点带文字的按钮）")
            return
        }
        val name = "示范 " + SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            .format(System.currentTimeMillis())
        val id = "demo-" + UUID.randomUUID().toString().take(12)
        val now = System.currentTimeMillis()
        runCatching {
            RoutineStore.add(
                RoutineStore.Routine(
                    id = id, name = name, prompt = name,
                    targetId = "local", targetLabel = "本机", createdAt = now,
                )
            )
            ActionCache.put(
                ActionCache.Sequence(
                    routineId = id, promptHash = name.hashCode(),
                    steps = captured, createdAt = now,
                )
            )
        }
        XLog.i(TAG, "demo saved $id: ${captured.size} steps")
        toast("已录 ${captured.size} 步，存为例程「$name」，可在例程里改名 / 重放")
    }

    fun cancel() {
        recording = false
        synchronized(steps) { steps.clear() }
        runCatching { LiveControlOverlay.hide() }
    }

    private fun toast(msg: String) {
        main.post {
            runCatching { Toast.makeText(ClawApplication.instance, msg, Toast.LENGTH_LONG).show() }
        }
    }
}
