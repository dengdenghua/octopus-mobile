package com.apk.claw.android.ui.browser

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.apk.claw.android.R
import com.apk.claw.android.base.BaseActivity
import com.apk.claw.android.octopus_mobile.browser.BrowserEngine
import com.apk.claw.android.octopus_mobile.browser.BrowserEngineFactory
import com.apk.claw.android.octopus_mobile.browser.EngineEvent
import com.apk.claw.android.tool.ToolRegistry
import com.apk.claw.android.widget.CommonToolbar
import com.apk.claw.android.widget.KButton
import kotlinx.coroutines.launch

/**
 * 内置浏览器页面
 *
 * 嵌入 BrowserEngine（GeckoView / SystemWebView），
 * 提供地址栏、进度条、底部工具栏、书签和扩展管理。
 */
class BrowserActivity : BaseActivity() {

    companion object {
        private const val TAG = "BrowserActivity"
        const val EXTRA_URL = "extra_url"
    }

    private lateinit var engine: BrowserEngine
    private lateinit var etUrl: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var browserContainer: FrameLayout
    private lateinit var btnRefresh: ImageButton
    private lateinit var loadingOverlay: LinearLayout
    private val bookmarkManager = BookmarkManager()

    private var isLoading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 初始化引擎
        engine = BrowserEngineFactory.selectBest(this)
        Log.i(TAG, "Browser engine: ${engine.name}")

        val root = buildLayout()
        setContentView(root)

        // 注册引擎到 ToolRegistry
        ToolRegistry.setBrowserEngine(engine)

