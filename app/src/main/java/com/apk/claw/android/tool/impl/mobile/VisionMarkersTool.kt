package com.apk.claw.android.tool.impl.mobile

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.apk.claw.android.octopus_mobile.VisionAnalyzer
import com.apk.claw.android.service.ClawAccessibilityService
import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult
import com.apk.claw.android.utils.XLog

/**
 * 视觉精确定位点击（Set-of-Marks）—— 当无障碍树抓不到目标（游戏 / canvas / 图片按钮 /
 * 自绘 UI），或纯文字定位不准时：截屏 → 在可点元素上叠**红色编号框** → 让视觉模型
 * **只选一个编号** → 按该节点中心点击。
 *
 * 比"让 VLM 估坐标"稳得多：坐标由无障碍节点精确给出，VLM 只需做"选数字"这种它最擅长的事。
 * 仅本机；需配置视觉模型（设置 → 模型配置）。
 */
class VisionMarkersTool : BaseTool() {

    override fun getName(): String = "tap_by_vision"

    override fun getDisplayName(): String = "视觉定位点击"

    override fun getDescriptionEN(): String =
        "Tap a target the accessibility tree can't locate. Screenshots the screen, overlays numbered " +
            "boxes on tappable elements, asks the vision model which number matches your target, then taps it. " +
            "Use when tap/find_node fail on games, canvas, image buttons, or custom-drawn UI. " +
            "Param: target — describe what to tap (e.g. 'the red play button')."

    override fun getDescriptionCN(): String =
        "点击无障碍树定位不到的目标。截屏 → 在可点元素上叠编号框 → 视觉模型选编号 → 点它。" +
            "当 tap/find_node 在游戏/canvas/图片按钮/自绘界面上失败时用。参数：target（要点什么，如『红色播放按钮』）。"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter(
            "target",
            "string",
            "What to tap — describe the target element, e.g. 'the search icon', 'red Start button'.",
            true,
        ),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val target = requireString(params, "target")
        if (!VisionAnalyzer.isConfigured()) {
            return ToolResult.error("未配置视觉模型，无法视觉定位。请到 设置 → 模型配置 填写视觉模型。")
        }
        val svc = ClawAccessibilityService.getInstance() ?: return ToolResult.error("无障碍服务未运行")
        val shot = svc.takeScreenshot(5000) ?: return ToolResult.error("截屏失败")

        val root = svc.rootInActiveWindow
        if (root == null) {
            shot.recycle()
            return ToolResult.error("无法获取当前界面节点")
        }
        val marks = try {
            collectMarks(root)
        } finally {
            root.recycle()
        }
        if (marks.isEmpty()) {
            shot.recycle()
            return ToolResult.error("当前屏没有可标记的可点元素")
        }

        val marked = drawMarkers(shot, marks)
        shot.recycle()

        val q = "屏幕上每个可点元素都标了一个红色编号框。我要点击的目标是：「$target」。" +
            "请只回答最匹配这个目标的那个编号数字（例如 7），不要任何解释。若没有匹配的，回答 -1。"
        val answer = runCatching { VisionAnalyzer.analyzeBlocking(marked, q) }.getOrElse {
            marked.recycle()
            return ToolResult.error("视觉分析失败：${it.message}")
        }
        marked.recycle()

        val idx = Regex("-?\\d+").find(answer)?.value?.toIntOrNull()
            ?: return ToolResult.error("视觉模型没给出编号：$answer")
        if (idx < 0 || idx >= marks.size) {
            return ToolResult.error("没找到匹配「$target」的可点元素（模型回答 $idx）")
        }
        val r = marks[idx]
        val ok = runCatching { svc.performTap(r.centerX(), r.centerY()) }.getOrDefault(false)
        XLog.i(TAG, "vision-grounded tap marker=$idx @(${r.centerX()},${r.centerY()}) target='$target' ok=$ok")
        return if (ok) ToolResult.success("已视觉定位并点击编号 $idx（目标：$target）")
        else ToolResult.error("点击编号 $idx 失败")
    }

    /** 收集可点 / 带标签的可见节点的屏幕矩形，作为候选标记（按深度优先编号）。 */
    private fun collectMarks(root: AccessibilityNodeInfo?): List<Rect> {
        if (root == null) return emptyList()
        val out = ArrayList<Rect>()
        val tmp = Rect()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || out.size >= MAX_MARKS) return
            n.getBoundsInScreen(tmp)
            val visible = tmp.width() > 8 && tmp.height() > 8
            val interesting = n.isClickable || !n.text.isNullOrBlank() || !n.contentDescription.isNullOrBlank()
            if (visible && interesting) out.add(Rect(tmp))
            for (i in 0 until n.childCount) {
                val child = n.getChild(i)
                walk(child)
                child?.recycle()
            }
        }
        walk(root)
        return out
    }

    private fun drawMarkers(src: Bitmap, marks: List<Rect>): Bitmap {
        val bmp = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(bmp)
        val box = Paint().apply {
            color = Color.RED; style = Paint.Style.STROKE; strokeWidth = 3f; isAntiAlias = true
        }
        val labelBg = Paint().apply { color = Color.RED; style = Paint.Style.FILL }
        val labelTxt = Paint().apply {
            color = Color.WHITE; textSize = 28f; isAntiAlias = true; isFakeBoldText = true
        }
        marks.forEachIndexed { i, r ->
            canvas.drawRect(r, box)
            val label = i.toString()
            val tw = labelTxt.measureText(label)
            val lx = r.left.toFloat()
            val ly = r.top.toFloat()
            canvas.drawRect(lx, ly, lx + tw + 12f, ly + 36f, labelBg)
            canvas.drawText(label, lx + 6f, ly + 28f, labelTxt)
        }
        return bmp
    }

    companion object {
        private const val TAG = "VisionMarkersTool"
        private const val MAX_MARKS = 60
    }
}
