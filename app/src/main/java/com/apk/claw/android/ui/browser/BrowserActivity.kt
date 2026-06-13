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
    private lateinit var engineChip: android.widget.ImageView
    private var tts: android.speech.tts.TextToSpeech? = null

    // 深色 iOS 风配色（与 Compose 各页一致）
    private val cBg = Color.parseColor("#000000")
    private val cSurface = Color.parseColor("#1C1C1E")
    private val cSurface2 = Color.parseColor("#2C2C2E")
    private val cPrimary = Color.parseColor("#0A84FF")
    private val cText = Color.parseColor("#FFFFFF")
    private val cMuted = Color.parseColor("#8E8E93")
    private val cBorder = Color.parseColor("#38383A")
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
        // 深色状态栏，配合深色 chrome
        runCatching { window.statusBarColor = cBg }

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
            navigateTo(SearchEngines.byId(KVUtils.getSearchEngine()).home)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 清除引擎引用
        browserContainer.removeAllViews()
        // 清除 ToolRegistry 中的浏览器引擎，避免内存泄漏
        ToolRegistry.clearBrowserEngine()
        runCatching { tts?.stop(); tts?.shutdown() }
        tts = null
    }

    override fun onBackPressed() {
        // 浏览器内的后退由引擎处理，这里简单地 finish
        super.onBackPressed()
    }

    // ── 布局构建 ─────────────────────────────────────

    private fun buildLayout(): LinearLayout {
        val dp8 = dp(8)
        val dp12 = dp(12)

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            setBackgroundColor(cBg)

            // 顶栏（自定义深色：关闭 + 标题 + 引擎名）
            addView(buildTopBar())

            // 浏览器容器（占满中间）
            browserContainer = FrameLayout(this@BrowserActivity).apply {
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
            }
            addView(browserContainer)

            // Loading 遮罩（深色，防白屏）
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
                    setPadding(0, dp8, 0, 0)
                })
            }

            // 进度条（细，蓝色）
            progressBar = ProgressBar(this@BrowserActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(2))
                max = 100
                progress = 0
                visibility = View.GONE
                progressTintList = android.content.res.ColorStateList.valueOf(cPrimary)
                progressBackgroundTintList = android.content.res.ColorStateList.valueOf(cBorder)
            }

            // 底部地址栏（omnibox：圆角暗色药丸 + 引擎标记 + 刷新）
            val addressBar = LinearLayout(this@BrowserActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                setBackgroundColor(cBg)
                setPadding(dp12, dp8, dp12, dp8)
            }
            engineChip = android.widget.ImageView(this@BrowserActivity).apply {
                val s = dp(36)
                layoutParams = LinearLayout.LayoutParams(s, s).apply { marginEnd = dp8 }
                setPadding(dp8, dp8, dp8, dp8)
                background = roundedBg(cSurface2, 10)
                setOnClickListener { showEngineMenu(it) }
                contentDescription = getString(R.string.browser_engine_chip_description)
            }
            addressBar.addView(engineChip)
            loadEngineFavicon()

            etUrl = EditText(this@BrowserActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f)
                hint = getString(R.string.browser_url_hint)
                setSingleLine(true)
                inputType = InputType.TYPE_TEXT_VARIATION_URI
                imeOptions = EditorInfo.IME_ACTION_GO
                textSize = 14f
                setTextColor(cText)
                setHintTextColor(cMuted)
                background = roundedBg(cSurface2, 12)
                setPadding(dp12, 0, dp12, 0)
                setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                        val t = text.toString().trim()
                        hideKeyboard()
                        // 智能 omnibox：像指令 → 让 Agent 操作此页；否则按网址/搜索处理
                        if (isPageCommand(t)) {
                            android.widget.Toast.makeText(this@BrowserActivity, getString(R.string.browser_ai_operate_toast), android.widget.Toast.LENGTH_SHORT).show()
                            runAgentOnPage(t)
                        } else {
                            navigateTo(t)
                        }
                        true
                    } else false
                }
            }
            addressBar.addView(etUrl)

            btnRefresh = ImageButton(this@BrowserActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp8 }
                setImageResource(android.R.drawable.ic_menu_rotate)
                setColorFilter(cMuted)
                setBackgroundColor(Color.TRANSPARENT)
                contentDescription = getString(R.string.browser_refresh_button)
                setOnClickListener { navigateTo(engine.currentUrl()) }
            }
            addressBar.addView(btnRefresh)

            // 底部：进度条 + 地址栏（放页面下方，避免与网页顶部搜索框重复）+ 导航栏
            addView(progressBar)
            addView(addressBar)
            addView(buildBottomToolbar())
        }
    }

    /** 自定义深色顶栏 */
    private fun buildTopBar(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(52))
            setBackgroundColor(cBg)
            setPadding(dp(6), 0, dp(16), 0)
            addView(TextView(this@BrowserActivity).apply {
                text = "✕"
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(cText)
                val s = dp(44)
                layoutParams = LinearLayout.LayoutParams(s, s)
                isClickable = true
                setOnClickListener { finish() }
                contentDescription = getString(R.string.advanced_action_close)
            })
            addView(TextView(this@BrowserActivity).apply {
                text = getString(R.string.discover_shortcut_browser)
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(cText)
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            })
            addView(TextView(this@BrowserActivity).apply {
                text = engine.name
                textSize = 11f
                setTextColor(cMuted)
            })
        }
    }

    private fun buildBottomToolbar(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(52))
            setPadding(dp(8), 0, dp(8), 0)
            setBackgroundColor(cSurface)
            addView(makeGlyphButton("✨", getString(R.string.browser_ask_ai_button)) { showAiSheet() })
            addView(makeGlyphButton("‹", getString(R.string.browser_back_button)) { engine.evaluateJs("window.history.back()") })
            addView(makeGlyphButton("›", getString(R.string.browser_forward_button)) { engine.evaluateJs("window.history.forward()") })
            addView(makeGlyphButton("⌂", getString(R.string.browser_home_button)) { navigateTo(SearchEngines.byId(KVUtils.getSearchEngine()).home) })
            addView(makeGlyphButton("☆", getString(R.string.browser_bookmarks_button)) { showBookmarkDialog() })
            addView(makeGlyphButton("⋯", getString(R.string.device_extensions)) { showExtensionDialog() })
        }
    }

    private fun makeGlyphButton(glyph: String, desc: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f)
            text = glyph
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(cMuted)
            contentDescription = desc
            isClickable = true
            setOnClickListener { onClick() }
        }
    }

    /** 纯色圆角背景 */
    private fun roundedBg(color: Int, radiusDp: Int) =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun withAlpha(color: Int, alpha: Int) =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    /** 加载当前搜索引擎的官方图标(favicon)到引擎切换标记。 */
    private fun loadEngineFavicon() {
        if (!::engineChip.isInitialized) return
        val url = SearchEngines.byId(KVUtils.getSearchEngine()).favicon
        runCatching { com.bumptech.glide.Glide.with(this).load(url).into(engineChip) }
    }

    // ── 页内问 AI（AI 浏览器）─────────────────────────

    /**
     * 「问 AI · 关于此页」：抓取当前页面的无障碍文本快照（GeckoView v151 的 JS 求值已失效，
     * 改用无障碍树读正文），连同问题交给 LLM，流式回答在底部面板。
     */
    private fun showAiSheet() {
        // 在弹面板之前抓快照，确保页面完整可见
        val pageText = com.apk.claw.android.service.ClawAccessibilityService.getInstance()
            ?.let { runCatching { it.screenTree }.getOrNull() }

        val pad = dp(16)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cBg)
            setPadding(pad, pad, pad, pad)
        }
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

        val etAsk = EditText(this).apply {
            hint = getString(R.string.browser_ask_page_hint)
            setSingleLine(true)
            textSize = 14f
            setTextColor(cText)
            setHintTextColor(cMuted)
            background = roundedBg(cSurface2, 12)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            imeOptions = EditorInfo.IME_ACTION_SEND
        }

        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)

        fun chip(parent: LinearLayout, label: String, accent: Int, onClick: () -> Unit) {
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

        // 第一排：问答类
        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, dp(8))
        }
        chip(chips, getString(R.string.browser_chip_summarize), cPrimary) { runAi(answer, "用简洁要点总结这个网页的主要内容。", pageText) }
        chip(chips, getString(R.string.browser_chip_key_points), cPrimary) { runAi(answer, "提取这个网页里最关键的信息要点。", pageText) }
        chip(chips, getString(R.string.browser_chip_translate), cPrimary) { runAi(answer, "把这个网页的主要内容翻译成中文。", pageText) }
        container.addView(chips)

        // 第二排：内容/操作类
        val chips2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(10))
        }
        chip(chips2, getString(R.string.browser_chip_reader), cText) { showReader(pageText) }
        chip(chips2, getString(R.string.browser_chip_speak), cText) { speakPage(pageText) }
        container.addView(chips2)

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        etAsk.hint = getString(R.string.browser_input_ask_or_operate)
        inputRow.addView(etAsk, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        // 「问」：基于页面内容问答
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
        // 「执行」：让 Agent 真正操作当前网页（关面板让其看见页面）
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
                if (q.isNotEmpty()) { sheet.dismiss(); runAgentOnPage(q) }
            }
        })
        container.addView(inputRow)
        container.addView(answerScroll)

        sheet.setContentView(container)
        sheet.show()
    }

    /** 从无障碍树中抽取纯正文（去掉 bounds/clickable 标注与浏览器自身 UI），供阅读/朗读用。 */
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

    /** 阅读模式：把页面正文以干净的大字深色视图展示。 */
    private fun showReader(pageText: String?) {
        val readable = extractReadableText(pageText)
        if (readable.isBlank()) {
            android.widget.Toast.makeText(this, getString(R.string.browser_cannot_read_content), android.widget.Toast.LENGTH_SHORT).show()
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

    /** 朗读：用系统 TTS 朗读页面正文；再次点击停止。 */
    private fun speakPage(pageText: String?) {
        val readable = extractReadableText(pageText).take(3000)
        if (readable.isBlank()) {
            android.widget.Toast.makeText(this, getString(R.string.browser_no_content_to_speak), android.widget.Toast.LENGTH_SHORT).show()
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

    /** 保守判定：输入是否是一条「在本页操作」的指令（命中强祈使标记才算，避免误判普通搜索）。 */
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

    /** 让 Agent 在当前网页上执行操作（通过无障碍 tap/输入，不依赖已失效的 JS）。 */
    private fun runAgentOnPage(task: String) {
        if (!com.apk.claw.android.ui.compose.screen.ChatAgentBridge.isConfigured()) {
            android.widget.Toast.makeText(this, getString(R.string.browser_configure_api_key), android.widget.Toast.LENGTH_SHORT).show()
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
                            // omnibox：搜索结果页显示关键词，其余显示 URL
                            etUrl.setText(SearchEngines.extractQuery(event.url) ?: event.url)
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
            // 关键词搜索：使用用户选择的搜索引擎
            SearchEngines.byId(KVUtils.getSearchEngine()).searchUrl(input)
        }
        // omnibox：搜索结果页显示关键词，其余显示 URL
        etUrl.setText(SearchEngines.extractQuery(url) ?: url)
        engine.navigate(url)
    }

    /** 弹出搜索引擎切换菜单（omnibox 左侧标记） */
    private fun showEngineMenu(anchor: View) {
        val popup = android.widget.PopupMenu(this, anchor)
        SearchEngines.ALL.forEachIndexed { i, e -> popup.menu.add(0, i, i, e.label) }
        popup.setOnMenuItemClickListener { item ->
            val e = SearchEngines.ALL[item.itemId]
            KVUtils.setSearchEngine(e.id)
            loadEngineFavicon()
            true
        }
        popup.show()
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
                            // 用当前 URL 和 etUrl 的文字作为标题
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
                if (input.isNotEmpty()) {
                    installExtension(input)
                }
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
                    // 看起来像 Chrome Web Store ID
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
}
