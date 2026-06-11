package com.apk.claw.android.cast

import android.content.Context
import android.util.Log
import android.view.Display
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 异步投屏服务 —— 管理外接显示器上的 Agent 工作区。
 *
 * 架构对标 Sula 超级启动器的"异步投屏"功能：
 *  - 手机屏：正常手机界面（用户可继续操作）
 *  - 外接屏：独立的 Agent 工作区，App 以 freeform 窗口运行
 *
 * 技术栈：
 *  - ExternalDisplayManager：检测外接显示器
 *  - AgentPresentation：在外接屏上创建独立窗口
 *  - ShizukuShellService.launchFreeform(displayId)：在外接屏上启动 App
 *
 * 使用方式：
 * ```
 * val castService = ScreenCastService(context)
 * castService.start()
 *
 * // 在外接屏上启动 App
 * castService.launchAppOnExternalDisplay("com.tencent.mm")
 *
 * // 停止投屏
 * castService.stop()
 * ```
 */
class ScreenCastService(private val context: Context) {

    companion object {
        private const val TAG = "ScreenCast"

        /** 单例实例 */
        @Volatile
        private var instance: ScreenCastService? = null

        fun getInstance(context: Context): ScreenCastService {
            return instance ?: synchronized(this) {
                instance ?: ScreenCastService(context.applicationContext).also { instance = it }
            }
        }
    }

    /** 外接显示器管理器 */
    val displayManager = ExternalDisplayManager(context)

    /** 当前 Presentation 实例 */
    private var presentation: AgentPresentation? = null

    /** 投屏状态 */
    @Volatile
    var isCasting: Boolean = false
        private set

    /** 投屏状态 Flow（供 UI 观察） */
    private val _castState = MutableStateFlow<CastState>(CastState.Idle)
    val castState: StateFlow<CastState> = _castState.asStateFlow()

    /** 状态变化回调 */
    var onCastStateChanged: ((Boolean, String) -> Unit)? = null

    init {
        // 监听外接显示器变化
        displayManager.onDisplayAdded = { display ->
            Log.i(TAG, "External display connected: ${display.name}")
            if (!isCasting) {
                startPresentation(display)
            }
        }

        displayManager.onDisplayRemoved = { _ ->
            Log.w(TAG, "External display disconnected")
            stopPresentation()
        }
    }

    /**
     * 启动投屏服务（注册显示器监听）。
     */
    fun start() {
        displayManager.register()
        Log.i(TAG, "ScreenCastService started")

        // 如果已经有外接显示器，立即开始投屏
        if (displayManager.hasExternalDisplay()) {
            startPresentation(displayManager.getPresentationDisplay()!!)
        }
    }

    /**
     * 停止投屏服务。
     */
    fun stop() {
        stopPresentation()
        displayManager.unregister()
        Log.i(TAG, "ScreenCastService stopped")
    }

    /**
     * 在外接显示器上创建 Presentation 窗口。
     */
    private fun startPresentation(display: Display) {
        if (presentation != null) {
            Log.w(TAG, "Presentation already active, skipping")
            return
        }

        try {
            presentation = AgentPresentation(context, display).apply {
                onStatusChanged = { status ->
                    Log.i(TAG, "Agent status: $status")
                }
            }
            presentation?.show()
            isCasting = true
            _castState.value = CastState.Casting(displayManager.getExternalDisplayInfo())

            val info = displayManager.getExternalDisplayInfo()
            Log.i(TAG, "Started presentation on $info")
            onCastStateChanged?.invoke(true, info)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start presentation", e)
            presentation = null
            isCasting = false
            _castState.value = CastState.Error("Failed: ${e.message}")
            onCastStateChanged?.invoke(false, "Failed: ${e.message}")
        }
    }

    /**
     * 关闭 Presentation 窗口。
     */
    private fun stopPresentation() {
        try {
            presentation?.dismiss()
        } catch (e: Exception) {
            Log.w(TAG, "Error dismissing presentation", e)
        }
        presentation = null
        isCasting = false
        _castState.value = CastState.Idle
        onCastStateChanged?.invoke(false, "Disconnected")
    }

    /**
     * 在外接显示器上启动 App（freeform 模式）。
     *
     * 通过 Shizuku 调用 `am start --display <displayId> --windowingMode 5`
     * 将 App 启动到外接屏上。
     *
     * @param packageName App 包名
     * @param x 窗口 X 位置（在外接屏上的坐标）
     * @param y 窗口 Y 位置
     * @param width 窗口宽度
     * @param height 窗口高度
     * @return true=启动成功
     */
    fun launchAppOnExternalDisplay(
        packageName: String,
        x: Int = 100,
        y: Int = 100,
        width: Int = 800,
        height: Int = 600
    ): Boolean {
        if (!isCasting) {
            Log.w(TAG, "Cannot launch app: not casting")
            return false
        }

        val displayId = displayManager.getExternalDisplayId()
        if (displayId == Display.DEFAULT_DISPLAY) {
            Log.w(TAG, "Cannot launch app: no external display")
            return false
        }

        // 使用 Shizuku 在外接屏上启动 App
        val result = com.apk.claw.android.shizuku.ShizukuShellService.launchFreeformOnDisplay(
            packageName, displayId, x, y, width, height
        )

        if (result == true) {
            presentation?.updateStatus("Launched $packageName on display #$displayId")
        }

        return result == true
    }

    /**
     * 获取投屏状态信息（用于 API 返回）。
     */
    fun getStatusInfo(): Map<String, Any> {
        return mapOf(
            "casting" to isCasting,
            "externalDisplay" to (displayManager.getExternalDisplayInfo()),
            "externalDisplayId" to displayManager.getExternalDisplayId(),
            "hasExternalDisplay" to displayManager.hasExternalDisplay()
        )
    }
}

/**
 * 投屏状态密封类
 */
sealed class CastState {
    object Idle : CastState()
    data class Casting(val displayInfo: String) : CastState()
    data class Error(val message: String) : CastState()
}
