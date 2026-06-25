package com.apk.claw.android.octopus_mobile

import com.apk.claw.android.service.ClawAccessibilityService
import com.apk.claw.android.utils.XLog

/**
 * 插屏/弹窗噪声自动消除 —— UI 自动化在野生 App 里的头号杀手是各种插屏挡路:
 * 广告插屏、"给个好评"、更新提示、引导浮层。Agent 不该每次都得"想"怎么关掉它们。
 *
 * 安全边界(很重要)：
 *  - 只在 Agent 任务执行期间被调（[com.apk.claw.android.agent.DefaultAgentService]
 *    每步工具前调一次），**不影响用户正常用机**；
 *  - **只点明确的"跳过 / 关闭 / 以后再说 / Skip / Not now"类按钮**，且只认短文字按钮，
 *    绝不点"允许/同意/确定"这类需要 Agent 自己判断的权限/确认框（那些仍交给 Agent）；
 *  - 失败开放：无服务 / 找不到按钮 → 直接返回 false，什么都不做。
 */
object PopupDetector {
    private const val TAG = "PopupDetector"

    // 仅消除"噪声插屏"的关闭/跳过类按钮。不含 允许/同意/确定（权限决策交给 Agent）。
    private val DISMISS_TEXTS = listOf(
        "跳过广告", "跳过", "关闭广告", "以后再说", "下次再说", "暂不更新", "暂不",
        "残忍拒绝", "我知道了", "知道了", "我再想想", "稍后",
        "Skip ad", "Skip Ad", "Skip", "Not now", "No thanks", "Maybe later", "Later", "Dismiss",
    )

    // 关闭类纯符号按钮（右上角 X）。单独处理：要求 text/desc 恰好是它。
    private val CLOSE_GLYPHS = listOf("×", "✕", "✖", "x", "X", "关闭", "Close")

    /**
     * 扫一眼当前屏，若命中噪声插屏的消除按钮就点掉。返回是否点了（点了说明清掉了一层遮挡）。
     */
    fun tryDismiss(): Boolean {
        val svc = ClawAccessibilityService.getInstance() ?: return false
        if (!ClawAccessibilityService.isRunning()) return false

        for (text in DISMISS_TEXTS) {
            val nodes = runCatching { svc.findNodesByText(text) }.getOrNull() ?: continue
            if (nodes.isEmpty()) continue
            // 只点"短文字"节点，避免点中含这些词的长正文（如一段说明里出现"跳过"）。
            val btn = nodes.firstOrNull { (it.text?.length ?: 99) <= text.length + 3 }
            val clicked = btn != null && runCatching { svc.clickNode(btn) }.getOrDefault(false)
            ClawAccessibilityService.recycleNodes(nodes)
            if (clicked) {
                XLog.i(TAG, "auto-dismissed interstitial via '$text'")
                return true
            }
        }

        // 右上角纯 X 关闭：text/desc 恰好是关闭符号才点（极保守）。
        for (g in CLOSE_GLYPHS) {
            val nodes = runCatching { svc.findNodesByText(g) }.getOrNull() ?: continue
            val btn = nodes.firstOrNull { (it.text?.toString()?.trim() == g) || (it.contentDescription?.toString()?.trim() == g) }
            val clicked = btn != null && runCatching { svc.clickNode(btn) }.getOrDefault(false)
            ClawAccessibilityService.recycleNodes(nodes)
            if (clicked) {
                XLog.i(TAG, "auto-dismissed interstitial via close-glyph '$g'")
                return true
            }
        }
        return false
    }
}
