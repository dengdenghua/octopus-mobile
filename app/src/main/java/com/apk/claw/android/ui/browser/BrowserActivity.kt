package com.apk.claw.android.ui.browser

import android.app.AlertDialog
import android.content.Intent
import androidx.activity.addCallback
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
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
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.apk.claw.android.R
import com.apk.claw.android.base.BaseActivity
import java.io.File
import com.apk.claw.android.octopus_mobile.browser.BrowserEngine
import com.apk.claw.android.octopus_mobile.browser.BrowserEngineFactory
import com.apk.claw.android.octopus_mobile.browser.EngineEvent
import com.apk.claw.android.octopus_mobile.browser.SearchEngines
import com.apk.claw.android.utils.KVUtils
import com.apk.claw.android.tool.ToolRegistry
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.toArgb
import com.apk.claw.android.ui.compose.theme.OctopusColors
import com.apk.claw.android.ui.compose.theme.OctopusThemeStyle

// ── 统一间距 token ──
private const val SPACING_XS = 4
private const val SPACING_SM = 8
private const val SPACING_MD = 12
private const val SPACING_LG = 16
private const val SPACING_XL = 20
private const val SPACING_XXL = 24

// ── 组件尺寸 token ──
private const val ICON_SIZE_SM = 36
private const val ICON_SIZE_MD = 44
private const val ICON_SIZE_LG = 52
private const val THUMB_SIZE = 80

// ── 圆角 token ──
private const val RADIUS_SM = 8
private const val RADIUS_MD = 12
private const val RADIUS_LG = 16
private const val RADIUS_XL = 20
private const val RADIUS_CAPSULE = 100

/**
 * AI 浏览器 —— 手机桌面风格首页 + 毛玻璃 + 壁纸 + 胶囊 + 视窗
 *
 * 设计语言：现代手机桌面
 * - 首页：分类图标网格 + 搜索栏 + Dock 栏 + 收藏标签
 * - 浏览页：可切换壁纸背景 + 毛玻璃顶栏/底栏 + 胶囊按钮 + 视窗容器
 * - AI 面板：毛玻璃 + 胶囊风格
 */
class BrowserActivity : BaseActivity() {

    companion object {
        private const val TAG = "BrowserActivity"
        const val EXTRA_URL = "extra_url"
        private const val PREFS_NAME = "browser_prefs"
        private const val KEY_WALLPAPER_INDEX = "wallpaper_index"
        private const val KEY_CUSTOM_WALLPAPER = "custom_wallpaper_path"
        private const val KEY_USE_CUSTOM_WALLPAPER = "use_custom_wallpaper"
        private const val CUSTOM_WALLPAPER_FILE = "browser_wallpaper.png"
        private const val REQUEST_CODE_PICK_IMAGE = 10001
        private const val KEY_HOME_VISIBLE = "home_visible"
    }