        // 嵌入浏览器视图
        val browserView = engine.createView(this)
        browserContainer.addView(browserView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        // 添加 loading 遮罩到浏览器容器上层
        browserContainer.addView(loadingOverlay)

        // 收集引擎事件
        observeEngineEvents()

        // 处理外部传入的 URL
        val url = intent.getStringExtra(EXTRA_URL)
        if (!url.isNullOrEmpty()) {
            navigateTo(url)
        } else {
            navigateTo("https://www.google.com")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 清除引擎引用
        browserContainer.removeAllViews()
        // 清除 ToolRegistry 中的浏览器引擎，避免内存泄漏
        ToolRegistry.clearBrowserEngine()
    }

    override fun onBackPressed() {
        // 浏览器内的后退由引擎处理，这里简单地 finish
        super.onBackPressed()
    }

    // ── 布局构建 ─────────────────────────────────────

    private fun buildLayout(): LinearLayout {
        val dp8 = dp(8)
        val dp12 = dp(12)
        val dp48 = dp(48)

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)

            // Toolbar
            addView(CommonToolbar(this@BrowserActivity).apply {
                setTitle("浏览器")
                setTitleCentered(false)
                showBackButton(true) { finish() }
                setActionText(engine.name) {}
            })

            // 地址栏行
            val addressBar = LinearLayout(this@BrowserActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                setPadding(dp12, dp8, dp12, dp8)
            }

            etUrl = EditText(this@BrowserActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                hint = "输入网址或搜索"
                setSingleLine(true)
                inputType = InputType.TYPE_TEXT_VARIATION_URI
                imeOptions = EditorInfo.IME_ACTION_GO
                textSize = 14f
                setBackgroundResource(android.R.drawable.edit_text)
                setPadding(dp8, dp8, dp8, dp8)
                setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                        navigateTo(text.toString().trim())
                        hideKeyboard()
                        true
                    } else false
                }
            }
            addressBar.addView(etUrl)

            btnRefresh = ImageButton(this@BrowserActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp48, dp48)
                setImageResource(android.R.drawable.ic_menu_rotate)
                setBackgroundColor(Color.TRANSPARENT)
                contentDescription = "刷新"
                setOnClickListener {
                    if (isLoading) {
                        // 停止加载（通过重新导航到当前 URL 模拟）
                        navigateTo(engine.currentUrl())
                    } else {
                        navigateTo(engine.currentUrl())
                    }
                }
            }
            addressBar.addView(btnRefresh)

            addView(addressBar)

            // 进度条
            progressBar = ProgressBar(this@BrowserActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(3))
                max = 100
                progress = 0
                visibility = View.GONE
            }
            addView(progressBar)

            // 浏览器容器
            browserContainer = FrameLayout(this@BrowserActivity).apply {
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
            }
            addView(browserContainer)

            // Loading 遮罩（防止 GeckoView 白屏）
            loadingOverlay = LinearLayout(this@BrowserActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(Color.parseColor("#E8F0F0F0"))
                layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            }
            val spinner = ProgressBar(this@BrowserActivity).apply {
                layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
            }
            loadingOverlay.addView(spinner)
            val tvLoading = TextView(this@BrowserActivity).apply {
                text = "引擎加载中..."
                textSize = 14f
                setTextColor(Color.GRAY)
                setPadding(0, dp(8), 0, 0)
            }
            loadingOverlay.addView(tvLoading)

            // 底部工具栏
            addView(buildBottomToolbar())
        }
    }

    private fun buildBottomToolbar(): LinearLayout {
        val dp48 = dp(48)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(52))
            setPadding(dp(8), 0, dp(8), 0)
            setBackgroundColor(Color.WHITE)
            elevation = dp(4).toFloat()

            // 后退
            addView(makeToolbarButton(android.R.drawable.ic_media_rew, "后退") {
                engine.evaluateJs("window.history.back()")
            })

            // 前进
            addView(makeToolbarButton(android.R.drawable.ic_media_ff, "前进") {
                engine.evaluateJs("window.history.forward()")
            })

            // Home
            addView(makeToolbarButton(android.R.drawable.ic_menu_today, "首页") {
                navigateTo("https://www.google.com")
            })

            // 书签
            addView(makeToolbarButton(android.R.drawable.star_big_on, "书签") {
                showBookmarkDialog()
            })

            // 菜单（扩展）
            addView(makeToolbarButton(android.R.drawable.ic_menu_more, "扩展") {
                showExtensionDialog()
            })
        }
    }

    private fun makeToolbarButton(iconRes: Int, desc: String, onClick: () -> Unit): ImageButton {
        return ImageButton(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f)
            setImageResource(iconRes)
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = desc
            setOnClickListener { onClick() }
        }
    }

    // ── 引擎事件收集 ─────────────────────────────────

    private fun observeEngineEvents() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                engine.events().collect { event ->
                    when (event) {
                        is EngineEvent.PageStarted -> {
                            isLoading = true
                            progressBar.visibility = View.VISIBLE
                            loadingOverlay.visibility = View.VISIBLE
                            btnRefresh.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                        }
                        is EngineEvent.PageFinished -> {
                            isLoading = false
                            progressBar.visibility = View.GONE
                            loadingOverlay.visibility = View.GONE
                            btnRefresh.setImageResource(android.R.drawable.ic_menu_rotate)
                            etUrl.setText(event.url)
                            // 自动更新标题到 toolbar（如果有 title 就显示）
                        }
                        is EngineEvent.ProgressChanged -> {
                            progressBar.progress = event.percent
                        }
                        is EngineEvent.Error -> {
                            isLoading = false
                            progressBar.visibility = View.GONE
                            Toast.makeText(
                                this@BrowserActivity,
                                "加载错误: ${event.description}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        is EngineEvent.DownloadStart -> {
                            Toast.makeText(
                                this@BrowserActivity,
                                "下载: ${event.suggestedFilename}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        is EngineEvent.ConsoleMessage -> {
                            Log.d(TAG, "[${event.level}] ${event.message}")
                        }
                        is EngineEvent.JsAlert -> {
                            AlertDialog.Builder(this@BrowserActivity)
                                .setMessage(event.message)
                                .setPositiveButton("OK") { _, _ -> event.onResult(true) }
                                .setNegativeButton("Cancel") { _, _ -> event.onResult(false) }
                                .show()
                        }
                    }
                }
            }
        }
    }

    // ── 导航 ─────────────────────────────────────────

    private fun navigateTo(input: String) {
        val url = if (input.contains(".") && !input.contains(" ")) {
            if (input.startsWith("http://") || input.startsWith("https://")) input
            else "https://$input"
        } else {
            "https://www.google.com/search?q=${java.net.URLEncoder.encode(input, "UTF-8")}"
        }
        etUrl.setText(url)
        engine.navigate(url)
    }

    // ── 书签 Dialog ──────────────────────────────────

    private fun showBookmarkDialog() {
        val bookmarks = bookmarkManager.getAll()
        val currentUrl = engine.currentUrl()
        val isBookmarked = bookmarkManager.isBookmarked(currentUrl)

        val items = mutableListOf<String>()
        if (isBookmarked) {
            items.add("⭐ 取消收藏当前页")
        } else {
            items.add("☆ 收藏当前页")
        }
        items.add("── 已保存书签 ──")
        bookmarks.forEach { items.add("${it.title}\n${it.url}") }

        AlertDialog.Builder(this)
            .setTitle("书签")
            .setItems(items.toTypedArray()) { _, which ->
                when {
                    which == 0 -> {
                        if (isBookmarked) {
                            bookmarkManager.remove(currentUrl)
                            Toast.makeText(this, "已取消收藏", Toast.LENGTH_SHORT).show()
                        } else {
                            // 用当前 URL 和 etUrl 的文字作为标题
                            bookmarkManager.add(currentUrl, etUrl.text.toString().ifEmpty { currentUrl })
                            Toast.makeText(this, "已收藏", Toast.LENGTH_SHORT).show()
                        }
                    }
                    which > 1 -> {
                        val bookmark = bookmarks[which - 2]
                        navigateTo(bookmark.url)
                    }
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    // ── 扩展 Dialog ──────────────────────────────────

    private fun showExtensionDialog() {
        if (!engine.supportsExtensions) {
            Toast.makeText(this, "当前引擎(${engine.name})不支持扩展", Toast.LENGTH_SHORT).show()
            return
        }

        val editText = EditText(this).apply {
            hint = "Chrome Web Store ID 或扩展 URL"
            setSingleLine(true)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        AlertDialog.Builder(this)
            .setTitle("安装扩展")
            .setMessage("输入 Chrome Web Store 扩展 ID 或 .xpi/.crx 下载链接")
            .setView(editText)
            .setPositiveButton("安装") { _, _ ->
                val input = editText.text.toString().trim()
                if (input.isNotEmpty()) {
                    installExtension(input)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun installExtension(input: String) {
        Toast.makeText(this, "正在安装扩展...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            try {
                val installer = com.apk.claw.android.octopus_mobile.browser.ExtensionInstaller(
                    engine as com.apk.claw.android.octopus_mobile.browser.GeckoViewEngine,
                    cacheDir
                )
                val result = if (input.length == 32 && !input.contains("/") && !input.contains(".")) {
                    // 看起来像 Chrome Web Store ID
                    installer.installFromChromeWebStore(input)
                } else {
                    installer.installFromUrl(input)
                }
                when (result) {
                    is com.apk.claw.android.octopus_mobile.browser.ExtensionInstaller.InstallResult.Success -> {
                        Toast.makeText(
                            this@BrowserActivity,
                            "安装成功: ${result.extensionName} v${result.extensionVersion}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    is com.apk.claw.android.octopus_mobile.browser.ExtensionInstaller.InstallResult.Failed -> {
                        Toast.makeText(
                            this@BrowserActivity,
                            "安装失败: ${result.reason}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this@BrowserActivity,
                    "安装异常: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // ── 辅助 ─────────────────────────────────────────

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(etUrl.windowToken, 0)
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
