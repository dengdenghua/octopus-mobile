package com.apk.claw.android.plugin

/**
 * 桌面窗口开关桥 —— 让后台的 `app_action` 工具能请求"把某 mini-app 开成桌面浮动窗口"
 * (而不是全屏 Activity)。桌面模式([DesktopActivity])活跃时注册 [opener],离开时清空。
 * app_action 在目标 mini-app 未运行时先试 [open];成功(桌面在前台)则开窗,失败则回退全屏 Activity。
 */
object MiniAppWindowController {

    /** 桌面注册的开窗器:入参 appId,返回是否已受理(桌面在前台)。桌面负责切到主线程开窗。 */
    @Volatile
    var opener: ((String) -> Boolean)? = null

    fun open(appId: String): Boolean = runCatching { opener?.invoke(appId) ?: false }.getOrDefault(false)
}