    // ── 壁纸渐变色组 ──
    private val wallpapers = listOf(
        intArrayOf(0xFF0F0C29.toInt(), 0xFF302B63.toInt(), 0xFF24243E.toInt()),
        intArrayOf(0xFF0D1B2A.toInt(), 0xFF1B2838.toInt(), 0xFF0D1B2A.toInt()),
        intArrayOf(0xFF1A002E.toInt(), 0xFF3D0066.toInt(), 0xFF1A002E.toInt()),
        intArrayOf(0xFF002B36.toInt(), 0xFF004D40.toInt(), 0xFF002B36.toInt()),
        intArrayOf(0xFF2D1B00.toInt(), 0xFF5C3D00.toInt(), 0xFF2D1B00.toInt()),
        intArrayOf(0xFF1A1A2E.toInt(), 0xFF16213E.toInt(), 0xFF0F3460.toInt()),
    )
    private val wallpaperDrawables by lazy {
        // 第 0 张「跟随主题」:用 App 主题色(深色 + 品牌主色微光)调出的渐变,与全局风格一致;其余为预设
        val themeColors = intArrayOf(
            OctopusColors.SurfaceDeep.toArgb(),
            blendColors(OctopusColors.Background.toArgb(), OctopusColors.Primary.toArgb(), 0.30f),
            OctopusColors.Background.toArgb(),
        )
        (listOf(themeColors) + wallpapers).map { colors ->
            GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors)
        }
    }

    /** 按比例混合两色(ratio=b 的占比),用于壁纸主题渐变中间色。 */
    private fun blendColors(a: Int, b: Int, ratio: Float): Int {
        val ir = 1f - ratio
        return Color.argb(
            255,
            (Color.red(a) * ir + Color.red(b) * ratio).toInt(),
            (Color.green(a) * ir + Color.green(b) * ratio).toInt(),
            (Color.blue(a) * ir + Color.blue(b) * ratio).toInt(),
        )
    }
    private var wallpaperIndex = 0
    private var useCustomWallpaper = false

    private val cPrimary get() = OctopusColors.Primary.toArgb()
    private val cText get() = OctopusColors.TextPrimary.toArgb()
    private val cMuted get() = OctopusColors.TextMuted.toArgb()
    private val cOnPrimary get() = OctopusColors.OnPrimary.toArgb()
    private val cSurface get() = OctopusColors.Surface.toArgb()
    private val cError get() = OctopusColors.Error.toArgb()
    private val cWarning get() = OctopusColors.Warning.toArgb()

    // 壁纸上的浅色文字（壁纸始终深色，故文字始终浅色，不随主题变）
    private val cOnWallpaper: Int get() = withAlpha(cOnPrimary, 235)
    private val cOnWallpaperMuted: Int get() = withAlpha(cOnPrimary, 170)
    private val cOnWallpaperFaint: Int get() = withAlpha(cOnPrimary, 190)

    // 毛玻璃颜色 —— 基于 OctopusColors.Surface 派生，自动适配亮/暗模式
    // 亮色模式：浅色毛玻璃（透出深色壁纸）；暗色模式：深色毛玻璃
    private val glassTop: Int get() = surfaceGlass(0.72f)
    private val glassBottom: Int get() = surfaceGlass(0.78f)
    private val glassCard: Int get() = surfaceGlass(0.55f)
    private val glassOverlay: Int get() = surfaceGlass(0.88f)
    private val glassHomeCard: Int get() = homeGlass()

    /** 基于 Surface 派生玻璃色（亮色=浅毛玻璃，暗色=深毛玻璃） */
    private fun surfaceGlass(alpha: Float): Int {
        val c = OctopusColors.Surface.toArgb()
        return Color.argb((alpha * 255).toInt(), Color.red(c), Color.green(c), Color.blue(c))
    }

    /** 首页卡片玻璃：亮色半透明白，暗色半透明白（在深色壁纸上形成毛玻璃） */
    private fun homeGlass(): Int = withAlpha(cOnPrimary, if (OctopusColors.isLight) 120 else 70)

    /** 玻璃描边色（亮色=深色描边，暗色=浅色描边，模拟玻璃边缘） */
    private val glassStroke: Int get() = if (OctopusColors.isLight) withAlpha(cText, 30) else withAlpha(cOnWallpaper, 35)

    /** 当前是否处于玻璃特效主题；Standard 模式下使用实色/扁平风格 */
    private val isGlassStyle get() = OctopusThemeStyle.isGlass

    /** Standard 模式下浏览器 Activity 的实色背景（跟随主题） */
    private val solidPageBg: Int get() = OctopusColors.Background.toArgb()

    /** Standard 模式下卡片/顶栏背景 */
    private val solidSurface: Int get() = OctopusColors.Surface.toArgb()

    /** Standard 模式下次级卡片背景 */
    private val solidSurfaceVariant: Int get() = OctopusColors.SurfaceVariant.toArgb()

    /** Standard 模式下细描边 */
    private val solidStroke: Int get() = if (OctopusColors.isLight) withAlpha(cText, 25) else withAlpha(cText, 40)

    /** Standard 模式下胶囊按钮背景 */
    private val solidControlBg: Int get() = if (OctopusColors.isLight) withAlpha(cPrimary, 20) else withAlpha(cText, 25)

    /** 顶栏/底栏/容器的背景：Glass 用毛玻璃，Standard 用实色 Surface */
    private fun panelBg(radiusDp: Int): GradientDrawable {
        return if (isGlassStyle) glassBg(radiusDp, 0.72f, stroke = true) else solidRoundRect(solidSurface, radiusDp, solidStroke, 1)
    }

    /** 面板背景（无描边）：Glass 用无描边毛玻璃，Standard 用实色 */
    private fun panelBgNoStroke(radiusDp: Int): GradientDrawable {
        return if (isGlassStyle) glassBg(radiusDp, 0.72f, stroke = false) else solidRoundRect(solidSurface, radiusDp, Color.TRANSPARENT, 0)
    }

    /** 浏览器视窗容器背景：Glass 用毛玻璃，Standard 用 Surface 实色 */
    private fun viewportBg(): GradientDrawable {
        return if (isGlassStyle) glassBg(RADIUS_XL, 0.78f) else solidRoundRect(solidSurface, RADIUS_XL, solidStroke, 1)
    }

    /** 实色圆角矩形（Standard 模式用） */
    private fun solidRoundRect(color: Int, radiusDp: Int, strokeColor: Int, strokeWidthDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = dp(radiusDp).toFloat()
            setColor(color)
            if (strokeWidthDp > 0) setStroke(dp(strokeWidthDp), strokeColor)
        }
    }

    /** 胶囊按钮背景：Glass 用半透明玻璃，Standard 用实色 */
    private fun capsuleBgAdaptive(fillColor: Int, radiusDp: Int, strokeColor: Int? = null, strokeWidthDp: Int = 0): GradientDrawable {
        return if (isGlassStyle) {
            capsuleBg(fillColor, radiusDp, strokeColor, strokeWidthDp)
        } else {
            solidRoundRect(fillColor, radiusDp, strokeColor ?: Color.TRANSPARENT, if (strokeColor == null) 0 else strokeWidthDp)
        }
    }

    /** 应用真实模糊（API31+ RenderEffect），低版本无操作（由半透明玻璃色兜底） */
    private fun applyBlur(view: View, radiusDp: Int = 24) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                view.setRenderEffect(
                    android.graphics.RenderEffect.createBlurEffect(
                        radiusDp.toFloat(), radiusDp.toFloat(),
                        android.graphics.Shader.TileMode.CLAMP
                    )
                )
            }
        }
    }

    /** 给 BottomSheetDialog 的 window 加真实背景模糊（API31+），低版本由 glassOverlay 兜底。
     *  Standard 主题跳过模糊,保持扁平纯色底。 */
    private fun applyDialogBlur(sheet: com.google.android.material.bottomsheet.BottomSheetDialog) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && isGlassStyle) {
            runCatching { sheet.window?.setBackgroundBlurRadius(40) }
        }
    }

    /** 玻璃背景：渐变 + 描边 + 圆角，模拟毛玻璃质感 */
    private fun glassBg(radiusDp: Int, alpha: Float = 0.7f, stroke: Boolean = true): GradientDrawable {
        return GradientDrawable().apply {
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
            val top = surfaceGlass(alpha + 0.05f)
            val bottom = surfaceGlass(alpha)
            colors = intArrayOf(top, bottom)
            cornerRadius = dp(radiusDp).toFloat()
            if (stroke) setStroke(dp(1).coerceAtLeast(1), glassStroke)
        }
    }

    private lateinit var engine: BrowserEngine
    private lateinit var etUrl: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var browserContainer: FrameLayout
    private lateinit var btnRefresh: ImageButton
    private lateinit var loadingOverlay: LinearLayout
    private lateinit var aiBadge: TextView
    private lateinit var bottomBar: LinearLayout
    private lateinit var topBar: LinearLayout
    private lateinit var wallpaperBg: ImageView

    // 首页相关
    private lateinit var homeLayer: FrameLayout
    private lateinit var contentLayer: LinearLayout
    private var isHomeVisible = true
    private var tts: android.speech.tts.TextToSpeech? = null
    private var isLoading = false
    private var aiMode = true

    // Bitmap 缓存：避免重复解码自定义壁纸
    private var cachedCustomBitmap: Bitmap? = null
    private var cachedCustomFileTime: Long = 0

    // ── 轻量多窗口(Activity 层维护;切换时按 URL 重载,单引擎)──
    private data class BrowserWindow(var title: String, var url: String, val id: Long)
    private val windows = mutableListOf<BrowserWindow>()
    private var currentWindowId: Long = -1L
    private var windowSeq: Long = 0L
    private var windowCountView: TextView? = null

    // 图片选择器
    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { saveCustomWallpaper(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        wallpaperIndex = prefs.getInt(KEY_WALLPAPER_INDEX, 0)
        useCustomWallpaper = prefs.getBoolean(KEY_USE_CUSTOM_WALLPAPER, false)
        isHomeVisible = prefs.getBoolean(KEY_HOME_VISIBLE, true)

        // 注入式插件 + 拦截规则由 PluginManager 在 App 启动时统一加载进 BrowserPluginHost
        // (assets 签名源,fail-closed)。stealth 反检测脚本内置,WebView 创建即带上。
        engine = BrowserEngineFactory.selectBest(this)
        Log.i(TAG, "Browser engine: ${engine.name}")

        val root = buildLayout()
        setContentView(root)
        runCatching { window.statusBarColor = Color.TRANSPARENT }
        runCatching { window.navigationBarColor = Color.TRANSPARENT }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && isGlassStyle) {
            runCatching { window.setBackgroundBlurRadius(80) }
        }

        ToolRegistry.setBrowserEngine(engine)

        val browserView = engine.createView(this)
        browserContainer.addView(browserView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        browserContainer.addView(loadingOverlay)

        observeEngineEvents()

        ensureWindow()  // 至少有一个窗口,供右侧窗口数显示

        val url = intent.getStringExtra(EXTRA_URL)
        if (!url.isNullOrEmpty()) {
            navigateTo(url)
        } else {
            // 默认显示首页
            showHome()
        }

        // 系统返回手势 → 网页历史后退（取代底栏已移除的 ‹ 键）：
        // 首页时退出浏览器；网页有历史则后退一步；无历史则回到首页。
        onBackPressedDispatcher.addCallback(this) { handleWebBack() }
    }

    private fun handleWebBack() {
        if (isHomeVisible) {
            finish()
            return
        }
        engine.evaluateJs("window.history.length") { len ->
            val n = len?.trim()?.trim('"')?.toIntOrNull() ?: 1
            runOnUiThread {
                if (n > 1) engine.evaluateJs("window.history.back()") else showHome()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // 后台时暂停 WebView 的 JS 定时器 / 网络 / 音频 / GPU 合成,避免持续占电。
        engine.onPause()
    }

    override fun onResume() {
        super.onResume()
        engine.onResume()
    }

    override fun onDestroy() {
        super.onDestroy()
        engine.destroy()
        browserContainer.removeAllViews()
        ToolRegistry.clearBrowserEngine()
        runCatching { tts?.stop(); tts?.shutdown() }
        tts = null
    }

    // ── 布局构建 ─────────────────────────────────────

    private fun buildLayout(): FrameLayout {
        return FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)

            // 1) 壁纸层
            wallpaperBg = ImageView(this@BrowserActivity).apply {
                layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            addView(wallpaperBg)
            applyWallpaper()  // 必须在 wallpaperBg 赋值后调用(它内部引用该 lateinit 字段)

            // 2) 内容层
            contentLayer = LinearLayout(this@BrowserActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            }

            // 顶栏
            topBar = buildTopBar()
            contentLayer.addView(topBar)

            // 浏览器视窗
            browserContainer = FrameLayout(this@BrowserActivity).apply {
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply {
                    marginStart = dp(SPACING_SM)
                    marginEnd = dp(SPACING_SM)
                    topMargin = dp(SPACING_XS)
                    bottomMargin = dp(SPACING_XS)
                }
                outlineProvider = android.view.ViewOutlineProvider.BOUNDS
                clipToOutline = false
                background = viewportBg()
                elevation = if (isGlassStyle) dp(SPACING_XS).toFloat() else 0f
            }
            contentLayer.addView(browserContainer)

            // Loading 遮罩
            loadingOverlay = LinearLayout(this@BrowserActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(if (isGlassStyle) glassOverlay else solidSurface)
                layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                addView(ProgressBar(this@BrowserActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
                    indeterminateTintList = android.content.res.ColorStateList.valueOf(cPrimary)
                })
                addView(TextView(this@BrowserActivity).apply {
                    text = getString(R.string.browser_loading_text)
                    textSize = 13f
                    setTextColor(cMuted)
                    setPadding(0, dp(SPACING_SM), 0, 0)
                })
            }

            // 进度条
            progressBar = ProgressBar(this@BrowserActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(3))
                max = 100
                progress = 0
                visibility = View.GONE
                progressTintList = android.content.res.ColorStateList.valueOf(cPrimary)
                progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)
            }
            contentLayer.addView(progressBar)

            addView(contentLayer)

            // 悬浮长胶囊底栏(覆盖在网页之上;首页态隐藏)
            bottomBar = buildBottomCapsule()
            addView(bottomBar)

            // 3) 首页层（覆盖在浏览器之上）
            homeLayer = buildHomeLayer()
            addView(homeLayer)
        }
    }

    // ── 首页 ─────────────────────────────────────────

    private fun buildHomeLayer(): FrameLayout {
        return FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            visibility = if (isHomeVisible) View.VISIBLE else View.GONE

            // 统一首页：直接复用底部导航的「浏览器桌面」(DiscoverScreen)，不再维护第二套老首页。
            // 点击分类/收藏/搜索 → onOpenUrl 在「当前 WebView」内导航(navigateTo)，不嵌套再起一个浏览器。
            addView(androidx.compose.ui.platform.ComposeView(this@BrowserActivity).apply {
                layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                setContent {
                    com.apk.claw.android.ui.compose.theme.OctopusTheme {
                        com.apk.claw.android.ui.compose.screen.DiscoverScreen(
                            onOpenUrl = { url -> url?.takeIf { it.isNotBlank() }?.let { navigateTo(it) } },
                        )
                    }
                }
            })

            // 右上角关闭(回收旧首页的 ×)；首页态系统返回手势也会退出浏览器。
            addView(TextView(this@BrowserActivity).apply {
                text = "×"
                textSize = 20f
                gravity = Gravity.CENTER
                setTextColor(cText)
                val s = dp(ICON_SIZE_SM)
                layoutParams = FrameLayout.LayoutParams(s, s, Gravity.TOP or Gravity.END).apply {
                    topMargin = dp(40)
                    marginEnd = dp(SPACING_MD)
                }
                background = capsuleBgAdaptive(solidSurface, RADIUS_LG)
                setOnClickListener { finish() }
                contentDescription = getString(R.string.advanced_action_close)
            })
        }
    }

    /** 品牌头部区域 */
    // ── 首页/浏览页切换 ──

    private fun showHome() {
        isHomeVisible = true
        homeLayer.visibility = View.VISIBLE
        browserContainer.visibility = View.GONE
        progressBar.visibility = View.GONE
        // 首页有自己的搜索框,网页顶栏/底栏在首页多余;且 browserContainer GONE 后底栏会顶到上方,故一并隐藏
        topBar.visibility = View.GONE
        bottomBar.visibility = View.GONE
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit().putBoolean(KEY_HOME_VISIBLE, true).apply()
    }

    private fun hideHome() {
        isHomeVisible = false
        homeLayer.visibility = View.GONE
        browserContainer.visibility = View.VISIBLE
        topBar.visibility = View.VISIBLE
        bottomBar.visibility = View.VISIBLE
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit().putBoolean(KEY_HOME_VISIBLE, false).apply()
    }

    // ── 分类数据 ──

    // ── 毛玻璃顶栏 ──

    private fun buildTopBar(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            background = panelBgNoStroke(0)  // 顶栏无圆角，全宽
            setPadding(dp(6), dp(SPACING_SM), dp(6), dp(SPACING_SM))

            // 返回胶囊按钮
            addView(TextView(this@BrowserActivity).apply {
                text = "←"
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(if (isGlassStyle) cMuted else cText)
                val s = dp(ICON_SIZE_SM)
                layoutParams = LinearLayout.LayoutParams(s, s)
                background = capsuleBgAdaptive(solidControlBg, RADIUS_LG)
                setOnClickListener { finish() }
                contentDescription = getString(R.string.advanced_action_close)
            })

            // AI 胶囊地址栏
            val omnibox = LinearLayout(this@BrowserActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                    marginStart = dp(6)
                    marginEnd = dp(6)
                }
                background = if (isGlassStyle) {
                    capsuleBg(glassCard, RADIUS_XL, withAlpha(cPrimary, 50), 1)
                } else {
                    solidRoundRect(solidSurfaceVariant, RADIUS_XL, solidStroke, 1)
                }
                setPadding(dp(SPACING_SM), 0, dp(SPACING_XS), 0)
            }

            aiBadge = TextView(this@BrowserActivity).apply {
                text = "AI"
                textSize = 10f
                setTypeface(Typeface.DEFAULT_BOLD)
                gravity = Gravity.CENTER
                setTextColor(cPrimary)
                val s = dp(28)
                layoutParams = LinearLayout.LayoutParams(s, s)
                setOnClickListener { toggleAiMode() }
                contentDescription = "AI mode"
            }
            omnibox.addView(aiBadge)

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

            btnRefresh = ImageButton(this@BrowserActivity).apply {
                val s = dp(28)
                layoutParams = LinearLayout.LayoutParams(s, s)
                setImageResource(android.R.drawable.ic_menu_rotate)
                setColorFilter(if (isGlassStyle) cMuted else cText)
                background = capsuleBgAdaptive(Color.TRANSPARENT, RADIUS_MD)
                setPadding(dp(SPACING_XS), dp(SPACING_XS), dp(SPACING_XS), dp(SPACING_XS))
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                contentDescription = getString(R.string.browser_refresh_button)
                setOnClickListener { navigateTo(engine.currentUrl()) }
            }
            omnibox.addView(btnRefresh)

            addView(omnibox)

            // 首页胶囊
            addView(TextView(this@BrowserActivity).apply {
                text = "⌂"
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(if (isGlassStyle) cMuted else cText)
                val s = dp(ICON_SIZE_SM)
                layoutParams = LinearLayout.LayoutParams(s, s)
                background = capsuleBgAdaptive(solidControlBg, RADIUS_LG)
                setOnClickListener {
                    if (isHomeVisible) hideHome() else showHome()
                }
                contentDescription = getString(R.string.browser_home_button)
            })

            // 壁纸切换胶囊
            addView(ImageView(this@BrowserActivity).apply {
                val s = dp(ICON_SIZE_SM)
                layoutParams = LinearLayout.LayoutParams(s, s).apply { marginStart = dp(2) }
                setImageResource(android.R.drawable.ic_menu_gallery)
                setColorFilter(if (isGlassStyle) cMuted else cText)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(dp(SPACING_XS), dp(SPACING_XS), dp(SPACING_XS), dp(SPACING_XS))
                background = capsuleBgAdaptive(solidControlBg, RADIUS_LG)
                setOnClickListener { showWallpaperPicker() }
                contentDescription = "Wallpaper"
            })
        }
    }

    // ── 毛玻璃底栏 ──

    /** 悬浮长胶囊底栏(浏览网页时):左 设置 · 中 后退/前进/AI · 右 窗口数 */
    private fun buildBottomCapsule(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = panelBg(RADIUS_XL)
            elevation = if (isGlassStyle) dp(10).toFloat() else 0f
            setPadding(dp(6), dp(6), dp(6), dp(6))
            // 悬浮固定在底部（导航栏上方）
            layoutParams = FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dp(SPACING_LG)
                marginStart = dp(SPACING_LG)
                marginEnd = dp(SPACING_LG)
            }

            // 左:三横 → 上滑出浏览器设置
            addView(makeCapsuleIcon("≡", getString(R.string.browser_settings_title)) { showBrowserSettingsSheet() })
            // 中:AI(后退/前进改用系统返回手势,不再占用底栏胶囊)
            addView(makeCapsuleIcon("AI", getString(R.string.browser_ask_ai_button), accent = true) { showAiSheet() })
            // 右:窗口数 → 弹出所有窗口
            windowCountView = TextView(this@BrowserActivity).apply {
                text = windows.size.coerceAtLeast(1).toString()
                textSize = 13f
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (isGlassStyle) cText else cPrimary)
                val s = dp(32)
                layoutParams = LinearLayout.LayoutParams(s, s).apply { marginStart = dp(3); marginEnd = dp(1) }
                background = if (isGlassStyle) {
                    capsuleBg(withAlpha(cPrimary, 70), RADIUS_SM, withAlpha(cPrimary, 160), 1)
                } else {
                    solidRoundRect(solidSurfaceVariant, RADIUS_SM, solidStroke, 1)
                }
                contentDescription = getString(R.string.browser_windows)
                setOnClickListener { showWindowsSheet() }
            }
            addView(windowCountView)
        }
    }

    private fun makeCapsuleIcon(glyph: String, desc: String, accent: Boolean = false, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            val s = dp(40)  // 触摸目标 ≥48dp 受限于胶囊紧凑度，40dp + padding 满足
            layoutParams = LinearLayout.LayoutParams(s, s).apply { marginStart = dp(1); marginEnd = dp(1) }
            text = glyph
            textSize = if (accent) 12f else 17f
            gravity = Gravity.CENTER
            setTextColor(if (accent) cOnPrimary else cText)
            background = if (accent) {
                capsuleBgAdaptive(cPrimary, RADIUS_XL)
            } else {
                capsuleBgAdaptive(if (isGlassStyle) withAlpha(cText, 25) else solidControlBg, RADIUS_XL)
            }
            contentDescription = desc
            setOnClickListener { onClick() }
        }
    }

    /** 上滑出的浏览器设置面板 */
    private fun showBrowserSettingsSheet() {
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(if (isGlassStyle) glassOverlay else solidSurface)
            setPadding(dp(SPACING_LG), dp(SPACING_LG), dp(SPACING_LG), dp(SPACING_XXL))
        }
        container.addView(TextView(this).apply {
            text = getString(R.string.browser_settings_title)
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(cText)
            setPadding(dp(SPACING_XS), 0, 0, dp(SPACING_SM))
        })
        fun row(title: String, onClick: () -> Unit) {
            container.addView(TextView(this).apply {
                text = title
                textSize = 15f
                setTextColor(cText)
                setPadding(dp(SPACING_SM), dp(SPACING_MD + 2), dp(SPACING_SM), dp(SPACING_MD + 2))
                isClickable = true
                background = capsuleBgAdaptive(if (isGlassStyle) withAlpha(cSurface, 20) else solidSurfaceVariant, RADIUS_LG)
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(SPACING_SM) }
                setOnClickListener { sheet.dismiss(); onClick() }
            })
        }
        row(getString(R.string.browser_refresh_page)) { navigateTo(engine.currentUrl()) }
        row(getString(R.string.browser_engine_settings)) {
            runCatching { startActivity(Intent(this, com.apk.claw.android.ui.featurescreens.BrowserSettingsActivity::class.java)) }
        }
        row(getString(R.string.browser_change_wallpaper)) { showWallpaperPicker() }
        row(getString(R.string.browser_bookmarks_button)) { showBookmarkDialog() }
        row(getString(R.string.browser_copy_link)) {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("url", engine.currentUrl()))
            Toast.makeText(this, getString(R.string.browser_link_copied), Toast.LENGTH_SHORT).show()
        }
        row(getString(R.string.browser_open_in_system)) {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(engine.currentUrl()))) }
        }
        sheet.setContentView(container)
        sheet.show()
        applyDialogBlur(sheet)
    }

    // ── 多窗口 ──

    private fun ensureWindow() {
        if (windows.isEmpty()) {
            val w = BrowserWindow(getString(R.string.browser_new_tab), "", windowSeq++)
            windows.add(w)
            currentWindowId = w.id
        }
        updateWindowCount()
    }

    private fun currentWindow(): BrowserWindow? = windows.firstOrNull { it.id == currentWindowId }

    private fun updateWindowCount() {
        windowCountView?.text = windows.size.coerceAtLeast(1).toString()
    }

    private fun newWindow() {
        val w = BrowserWindow(getString(R.string.browser_new_tab), "", windowSeq++)
        windows.add(w)
        currentWindowId = w.id
        updateWindowCount()
        showHome()
    }

    private fun switchWindow(w: BrowserWindow) {
        currentWindowId = w.id
        if (w.url.isBlank()) showHome() else navigateTo(w.url)
    }

    private fun closeWindow(w: BrowserWindow) {
        windows.remove(w)
        if (windows.isEmpty()) { newWindow(); return }
        if (w.id == currentWindowId) switchWindow(windows.last())
        updateWindowCount()
    }

    /** 右侧窗口数 → 弹出所有窗口列表 */
    private fun showWindowsSheet() {
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(if (isGlassStyle) glassOverlay else solidSurface)
            setPadding(dp(SPACING_LG), dp(SPACING_LG), dp(SPACING_LG), dp(SPACING_XXL))
        }
        // 标题 + 新窗口
        container.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@BrowserActivity).apply {
                text = getString(R.string.browser_windows_count, windows.size)
                textSize = 17f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(cText)
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            })
            addView(TextView(this@BrowserActivity).apply {
                text = getString(R.string.browser_new_window)
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(cOnPrimary)
                setPadding(dp(SPACING_MD), dp(SPACING_XS + 3), dp(SPACING_MD), dp(SPACING_XS + 3))
                background = capsuleBg(cPrimary, RADIUS_LG)
                setOnClickListener { sheet.dismiss(); newWindow() }
            })
        })
        // 列表
        windows.forEach { w ->
            container.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(SPACING_MD), dp(SPACING_MD), dp(SPACING_SM), dp(SPACING_MD))
                val active = w.id == currentWindowId
                background = if (isGlassStyle) {
                    capsuleBg(
                        if (active) withAlpha(cPrimary, 45) else withAlpha(cSurface, 12), RADIUS_LG,
                        if (active) cPrimary else Color.TRANSPARENT, 1,
                    )
                } else {
                    solidRoundRect(
                        if (active) withAlpha(cPrimary, 25) else solidSurfaceVariant, RADIUS_LG,
                        if (active) cPrimary else solidStroke, 1,
                    )
                }
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(SPACING_SM) }
                elevation = if (isGlassStyle) dp(2).toFloat() else 0f
                isClickable = true
                setOnClickListener { sheet.dismiss(); switchWindow(w) }

                addView(LinearLayout(this@BrowserActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                    addView(TextView(this@BrowserActivity).apply {
                        text = w.title.ifBlank { getString(R.string.browser_new_tab) }
                        textSize = 14f
                        setTextColor(cText)
                        setSingleLine(true)
                    })
                    addView(TextView(this@BrowserActivity).apply {
                        text = w.url.ifBlank { "—" }
                        textSize = 11f
                        setTextColor(if (isGlassStyle) cOnWallpaperMuted else cMuted)
                        setSingleLine(true)
                    })
                })
                addView(TextView(this@BrowserActivity).apply {
                    text = "×"
                    textSize = 18f
                    gravity = Gravity.CENTER
                    setTextColor(if (isGlassStyle) cOnWallpaperMuted else cMuted)
                    val s = dp(32)
                    layoutParams = LinearLayout.LayoutParams(s, s)
                    setOnClickListener { closeWindow(w); sheet.dismiss(); showWindowsSheet() }
                })
            })
        }
        sheet.setContentView(android.widget.ScrollView(this).apply { addView(container) })
        sheet.show()
        applyDialogBlur(sheet)
    }

    // ── 壁纸 ──

    private fun applyWallpaper() {
        if (!isGlassStyle) {
            wallpaperBg.setImageDrawable(null)
            wallpaperBg.setBackgroundColor(solidPageBg)
            return
        }
        if (useCustomWallpaper) {
            val file = File(filesDir, CUSTOM_WALLPAPER_FILE)
            if (file.exists()) {
                // 缓存命中检查：文件修改时间未变则复用 Bitmap
                if (cachedCustomBitmap != null && cachedCustomFileTime == file.lastModified()) {
                    wallpaperBg.setImageBitmap(cachedCustomBitmap)
                    return
                }
                // 释放旧缓存
                cachedCustomBitmap?.recycle()
                cachedCustomBitmap = null

                val opts = BitmapFactory.Options().apply { inSampleSize = 1 }
                val bmp = BitmapFactory.decodeFile(file.absolutePath, opts)
                if (bmp != null) {
                    cachedCustomBitmap = bmp
                    cachedCustomFileTime = file.lastModified()
                    wallpaperBg.setImageBitmap(bmp)
                    return
                }
            }
            // 自定义壁纸文件丢失，回退到预设
            useCustomWallpaper = false
            cachedCustomBitmap?.recycle()
            cachedCustomBitmap = null
        }
        wallpaperBg.setImageDrawable(wallpaperDrawable())
    }

    private fun wallpaperDrawable(): GradientDrawable = wallpaperDrawables[wallpaperIndex % wallpaperDrawables.size]

    private fun selectPresetWallpaper(index: Int) {
        useCustomWallpaper = false
        wallpaperIndex = index
        wallpaperBg.setImageDrawable(wallpaperDrawable())
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().apply {
            putInt(KEY_WALLPAPER_INDEX, wallpaperIndex)
            putBoolean(KEY_USE_CUSTOM_WALLPAPER, false)
            apply()
        }
    }

    private fun saveCustomWallpaper(uri: Uri) {
        lifecycleScope.launch {
            runCatching {
                contentResolver.openInputStream(uri)?.use { input ->
                    val bmp = BitmapFactory.decodeStream(input) ?: return@use
                    // 缩放到合理尺寸避免 OOM
                    val maxDim = 1920
                    val scale = if (bmp.width > maxDim || bmp.height > maxDim) {
                        maxDim.toFloat() / maxOf(bmp.width, bmp.height)
                    } else 1f
                    val scaled = if (scale < 1f) {
                        Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
                    } else bmp

                    val file = File(filesDir, CUSTOM_WALLPAPER_FILE)
                    file.outputStream().use { out ->
                        scaled.compress(Bitmap.CompressFormat.PNG, 90, out)
                    }
                    useCustomWallpaper = true
                    wallpaperBg.setImageBitmap(scaled)
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().apply {
                        putBoolean(KEY_USE_CUSTOM_WALLPAPER, true)
                        apply()
                    }
                }
            }.onFailure {
                Toast.makeText(this@BrowserActivity, getString(R.string.browser_wallpaper_save_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showWallpaperPicker() {
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(if (isGlassStyle) glassOverlay else solidSurface)
            setPadding(dp(SPACING_XL), dp(SPACING_XL), dp(SPACING_XL), dp(SPACING_XL))
        }

        // 标题行
        container.addView(LinearLayout(this@BrowserActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)

            addView(TextView(this@BrowserActivity).apply {
                text = getString(R.string.browser_wallpaper_title)
                textSize = 18f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (isGlassStyle) cOnWallpaper else cText)
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            })

            addView(TextView(this@BrowserActivity).apply {
                text = "×"
                textSize = 20f
                setTextColor(if (isGlassStyle) cOnWallpaperMuted else cMuted)
                val s = dp(ICON_SIZE_SM)
                layoutParams = LinearLayout.LayoutParams(s, s)
                gravity = Gravity.CENTER
                setOnClickListener { sheet.dismiss() }
            })
        })

        // 预设壁纸标题
        container.addView(TextView(this@BrowserActivity).apply {
            text = getString(R.string.browser_wallpaper_presets)
            textSize = 13f
            setTextColor(if (isGlassStyle) cOnWallpaperMuted else cMuted)
            setPadding(0, dp(SPACING_MD), 0, dp(SPACING_SM))
        })

        // 预设壁纸网格（2行3列）
        for (row in 0..1) {
            val rowLayout = LinearLayout(this@BrowserActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            }
            for (col in 0..2) {
                val idx = row * 3 + col
                if (idx < wallpapers.size) {
                    val isSelected = !useCustomWallpaper && wallpaperIndex == idx
                    rowLayout.addView(LinearLayout(this@BrowserActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                            marginStart = dp(SPACING_XS)
                            marginEnd = dp(SPACING_XS)
                            bottomMargin = dp(SPACING_SM)
                        }

                        // 缩略图
                        addView(ImageView(this@BrowserActivity).apply {
                            val size = dp(THUMB_SIZE)
                            layoutParams = LinearLayout.LayoutParams(size, size)
                            setImageDrawable(GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, wallpapers[idx]).apply {
                                cornerRadius = dp(RADIUS_MD).toFloat()
                            })
                            elevation = dp(2).toFloat()
                            if (isSelected) {
                                foreground = GradientDrawable().apply {
                                    setStroke(dp(2), cPrimary)
                                    cornerRadius = dp(RADIUS_MD).toFloat()
                                }
                            }
                        })

                        // 选中指示
                        addView(View(this@BrowserActivity).apply {
                            val indicatorSize = if (isSelected) dp(SPACING_XS) else 0
                            layoutParams = LinearLayout.LayoutParams(indicatorSize, indicatorSize)
                            background = GradientDrawable().apply {
                                setColor(if (isSelected) cPrimary else Color.TRANSPARENT)
                                cornerRadius = dp(3).toFloat()
                            }
                            setPadding(0, dp(SPACING_XS), 0, 0)
                        })

                        setOnClickListener {
                            selectPresetWallpaper(idx)
                            sheet.dismiss()
                        }
                    })
                }
            }
            container.addView(rowLayout)
        }

        // 分隔线
        container.addView(View(this@BrowserActivity).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(1)).apply {
                topMargin = dp(SPACING_SM)
                bottomMargin = dp(SPACING_SM)
            }
            setBackgroundColor(if (isGlassStyle) withAlpha(cOnWallpaper, 40) else OctopusColors.Border.toArgb())
        })

        // 上传壁纸按钮
        container.addView(LinearLayout(this@BrowserActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = dp(SPACING_XS)
            }
            background = if (isGlassStyle) {
                capsuleBg(withAlpha(cOnWallpaper, 60), RADIUS_LG)
            } else {
                solidRoundRect(solidSurfaceVariant, RADIUS_LG, solidStroke, 1)
            }
            setPadding(dp(SPACING_MD), dp(SPACING_MD), dp(SPACING_MD), dp(SPACING_MD))
            elevation = if (isGlassStyle) dp(2).toFloat() else 0f

            addView(ImageView(this@BrowserActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(ICON_SIZE_SM), dp(ICON_SIZE_SM))
                setImageResource(android.R.drawable.ic_menu_camera)
                setColorFilter(if (isGlassStyle) cOnWallpaper else cText)
                scaleType = ImageView.ScaleType.FIT_CENTER
            })

            addView(LinearLayout(this@BrowserActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                    marginStart = dp(SPACING_MD)
                }

                addView(TextView(this@BrowserActivity).apply {
                    text = getString(R.string.browser_wallpaper_upload)
                    textSize = 14f
                    setTextColor(if (isGlassStyle) cOnWallpaper else cText)
                    setTypeface(typeface, Typeface.BOLD)
                })

                addView(TextView(this@BrowserActivity).apply {
                    text = getString(R.string.browser_wallpaper_upload_hint)
                    textSize = 11f
                    setTextColor(if (isGlassStyle) cOnWallpaperFaint else cMuted)
                })
            })

            setOnClickListener {
                imagePickerLauncher.launch(arrayOf("image/*"))
                sheet.dismiss()
            }
        })

        // 当前使用自定义壁纸时显示删除按钮
        if (useCustomWallpaper) {
            container.addView(TextView(this@BrowserActivity).apply {
                text = getString(R.string.browser_wallpaper_remove_custom)
                textSize = 12f
                setTextColor(cError)
                gravity = Gravity.CENTER
                setPadding(0, dp(SPACING_SM), 0, 0)
                setOnClickListener {
                    useCustomWallpaper = false
                    val file = File(filesDir, CUSTOM_WALLPAPER_FILE)
                    file.delete()
                    applyWallpaper()
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                        .putBoolean(KEY_USE_CUSTOM_WALLPAPER, false).apply()
                    sheet.dismiss()
                }
            })
        }

        sheet.setContentView(container)
        sheet.show()
        applyDialogBlur(sheet)
    }

    // ── 毛玻璃辅助 ──

    private fun capsuleBg(color: Int, radiusDp: Int, strokeColor: Int? = null, strokeDp: Int = 1): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeColor != null) setStroke(dp(strokeDp).coerceAtLeast(1), strokeColor)
        }
    }

    private fun toggleAiMode() {
        aiMode = !aiMode
        aiBadge.text = if (aiMode) "AI" else "URL"
        etUrl.hint = if (aiMode) getString(R.string.browser_url_hint) else getString(R.string.browser_input_url)
        etUrl.inputType = if (aiMode) InputType.TYPE_CLASS_TEXT else InputType.TYPE_TEXT_VARIATION_URI
    }

    private fun showTabInfo() {
        val url = engine.currentUrl()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.browser_current_tab))
            .setMessage(url)
            .setPositiveButton(getString(R.string.browser_copy_url)) { _, _ ->
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("URL", url))
                Toast.makeText(this, getString(R.string.browser_copied), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.advanced_action_close), null)
            .show()
    }

    // ── AI 浏览器功能 ──

    private fun showAiSheet() {
        val pageText = com.apk.claw.android.service.ClawAccessibilityService.getInstance()
            ?.let { runCatching { it.screenTree }.getOrNull() }

        val pad = dp(SPACING_LG)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(if (isGlassStyle) glassOverlay else solidSurface)
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
                setTextColor(cWarning)
                setPadding(0, dp(SPACING_SM), 0, 0)
            })
        }

        val answer = TextView(this).apply {
            textSize = 14f
            setTextColor(cText)
            setLineSpacing(0f, 1.2f)
            setPadding(0, dp(SPACING_MD), 0, 0)
        }
        val answerScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(220))
            addView(answer)
        }

        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(SPACING_MD), 0, dp(SPACING_SM))
        }
        chip(chips, getString(R.string.browser_chip_summarize), cPrimary) { runAi(answer, getString(R.string.browser_ai_prompt_summarize), pageText) }
        chip(chips, getString(R.string.browser_chip_key_points), cPrimary) { runAi(answer, getString(R.string.browser_ai_prompt_key_points), pageText) }
        chip(chips, getString(R.string.browser_chip_translate), cPrimary) { runAi(answer, getString(R.string.browser_ai_prompt_translate), pageText) }
        container.addView(chips)

        val chips2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(SPACING_SM + 2))
        }
        chip(chips2, getString(R.string.browser_chip_reader), cText) { showReader(pageText) }
        chip(chips2, getString(R.string.browser_chip_speak), cText) { speakPage(pageText) }
        container.addView(chips2)

        val etAsk = EditText(this).apply {
            hint = getString(R.string.browser_input_ask_or_operate)
            setSingleLine(true)
            textSize = 14f
            setTextColor(cText)
            setHintTextColor(cMuted)
            background = capsuleBg(glassCard, RADIUS_XL, withAlpha(cPrimary, 40), 1)
            setPadding(dp(SPACING_MD), dp(SPACING_SM + 2), dp(SPACING_MD), dp(SPACING_SM + 2))
            imeOptions = EditorInfo.IME_ACTION_SEND
        }

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        inputRow.addView(etAsk, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        inputRow.addView(TextView(this).apply {
            text = getString(R.string.browser_ask_button)
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(cOnPrimary)
            background = capsuleBg(cPrimary, RADIUS_XL)
            setPadding(dp(SPACING_MD), dp(SPACING_SM + 2), dp(SPACING_MD), dp(SPACING_SM + 2))
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(6) }
            setOnClickListener {
                val q = etAsk.text.toString().trim()
                if (q.isNotEmpty()) runAi(answer, q, pageText)
            }
        })

        inputRow.addView(TextView(this).apply {
            text = getString(R.string.browser_execute_button)
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(cOnPrimary)
            background = capsuleBg(cWarning, RADIUS_XL)
            setPadding(dp(SPACING_MD), dp(SPACING_SM + 2), dp(SPACING_MD), dp(SPACING_SM + 2))
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(6) }
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
        applyDialogBlur(sheet)
    }

    private fun chip(parent: LinearLayout, label: String, accent: Int, onClick: () -> Unit) {
        parent.addView(TextView(this).apply {
            text = label
            textSize = 12f
            setTextColor(accent)
            background = capsuleBg(withAlpha(accent, 38), RADIUS_LG)
            setPadding(dp(SPACING_MD), dp(SPACING_XS + 3), dp(SPACING_MD), dp(SPACING_XS + 3))
            val lp = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
            lp.marginEnd = dp(SPACING_SM)
            layoutParams = lp
            setOnClickListener { onClick() }
        })
    }

    private fun extractReadableText(tree: String?): String {
        if (tree.isNullOrBlank()) return ""
        val chrome = getString(R.string.browser_chrome_filter).split(",").map { it.trim() }.toSet()
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
            setPadding(dp(SPACING_XL), dp(SPACING_XL), dp(SPACING_XL), dp(SPACING_XL))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(glassOverlay)
            addView(tv)
        }
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        sheet.setContentView(scroll)
        sheet.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        sheet.show()
        applyDialogBlur(sheet)
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
        val markers = getString(R.string.browser_page_cmd_markers).split(",").map { it.trim() }
        return markers.any { t.contains(it) }
    }

    private fun runAgentOnPage(task: String) {
        if (!com.apk.claw.android.ui.compose.screen.ChatAgentBridge.isConfigured()) {
            Toast.makeText(this, getString(R.string.browser_configure_api_key), Toast.LENGTH_SHORT).show()
            return
        }
        val prompt = getString(R.string.browser_agent_prompt, task)
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
        val prompt = getString(R.string.browser_ai_system_prompt, question, ctx)
        val sb = StringBuilder()
        com.apk.claw.android.ui.compose.screen.ChatAgentBridge.run(
            prompt,
            onTool = { _, _, _, _ -> },
            onText = { t -> sb.append(t); answer.text = sb.toString() },
            onDone = { d -> answer.text = if (sb.isNotEmpty()) sb.toString() else d },
            onError = { e -> answer.text = getString(R.string.browser_error_message, e) },
        )
    }

    // ── 引擎事件收集 ──

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
                            // 记录到当前窗口(供「所有窗口」列表展示)
                            currentWindow()?.apply {
                                url = event.url
                                title = event.title.ifBlank { event.url }
                            }
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
                            if (event.message.startsWith(getString(R.string.browser_installing_extension_toast))) {
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

    // ── 导航 ──

    private fun navigateTo(input: String) {
        hideHome()
        val url = if (input.contains(".") && !input.contains(" ")) {
            if (input.startsWith("http://") || input.startsWith("https://")) input
            else "https://$input"
        } else {
            SearchEngines.byId(KVUtils.getSearchEngine()).searchUrl(input)
        }
        etUrl.setText(SearchEngines.extractQuery(url) ?: url)
        engine.navigate(url)
    }

    // ── 书签 Dialog ──

    private fun showBookmarkDialog() {
        val bookmarks = BookmarkManager.getAll()
        val currentUrl = engine.currentUrl()
        val isBookmarked = BookmarkManager.isBookmarked(currentUrl)
        val isCommonSite = CommonSiteStore.contains(currentUrl)

        val items = mutableListOf<String>()
        if (isBookmarked) {
            items.add(getString(R.string.browser_remove_bookmark))
        } else {
            items.add(getString(R.string.browser_bookmark_current))
        }
        if (isCommonSite) {
            items.add(getString(R.string.browser_remove_common_site))
        } else {
            items.add(getString(R.string.browser_add_common_site))
        }
        items.add(getString(R.string.browser_saved_bookmarks_header))
        bookmarks.forEach { items.add("${it.title}\n${it.url}") }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.browser_bookmarks_button))
            .setItems(items.toTypedArray()) { _, which ->
                when {
                    which == 0 -> {
                        if (isBookmarked) {
                            BookmarkManager.remove(currentUrl)
                            Toast.makeText(this, getString(R.string.browser_bookmark_removed), Toast.LENGTH_SHORT).show()
                        } else {
                            BookmarkManager.add(currentUrl, etUrl.text.toString().ifEmpty { currentUrl })
                            Toast.makeText(this, getString(R.string.browser_bookmarked), Toast.LENGTH_SHORT).show()
                        }
                    }
                    which == 1 -> {
                        if (isCommonSite) {
                            CommonSiteStore.remove(currentUrl)
                            Toast.makeText(this, getString(R.string.browser_home_removed_toast), Toast.LENGTH_SHORT).show()
                        } else {
                            CommonSiteStore.add(currentUrl, etUrl.text.toString().ifEmpty { currentUrl })
                            Toast.makeText(this, getString(R.string.browser_common_site_added), Toast.LENGTH_SHORT).show()
                        }
                    }
                    which > 2 -> {
                        val bookmark = bookmarks[which - 3]
                        navigateTo(bookmark.url)
                    }
                }
            }
            .setNegativeButton(getString(R.string.advanced_action_close), null)
            .show()
    }

    // 浏览器扩展(WebExtension)随 GeckoView 一并移除;扩展能力改由自建注入式插件生态承载。

    // ── 辅助 ──

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(etUrl.windowToken, 0)
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun roundedBg(color: Int, radiusDp: Int, strokeColor: Int? = null, strokeDp: Int = 1) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeColor != null) setStroke(dp(strokeDp).coerceAtLeast(1), strokeColor)
        }

    private fun withAlpha(color: Int, alpha: Int) =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    /** 依据背景亮度自动选黑/白前景色——保证首字母在浅色 tint（如浅青/黄）上也清晰。 */
    private fun contrastText(bg: Int): Int {
        val luminance = (0.299 * Color.red(bg) + 0.587 * Color.green(bg) + 0.114 * Color.blue(bg)) / 255.0
        return if (luminance > 0.6) Color.argb(235, 20, 20, 28) else cOnPrimary
    }
}
