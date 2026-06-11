package com.apk.claw.android.cast

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display

/**
 * 外接显示器管理器 —— 检测和管理外接显示器（USB-C / HDMI / 无线投屏）。
 *
 * 核心 API：
 *  - DisplayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
 *    返回所有可用于投屏的外接显示器列表
 *  - Display.FLAG_PRESENTATION 标记该显示器适合作为演示屏
 *
 * 使用方式：
 * ```
 * val manager = ExternalDisplayManager(context)
 * manager.register()
 *
 * if (manager.hasExternalDisplay()) {
 *     val display = manager.getPresentationDisplay()
 *     // 创建 Presentation 窗口
 * }
 * ```
 */
class ExternalDisplayManager(private val context: Context) {

    companion object {
        private const val TAG = "ExternalDisplay"
    }

    private val displayManager: DisplayManager =
        context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 当前可用的外接显示器列表 */
    private val presentationDisplays = mutableListOf<Display>()

    /** 显示器状态变化回调 */
    var onDisplaysChanged: ((List<Display>) -> Unit)? = null

    /** 显示器连接回调 */
    var onDisplayAdded: ((Display) -> Unit)? = null

    /** 显示器断开回调 */
    var onDisplayRemoved: ((Display?) -> Unit)? = null

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            refreshDisplays()
        }

        override fun onDisplayRemoved(displayId: Int) {
            refreshDisplays()
        }

        override fun onDisplayChanged(displayId: Int) {
            refreshDisplays()
        }
    }

    /**
     * 注册显示器监听。应在 Application.onCreate() 或 Service 启动时调用。
     */
    fun register() {
        displayManager.registerDisplayListener(displayListener, mainHandler)
        refreshDisplays()
        Log.i(TAG, "Registered display listener, found ${presentationDisplays.size} external display(s)")
    }

    /**
     * 注销显示器监听。
     */
    fun unregister() {
        displayManager.unregisterDisplayListener(displayListener)
        presentationDisplays.clear()
    }

    /**
     * 刷新外接显示器列表。
     */
    private fun refreshDisplays() {
        val oldCount = presentationDisplays.size
        presentationDisplays.clear()

        // 获取所有演示类显示器（外接屏 / 无线投屏）
        val displays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        presentationDisplays.addAll(displays)

        val newCount = presentationDisplays.size

        if (newCount > oldCount && newCount > 0) {
            // 新增显示器
            val newDisplay = presentationDisplays.last()
            Log.i(TAG, "External display added: id=${newDisplay.displayId}, name=${newDisplay.name}")
            onDisplayAdded?.invoke(newDisplay)
        } else if (newCount < oldCount) {
            // 显示器断开
            Log.i(TAG, "External display removed (was $oldCount, now $newCount)")
            onDisplayRemoved?.invoke(null as Display?)
        }

        if (newCount != oldCount) {
            onDisplaysChanged?.invoke(presentationDisplays.toList())
        }
    }

    /**
     * 是否有可用的外接显示器。
     */
    fun hasExternalDisplay(): Boolean = presentationDisplays.isNotEmpty()

    /**
     * 获取第一个可用的外接显示器（用于 Presentation）。
     * 返回 null 表示没有外接显示器。
     */
    fun getPresentationDisplay(): Display? = presentationDisplays.firstOrNull()

    /**
     * 获取所有可用的外接显示器列表。
     */
    fun getAllPresentationDisplays(): List<Display> = presentationDisplays.toList()

    /**
     * 获取显示器的显示 ID。
     * 主屏幕 ID = 0 (Display.DEFAULT_DISPLAY)
     * 外接屏 ID > 0
     */
    fun getExternalDisplayId(): Int {
        return getPresentationDisplay()?.displayId ?: Display.DEFAULT_DISPLAY
    }

    /**
     * 获取外接显示器的分辨率信息。
     *
     * @return Pair(width, height)，无外接屏时返回 null
     */
    fun getExternalDisplaySize(): Pair<Int, Int>? {
        val display = getPresentationDisplay() ?: return null
        val metrics = android.util.DisplayMetrics()
        display.getMetrics(metrics)
        return Pair(metrics.widthPixels, metrics.heightPixels)
    }

    /**
     * 获取外接显示器的描述信息（用于 UI 展示和日志）。
     */
    fun getExternalDisplayInfo(): String {
        val display = getPresentationDisplay() ?: return "No external display"
        val size = getExternalDisplaySize() ?: return "Display #${display.displayId}: unknown size"
        return "Display #${display.displayId}: ${display.name}, ${size.first}x${size.second}"
    }
}
