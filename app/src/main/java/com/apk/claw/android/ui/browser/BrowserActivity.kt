package com.apk.claw.android.ui.browser

import android.app.SearchManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import com.apk.claw.android.base.BaseActivity
import com.apk.claw.android.octopus_mobile.browser.SearchEngines
import com.apk.claw.android.ui.compose.theme.OctopusTheme

/**
 * AI 浏览器 —— Compose 版。
 *
 * 页面逻辑与 UI 全部收敛到 [BrowserScreen]，Activity 只负责：
 * - 接收外部传入的 URL（`EXTRA_URL` 或 ACTION_VIEW 的 intent.data）
 * - 响应 ACTION_WEB_SEARCH 把 query 转成搜索引擎 URL
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
        val url = resolveIntentUrl(intent)
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
     * 解析启动 Intent 得到要加载的 URL:
     * - 显式 EXTRA_URL(内部跳转)
     * - ACTION_VIEW: intent.data 即 http/https 链接
     * - ACTION_WEB_SEARCH: 取 SearchManager.QUERY 走默认搜索引擎
     */
    private fun resolveIntentUrl(intent: Intent?): String? {
        if (intent == null) return null
        intent.getStringExtra(EXTRA_URL)?.let { return it }
        when (intent.action) {
            Intent.ACTION_VIEW -> {
                val data = intent.data ?: return null
                // 仅接受 http/https,避免 file/content 等被外部唤起后落到 WebView。
                if (data.scheme == "http" || data.scheme == "https") return data.toString()
            }
            Intent.ACTION_WEB_SEARCH -> {
                val query = intent.getStringExtra(SearchManager.QUERY)?.takeIf { it.isNotBlank() }
                    ?: return null
                return SearchEngines.DEFAULT.searchUrl(query)
            }
        }
        return null
    }

    /**
     * 禁用 BaseActivity 自动添加状态栏 padding；Compose 内部通过
     * `statusBarsPadding()` / `navigationBarsPadding()` 自行处理 insets。
     */
    override fun isApplyStatusBarPadding(): Boolean = false
}
