package com.apk.claw.android.floating

import android.app.Application
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.R
import com.apk.claw.android.channel.Channel

/**
 * 悬浮控制已统一为单一「实时控制条」([LiveControlOverlay])。
 *
 * 历史上这里是一个常驻的圆形悬浮球，与控制条会同时出现两个悬浮物。现把本类改为
 * 薄委托层：保留原有公开 API 以兼容所有调用方（IM 渠道任务编排器等），内部全部
 * 委托给 [LiveControlOverlay]，且不再显示常驻圆球——平时无悬浮，任务运行时才出现
 * 控制条（步骤 + 停止），与 App 内对话发起的任务共用同一个悬浮元素。
 */
object FloatingCircleManager {

    /** 兼容旧调用方：圆球点击回调（已无圆球，保留空实现）。 */
    var onFloatClick: () -> Unit = {}

    /** IM/编排器任务的停止：委托到正在运行的任务取消。 */
    private fun cancelRunning() {
        runCatching { ClawApplication.appViewModelInstance.cancelCurrentTask() }
    }

    /** 兼容：不再显示常驻圆球（改由任务期间的控制条承担）。 */
    fun show(application: Application, x: Int? = null, y: Int? = null) {}

    fun hide() = LiveControlOverlay.hide()

    fun isShowing(): Boolean = false

    fun setIdleState() = LiveControlOverlay.hide()

    /** 收到 IM 任务：展开控制条显示任务文本。 */
    fun showTaskNotify(taskText: String, channel: Channel) {
        LiveControlOverlay.show("📨 " + taskText.take(40)) { cancelRunning() }
    }

    /** 任务执行中：控制条显示进度（show 幂等：已显示则只更新文案）。 */
    fun setRunningState(round: Int, channel: Channel) {
        LiveControlOverlay.show(ClawApplication.instance.getString(R.string.floating_circle_running_state)) { cancelRunning() }
    }

    fun setSuccessState() = LiveControlOverlay.finish(true, ClawApplication.instance.getString(R.string.floating_circle_success_state))

    fun setErrorState() = LiveControlOverlay.finish(false, ClawApplication.instance.getString(R.string.floating_circle_error_state))
}
