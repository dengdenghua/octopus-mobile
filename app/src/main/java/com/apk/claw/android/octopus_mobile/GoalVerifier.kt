package com.apk.claw.android.octopus_mobile

import android.graphics.Bitmap
import com.apk.claw.android.utils.XLog

/**
 * 目标自校验 —— 用 VLM 看当前屏幕,判断"这个目标真的达成了吗",而不是听 LLM
 * 自己说"我做完了"。对应 octopus-agent 的 verdict_repair(产出→评判→修复)。
 *
 * UI 自动化最隐蔽的失败是:点击成功了,但目标没达成(点错按钮、被弹窗挡住、页面
 * 没跳转)。ReAct 循环在 LLM 不再调工具时就判 Done —— 缺一道"看屏确认"。
 *
 * **失败开放(fail-open)**:VLM 没配置 / 没截图 / 回答含糊时一律判"通过",绝不
 * 拦正常完成 —— 这样接进去永不弱于现状;价值只在 VLM 明确说"没达成"时兑现。
 *
 * 接线(在 LightweightReAct 判 Done 处,需 Android 构建 + 真机验证):
 * ```
 * if (response.toolCalls.isEmpty()) {
 *     val v = GoalVerifier.verify(goalText, captureScreen())   // captureScreen: suspend () -> Bitmap?
 *     if (!v.achieved && repairsLeft-- > 0) {
 *         history.add(systemMsg("目标尚未达成:${v.reason}。请继续操作直到完成。"))
 *         continue   // 修复:带着原因再来一轮
 *     }
 *     return TaskResult.Done(...)
 * }
 * ```
 */
object GoalVerifier {
    private const val TAG = "GoalVerifier"

    data class Verdict(val achieved: Boolean, val reason: String)

    /**
     * @param goal 本次任务的自然语言目标,如"在微信里给张三发'到了'"
     * @param screenshot 执行后的当前屏幕;null 时无法校验
     * @return 达成判定 + 一句原因
     */
    suspend fun verify(goal: String, screenshot: Bitmap?): Verdict {
        if (goal.isBlank() || screenshot == null || !VisionAnalyzer.isConfigured()) {
            return Verdict(achieved = true, reason = "无法校验(未配置VLM/无截图),默认放行")
        }
        return try {
            val question =
                "任务目标:「$goal」。\n" +
                    "请只看当前这张手机截图,判断该目标是否已经达成。\n" +
                    "第一行只回 YES 或 NO;第二行用一句话说明原因(没达成就指出差在哪)。"
            val answer = VisionAnalyzer.analyze(screenshot, question)
            parse(answer)
        } catch (e: Exception) {
            XLog.w(TAG, "verify failed, fail-open: ${e.message}")
            Verdict(achieved = true, reason = "校验异常,默认放行:${e.message}")
        }
    }

    /** 解析 VLM 回答。识别不出肯定/否定时,fail-open 判达成。 */
    internal fun parse(answer: String): Verdict {
        val text = answer.trim()
        val head = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val reason = text.lineSequence()
            .drop(1)
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?: head
        val lowerHead = head.lowercase()
        val negative =
            lowerHead.startsWith("no") ||
                head.startsWith("否") ||
                head.contains("未达成") ||
                head.contains("没有达成") ||
                head.contains("未完成")
        val positive =
            lowerHead.startsWith("yes") ||
                head.startsWith("是") ||
                head.contains("已达成") ||
                head.contains("已完成")
        // 只有明确否定才判失败;含糊 → fail-open。
        val achieved = !(negative && !positive)
        return Verdict(achieved = achieved, reason = reason.ifBlank { text })
    }
}
