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
import com.apk.claw.android.octopus_mobile.browser.SearchEngines
import com.apk.claw.android.utils.KVUtils
import com.apk.claw.android.tool.ToolRegistry
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.toArgb
import com.apk.claw.android.ui.compose.theme.OctopusColors

/**
 * AI 浏览器页面
 *
 * 设计理念：极简 + AI 原生
 * - 顶部：极简导航栏（返回 + AI 地址栏 + 标签管理）
 * - 中间：浏览器引擎渲染区
 * - 底部：浮动 AI 工具条 + 导航按钮
 * - AI 模式：地址栏即 AI 输入，关键词自动路由到 Agent
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
    private lateinit var aiBadge: TextView
    private lateinit var bottomBar: LinearLayout
    private val bookmarkManager = BookmarkManager()
    private var tts: android.speech.tts.TextToSpeech? = null
    private var isLoading = false
    private var aiMode = true

    // 主题色（跟随 OctopusColors）
    private val cBg get() = OctopusColors.Background.toArgb()
    private val cSurface get() = OctopusColors.Surface.toArgb()
    private val cSurface2 get() = OctopusColors.SurfaceVariant.toArgb()
    private val cPrimary get() = OctopusColors.Primary.toArgb()
    private val cText get() = OctopusColors.TextPrimary.toArgb()
    private val cMuted get() = OctopusColors.TextMuted.toArgb()
    private val cBorder get() = OctopusColors.Border.toArgb()
    private val cAccent get() = OctopusColors.Accent.toArgb()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        engine = BrowserEngineFactory.selectBest(this)
        Log.i(TAG, "Browser engine: ${engine.name}")

        val root = buildLayout()
        setContentView(root)
        runCatching { window.statusBarColor = cBg }
        runCatching { window.navigationBarColor = cBg }

        ToolRegistry.setBrowserEngine(engine)

        val browserView = engine.createView(this)
        browserContainer.addView(browserView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        browserContainer.addView(loadingOverlay)

        observeEngineEvents()

        val url = intent.getStringExtra(EXTRA_URL)
        if (!url.isNullOrEmpty()) {
            navigateTo(url)
        } else {
            navigateTo(SearchEngines.byId(KVUtils.getSearchEngine()).home)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        browserContainer.removeAllViews()
        ToolRegistry.clearBrowserEngine()
        runCatching { tts?.stop(); tts?.shutdown() }
        tts = null
    }

    override fun onBackPressed() {
        super.onBackPressed()
    }

    // ── 布局构建 ─────────────────────────────────────

    private fun buildLayout(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            setBackgroundColor(cBg)

            // 顶部 AI 地址栏
            addView(buildTopBar())

            // 浏览器容器
            browserContainer = FrameLayout(this@BrowserActivity).apply {
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
            }
            addView(browserContainer)

            // Loading 遮罩
            loadingOverlay = LinearLayout(this@BrowserActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(cBg)
                layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                addView(ProgressBar(this@BrowserActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
                    indeterminateTintList = android.content.res.ColorStateList.valueOf(cPrimary)
                })
                addView(TextView(this@BrowserActivity).apply {
                    text = getString(R.string.browser_loading_text)
                    textSize = 13f
                    setTextColor(cMuted)
                    setPadding(0, dp(8), 0, 0)
                })
            }

            // 进度条
            progressBar = ProgressBar(this@BrowserActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(2))
                max = 100
                progress = 0
                visibility = View.GONE
                progressTintList = android.content.res.ColorStateList.valueOf(cPrimary)
                progressBackgroundTintList = android.content.res.ColorStateList.valueOf(cBorder)
            }
            addView(progressBar)

            // 底部导航栏
            bottomBar = buildBottomBar()
            addView(bottomBar)
        }
    }

    /** 极简顶栏：返回 + AI 地址栏 + 标签管理 */
    private fun buildTopBar(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            setBackgroundColor(cBg)
            setPadding(dp(4), dp(4), dp(4), dp(6))

            // 返回按钮
            addView(TextView(this@BrowserActivity).apply {
                text = "←"
                textSize = 20f
                gravity = Gravity.CENTER
                setTextColor(cMuted)
                val s = dp(40)
                layoutParams = LinearLayout.LayoutParams(s, s)
                background = rippleBorderless()
                setOnClickListener { finish() }
                contentDescription = getString(R.string.advanced_action_close)
            })

            // AI 地址栏（圆角药丸）
            val omnibox = LinearLayout(this@BrowserActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                    marginStart = dp(2)
                    marginEnd = dp(2)
                }
                background = roundedBg(cSurface, 21, cBorder, 1)
                setPadding(dp(4), 0, dp(4), 0)
            }

            // AI 标记
            aiBadge = TextView(this@BrowserActivity).apply {
                text = "✨"
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(cPrimary)
                val s = dp(32)
                layoutParams = LinearLayout.LayoutParams(s, s)
                background = rippleBorderless()
                setOnClickListener { toggleAiMode() }
                contentDescription = "AI mode"
            }
            omnibox.addView(aiBadge)

            // URL 输入
            etUrl = EditText(this@BrowserActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, MATCH_PARENT, 1f)
                hint = getString(R.string.browser_url_hint)
                setSingleLine(true)
                inputType = InputType.TYPE_TEXT_VARIATION_URI
                imeOptions = EditorInfo.IME_ACTION_GO
                textSize = 13f
                setTextColor(cText)
                setHintTextColor(cMuted)
                background = null
                setPadding(dp(2), 0, dp(2), 0)
                setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                        val t = text.toString().trim()
                        hideKeyboard()
                        if (isPageCommand(t)) {
                            Toast.makeText(this@BrowserActivity, getString(R.string.browser_ai_operate_toast), Toast.LENGTH_SHORT).show()
                            runAgentOnPage(t)
                        } else {
                            navigateTo(t)
                        }
                        true
                    } else false
                }
            }
            omnibox.addView(etUrl)

            // 刷新/停止按钮
            btnRefresh = ImageButton(this@BrowserActivity).apply {
                val s = dp(32)
                layoutParams = LinearLayout.LayoutParams(s, s)
                setImageResource(android.R.drawable.ic_menu_rotate)
                setColorFilter(cMuted)
                background = rippleBorderless()
                setPadding(dp(6), dp(6), dp(6), dp(6))
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                contentDescription = getString(R.string.browser_refresh_button)
                setOnClickListener { navigateTo(engine.currentUrl()) }
            }
            omnibox.addView(btnRefresh)

            addView(omnibox)

            // 标签页按钮
            addView(TextView(this@BrowserActivity).apply {
                text = "☰"
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(cMuted)
                val s = dp(40)
                layoutParams = LinearLayout.LayoutParams(s, s)
                background = rippleBorderless()
                setOnClickListener { showTabInfo() }
                contentDescription = "Tabs"
            })
        }
    }

    /** 底部导航：AI 操作 + 浏览导航 */
    private fun buildBottomBar(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            setBackgroundColor(cSurface)

            // 发丝线
            addView(View(this@BrowserActivity).apply {
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 1)
                setBackgroundColor(cBorder)
            })

            // 按钮行
            LinearLayout(this@BrowserActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(50))
                setPadding(dp(4), 0, dp(4), dp(2))

                // ✨ AI 按钮（突出）
                addView(makeNavButton("✨", getString(R.string.browser_ask_ai_button), accent = true) { showAiSheet() })
                // 后退
                addView(makeNavButton("‹", getString(R.string.browser_back_button)) { engine.evaluateJs("window.history.back()") })
                // 前进
                addView(makeNavButton("›", getString(R.string.browser_forward_button)) { engine.evaluateJs("window.history.forward()") })
                // 首页
                addView(makeNavButton("⌂", getString(R.string.browser_home_button)) { navigateTo(SearchEngines.byId(KVUtils.getSearchEngine()).home) })
                // 书签
                addView(makeNavButton("☆", getString(R.string.browser_bookmarks_button)) { showBookmarkDialog() })
                // 扩展
                addView(makeNavButton("⋮", getString(R.string.device_extensions)) { showExtensionDialog() })
            }.also { addView(it) }
        }
    }

    private fun makeNavButton(glyph: String, desc: String, accent: Boolean = false, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f)
            text = glyph
            textSize = if (accent) 18f else 20f
            gravity = Gravity.CENTER
            setTextColor(if (accent) cPrimary else cMuted)
            background = rippleBorderless()
            contentDescription = desc
            setOnClickListener { onClick() }
            // AI 按钮加圆形高亮背景
            if (accent) {
                val size = dp(36)
                val pill = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(withAlpha(cPrimary, 25))
                }
                // 不设背景，保持简洁
            }
        }
    }

    /** 切换 AI / URL 模式 */
    private fun toggleAiMode() {
        aiMode = !aiMode
        aiBadge.text = if (aiMode) "✨" else "🌐"
        etUrl.hint = if (aiMode) getString(R.string.browser_url_hint) else "输入网址..."
        etUrl.inputType = if (aiMode) InputType.TYPE_CLASS_TEXT else InputType.TYPE_TEXT_VARIATION_URI
    }

    /** 显示标签页信息 */
    private fun showTabInfo() {
        val url = engine.currentUrl()
        AlertDialog.Builder(this)
            .setTitle("当前标签页")
            .setMessage(url)
            .setPositiveButton("复制 URL") { _, _ ->
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("URL", url))
                Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.advanced_action_close), null)
            .show()
    }

    // ── AI 浏览器功能 ─────────────────────────────────

    private fun showAiSheet() {
        val pageText = com.apk.claw.android.service.ClawAccessibilityService.getInstance()
            ?.let { runCatching { it.screenTree }.getOrNull() }

        val pad = dp(16)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cBg)
            setPadding(pad, pad, pad, pad)
        }

        // 标题
        container.addView(TextView(this).apply {
            text = getString(R.string.browser_ai_sheet_title)
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(cText)
        })

        if (pageText.isNullOrBlank()) {
            container.addView(TextView(this).apply {
                text = getString(R.string.browser_accessibility_warning)
                textSize = 11f
                setTextColor(Color.parseColor("#FF9F0A"))
                setPadding(0, dp(6), 0, 0)
            })
        }

        // AI 回答区
        val answer = TextView(this).apply {
            textSize = 14f
            setTextColor(cText)
            setLineSpacing(0f, 1.2f)
            setPadding(0, dp(12), 0, 0)
        }
        val answerScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(220))
            addView(answer)
        }

        // 快捷操作芯片
        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, dp(8))
        }
        chip(chips, getString(R.string.browser_chip_summarize), cPrimary) { runAi(answer, "用简洁要点总结这个网页的主要内容。", pageText) }
        chip(chips, getString(R.string.browser_chip_key_points), cPrimary) { runAi(answer, "提取这个网页里最关键的信息要点。", pageText) }
        chip(chips, getString(R.string.browser_chip_translate), cPrimary) { runAi(answer, "把这个网页的主要内容翻译成中文。", pageText) }
        container.addView(chips)

        val chips2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(10))
        }
        chip(chips2, getString(R.string.browser_chip_reader), cText) { showReader(pageText) }
        chip(chips2, getString(R.string.browser_chip_speak), cText) { speakPage(pageText) }
        container.addView(chips2)

        // 输入行
        val etAsk = EditText(this).apply {
            hint = getString(R.string.browser_input_ask_or_operate)
            setSingleLine(true)
            textSize = 14f
            setTextColor(cText)
            setHintTextColor(cMuted)
            background = roundedBg(cSurface2, 12)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            imeOptions = EditorInfo.IME_ACTION_SEND
        }

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        inputRow.addView(etAsk, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        // 「问」按钮
        inputRow.addView(TextView(this).apply {
            text = getString(R.string.browser_ask_button)
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = roundedBg(cPrimary, 12)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(8) }
            setOnClickListener {
                val q = etAsk.text.toString().trim()
                if (q.isNotEmpty()) runAi(answer, q, pageText)
            }
        })

        // 「执行」按钮
        inputRow.addView(TextView(this).apply {
            text = getString(R.string.browser_execute_button)
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = roundedBg(Color.parseColor("#FF9F0A"), 12)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(8) }
            setOnClickListener {
                val q = etAsk.text.toString().trim()
                if (q.isNotEmpty()) {
                    (android.view.ViewGroup::class.java.getMethod("getParent").invoke(container) as? android.view.ViewGroup)?.let { parent ->
                        if (parent.parent is com.google.android.material.bottomsheet.BottomSheetDialog) {
                            (parent.parent as com.google.android.material.bottomsheet.BottomSheetDialog).dismiss()
                        }
                    }
                    runAgentOnPage(q)
                }
            }
        })

        container.addView(inputRow)
        container.addView(answerScroll)

        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        sheet.setContentView(container)
        sheet.show()
    }

    private fun chip(parent: LinearLayout, label: String, accent: Int, onClick: () -> Unit) {
        parent.addView(TextView(this).apply {
            text = label
            textSize = 12f
            setTextColor(accent)
            background = roundedBg(withAlpha(accent, 38), 14)
            setPadding(dp(12), dp(7), dp(12), dp(7))
            val lp = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
            lp.marginEnd = dp(8)
            layoutParams = lp
            setOnClickListener { onClick() }
        })
    }

    private fun extractReadableText(tree: String?): String {
        if (tree.isNullOrBlank()) return ""
        val chrome = setOf(
            "X", "浏览器", "GeckoView", "停止", "问", "执行", "✨", "刷新",
            "关闭", "切换搜索引擎", "问 AI", "后退", "前进", "首页", "书签", "扩展",
            "搜索或输入网址", "加载中", "加载中…", "搜索或输入",
        )
        val seen = LinkedHashSet<String>()
        Regex("(?:text|desc)=\"([^\"]+)\"").findAll(tree).forEach { m ->
            val s = m.groupValues[1].trim()
            if (s.length >= 2 && s !in chrome && !s.startsWith("http")) seen.add(s)
        }
        return seen.joinToString("\n")
    }

    private fun showReader(pageText: String?) {
        val readable = extractReadableText(pageText)
        if (readable.isBlank()) {
            Toast.makeText(this, getString(R.string.browser_cannot_read_content), Toast.LENGTH_SHORT).show()
            return
        }
        val tv = TextView(this).apply {
            text = readable
            textSize = 17f
            setTextColor(cText)
            setLineSpacing(0f, 1.35f)
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(cBg)
            addView(tv)
        }
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        sheet.setContentView(scroll)
        sheet.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        sheet.show()
    }

    private fun speakPage(pageText: String?) {
        val readable = extractReadableText(pageText).take(3000)
        if (readable.isBlank()) {
            Toast.makeText(this, getString(R.string.browser_no_content_to_speak), Toast.LENGTH_SHORT).show()
            return
        }
        val t = tts
        if (t != null && t.isSpeaking) {
            t.stop()
            return
        }
        if (tts == null) {
            tts = android.speech.tts.TextToSpeech(this) { status ->
                if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                    tts?.language = java.util.Locale.CHINESE
                    tts?.speak(readable, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "page")
                }
            }
        } else {
            tts?.language = java.util.Locale.CHINESE
            tts?.speak(readable, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "page")
        }
    }

    private fun isPageCommand(s: String): Boolean {
        if (s.startsWith("http") || (s.contains(".") && !s.contains(" "))) return false
        val t = s.lowercase()
        val markers = listOf(
            "帮我", "这页", "此页", "这个页面", "本页", "点一下", "点击", "填写", "填一下",
            "加入购物车", "加购", "下单", "结算", "登录这", "勾选", "提交表单",
            "click ", "fill ", "add to cart", "log in", "submit ", "check the ",
        )
        return markers.any { t.contains(it) }
    }

    private fun runAgentOnPage(task: String) {
        if (!com.apk.claw.android.ui.compose.screen.ChatAgentBridge.isConfigured()) {
            Toast.makeText(this, getString(R.string.browser_configure_api_key), Toast.LENGTH_SHORT).show()
            return
        }
        val prompt = "在当前网页上完成以下操作（用 get_screen_info 查看页面元素及坐标，用 tap / input_text 等工具操作）：$task"
        com.apk.claw.android.ui.compose.screen.ChatAgentBridge.run(
            prompt,
            onTool = { _, _, _, _ -> },
            onText = { },
            onDone = { },
            onError = { },
        )
    }

    private fun runAi(answer: TextView, question: String, pageText: String?) {
        if (!com.apk.claw.android.ui.compose.screen.ChatAgentBridge.isConfigured()) {
            answer.text = getString(R.string.browser_configure_api_key_text)
            return
        }
        answer.text = getString(R.string.browser_thinking_status)
        val ctx = if (pageText.isNullOrBlank()) "" else "\n\n【当前网页内容】\n" + pageText.take(4000)
        val prompt = "你是网页阅读助手。请只依据下方网页内容回答，不要调用任何工具。\n用户问题：$question$ctx"
        val sb = StringBuilder()
        com.apk.claw.android.ui.compose.screen.ChatAgentBridge.run(
            prompt,
            onTool = { _, _, _, _ -> },
            onText = { t -> sb.append(t); answer.text = sb.toString() },
            onDone = { d -> answer.text = if (sb.isNotEmpty()) sb.toString() else d },
            onError = { e -> answer.text = getString(R.string.browser_error_message, e) },
        )
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
                            etUrl.setText(SearchEngines.extractQuery(event.url) ?: event.url)
                        }
                        is EngineEvent.ProgressChanged -> {
                            progressBar.progress = event.percent
                        }
                        is EngineEvent.Error -> {
                            isLoading = false
                            progressBar.visibility = View.GONE
                            Toast.makeText(
                                this@BrowserActivity,
                                getString(R.string.browser_load_error, event.description),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        is EngineEvent.DownloadStart -> {
                            Toast.makeText(
                                this@BrowserActivity,
                                getString(R.string.browser_download_notification, event.suggestedFilename),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        is EngineEvent.ConsoleMessage -> {
                            Log.d(TAG, "[${event.level}] ${event.message}")
                            if (event.message.startsWith("正在安装扩展")) {
                                Toast.makeText(this@BrowserActivity, event.message, Toast.LENGTH_SHORT).show()
                            }
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
            SearchEngines.byId(KVUtils.getSearchEngine()).searchUrl(input)
        }
        etUrl.setText(SearchEngines.extractQuery(url) ?: url)
        engine.navigate(url)
    }

    // ── 书签 Dialog ──────────────────────────────────

    private fun showBookmarkDialog() {
        val bookmarks = bookmarkManager.getAll()
        val currentUrl = engine.currentUrl()
        val isBookmarked = bookmarkManager.isBookmarked(currentUrl)

        val items = mutableListOf<String>()
        if (isBookmarked) {
            items.add(getString(R.string.browser_remove_bookmark))
        } else {
            items.add(getString(R.string.browser_bookmark_current))
        }
        items.add(getString(R.string.browser_saved_bookmarks_header))
        bookmarks.forEach { items.add("${it.title}\n${it.url}") }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.browser_bookmarks_button))
            .setItems(items.toTypedArray()) { _, which ->
                when {
                    which == 0 -> {
                        if (isBookmarked) {
                            bookmarkManager.remove(currentUrl)
                            Toast.makeText(this, getString(R.string.browser_bookmark_removed), Toast.LENGTH_SHORT).show()
                        } else {
                            bookmarkManager.add(currentUrl, etUrl.text.toString().ifEmpty { currentUrl })
                            Toast.makeText(this, getString(R.string.browser_bookmarked), Toast.LENGTH_SHORT).show()
                        }
                    }
                    which > 1 -> {
                        val bookmark = bookmarks[which - 2]
                        navigateTo(bookmark.url)
                    }
                }
            }
            .setNegativeButton(getString(R.string.advanced_action_close), null)
            .show()
    }

    // ── 扩展 Dialog ──────────────────────────────────

    private fun showExtensionDialog() {
        if (!engine.supportsExtensions) {
            Toast.makeText(this, getString(R.string.browser_engine_no_extensions, engine.name), Toast.LENGTH_SHORT).show()
            return
        }

        val editText = EditText(this).apply {
            hint = getString(R.string.browser_extension_xpi_hint)
            setSingleLine(true)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.device_extensions))
            .setMessage(getString(R.string.browser_extension_dialog_message))
            .setView(editText)
            .setPositiveButton(getString(R.string.browser_install_button)) { _, _ ->
                val input = editText.text.toString().trim()
                if (input.isNotEmpty()) installExtension(input)
            }
            .setNeutralButton(getString(R.string.browser_browse_amo_button)) { _, _ ->
                navigateTo("https://addons.mozilla.org/zh-CN/android/")
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show()
    }

    private fun installExtension(input: String) {
        Toast.makeText(this, getString(R.string.browser_installing_extension), Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            try {
                val installer = com.apk.claw.android.octopus_mobile.browser.ExtensionInstaller(
                    engine as com.apk.claw.android.octopus_mobile.browser.GeckoViewEngine,
                    cacheDir
                )
                val result = if (input.length == 32 && !input.contains("/") && !input.contains(".")) {
                    installer.installFromChromeWebStore(input)
                } else {
                    installer.installFromUrl(input)
                }
                when (result) {
                    is com.apk.claw.android.octopus_mobile.browser.ExtensionInstaller.InstallResult.Success -> {
                        Toast.makeText(
                            this@BrowserActivity,
                            getString(R.string.browser_install_success, result.extensionName, result.extensionVersion),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    is com.apk.claw.android.octopus_mobile.browser.ExtensionInstaller.InstallResult.Failed -> {
                        Toast.makeText(
                            this@BrowserActivity,
                            getString(R.string.browser_install_failed, result.reason),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this@BrowserActivity,
                    getString(R.string.browser_install_error, e.message),
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

    private fun roundedBg(color: Int, radiusDp: Int, strokeColor: Int? = null, strokeDp: Int = 1) =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeColor != null) setStroke(dp(strokeDp).coerceAtLeast(1), strokeColor)
        }

    private fun rippleBorderless(): android.graphics.drawable.RippleDrawable {
        val mask = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(Color.WHITE)
        }
        return android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(withAlpha(cPrimary, 70)), null, mask
        )
    }

    private fun withAlpha(color: Int, alpha: Int) =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
}
