package com.apk.claw.android.ui.browser

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import com.apk.claw.android.base.BaseActivity
import com.apk.claw.android.ui.compose.theme.OctopusTheme

/**
 * AI 浏览器 —— Compose 版。
 *
 * 页面逻辑与 UI 全部收敛到 [BrowserScreen]，Activity 只负责：
 * - 接收外部传入的 URL（`EXTRA_URL`）
 * - 设置 Edge-to-Edge 窗口
 * - 挂载 Compose 主题
 */
class BrowserActivity : BaseActivity() {

    companion object {
        const val EXTRA_URL = "extra_url"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val url = intent.getStringExtra(EXTRA_URL)
        setContent {
            OctopusTheme {
                BrowserScreen(
                    initialUrl = url,
                    onClose = { finish() },
                )
            }
        }
    }

    /**
     * 禁用 BaseActivity 自动添加状态栏 padding；Compose 内部通过
     * `statusBarsPadding()` / `navigationBarsPadding()` 自行处理 insets。
     */
    override fun isApplyStatusBarPadding(): Boolean = false
}
