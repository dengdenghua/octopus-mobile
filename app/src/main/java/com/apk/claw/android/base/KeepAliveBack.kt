package com.apk.claw.android.base

import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import com.apk.claw.android.service.ClawAccessibilityService

/**
 * 无障碍保活的返回拦截 —— **所有可能成为「任务根」的入口 Activity 都必须装**
 * (MainActivity / DesktopActivity / BaseActivity 全家)。只装在 BaseActivity 上不够:
 * 主对话页 MainActivity 继承的是 ComponentActivity、桌面模式 DesktopActivity 继承的是
 * AppCompatActivity,都不经过 BaseActivity,恰恰是用户最常滑动返回的两个任务根。
 *
 * 问题:开着无障碍服务时,从任务根页面滑动返回会 finish 掉根 Activity → 任务被移除 →
 * 进程可被系统(尤其国产 ROM)立即回收 → 与进程同生死的无障碍服务被一并关闭,表现为
 * 「一滑返回无障碍就自动关了」。
 *
 * 处理:仅当① 本页是任务根 且 ② 无障碍正开着时,把返回改为退到后台(moveTaskToBack),
 * 保活进程与服务,应用照常在后台待命;其余情况(非根页/未开无障碍)一律交回系统默认返回,
 * 不改变正常的页内返回与「未用无障碍用户」的退出体验。后注册的返回回调(子类 /
 * Compose BackHandler)优先级更高,不受影响。走 OnBackPressedDispatcher 注册,
 * manifest 已开 enableOnBackInvokedCallback,手势预测返回同样会经过这里。
 */
object KeepAliveBack {

    fun install(activity: ComponentActivity) {
        activity.onBackPressedDispatcher.addCallback(
            activity,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (activity.isTaskRoot && ClawAccessibilityService.isRunning()) {
                        activity.moveTaskToBack(true)
                    } else {
                        // 让出:临时禁用本回调并重新分发,走系统/其它回调的默认返回逻辑。
                        isEnabled = false
                        activity.onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            },
        )
    }
}
