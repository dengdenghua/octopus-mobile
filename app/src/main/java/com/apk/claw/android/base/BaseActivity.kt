package com.apk.claw.android.base

import android.content.res.Configuration
import android.content.res.Resources
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.blankj.utilcode.util.AdaptScreenUtils
import com.blankj.utilcode.util.BarUtils
import com.apk.claw.android.R
import com.apk.claw.android.service.ClawAccessibilityService

/**
 *
 * 屏幕适配单位用的是pt, 如果用dp在有些设备上会导致toast换行位置不正确
 */
open class BaseActivity : AppCompatActivity() {

    override fun getResources(): Resources {
        val resources = super.getResources()
        return AdaptScreenUtils.adaptWidth(resources, getDesignWidth())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyStatusBarMode()

        // 统一处理状态栏高度 - 在布局加载后应用
        applyStatusBarPadding()

        installKeepAliveBackHandler()
    }

    /**
     * 无障碍保活的返回拦截。
     *
     * 问题:开着无障碍服务时,从「任务根」页面滑动返回会 finish 掉根 Activity → 任务被移除 →
     * 进程可被系统(尤其国产 ROM)立即回收 → 与进程同生死的无障碍服务被一并关闭,表现为
     * 「一滑返回无障碍就自动关了」。
     *
     * 处理:仅当① 本页是任务根 且 ② 无障碍正开着时,把返回改为退到后台(moveTaskToBack),
     * 保活进程与服务,应用照常在后台待命;其余情况(非根页/未开无障碍)一律交回系统默认返回,
     * 不改变正常的页内返回与「未用无障碍用户」的退出体验。子类/Compose 自己注册的返回回调优先级更高,
     * 不受影响。
     */
    private fun installKeepAliveBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isTaskRoot && ClawAccessibilityService.isRunning()) {
                    moveTaskToBack(true)
                } else {
                    // 让出:临时禁用本回调并重新分发,走系统/其它回调的默认返回逻辑。
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
    }

    /**
     * 主题切换时自动回调，更新状态栏文字颜色
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyStatusBarMode()
    }

    /**
     * 根据当前主题模式设置状态栏文字颜色
     * 浅色主题 → 深色文字（light mode）
     * 深色主题 → 浅色文字（dark mode）
     */
    private fun applyStatusBarMode() {
        val isNightMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        BarUtils.setStatusBarLightMode(this, !isNightMode)
    }

    /**
     * 为根视图添加状态栏高度的顶部 padding
     * 子类可通过重写 isApplyStatusBarPadding() 来禁用
     */
    private fun applyStatusBarPadding() {
        window.decorView.post {
            val rootView = findViewById<ViewGroup>(android.R.id.content)?.getChildAt(0)
            rootView?.let { applyPaddingToRootView(it) }
        }
    }

    /**
     * 为根视图应用状态栏高度 padding
     * 如果布局已经处理了 insets，可以重写此方法自定义行为
     */
    protected open fun applyPaddingToRootView(rootView: View) {
        if (!isApplyStatusBarPadding()) return

        val statusBarHeight = BarUtils.getStatusBarHeight()
        val existingPaddingTop = rootView.paddingTop

        // 只在没有 padding 或 padding 小于状态栏高度时才添加
        if (existingPaddingTop < statusBarHeight) {
            rootView.updatePadding(top = statusBarHeight)
        }
    }

    /**
     * 子类可重写此方法来禁用自动添加状态栏高度
     */
    protected open fun isApplyStatusBarPadding(): Boolean = true

    /**
     * 屏幕适配-设计稿尺寸
     */
    open fun getDesignWidth(): Int {
        return 402
    }
}