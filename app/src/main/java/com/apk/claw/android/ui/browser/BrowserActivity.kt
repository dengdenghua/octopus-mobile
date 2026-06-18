package com.apk.claw.android.ui.browser

import android.app.AlertDialog
import android.content.Intent
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

    // ── 分类应用数据 ──
    private data class DesktopApp(
        val name: String,
        val url: String,
        val emoji: String,
        val color: Int,
        val category: String,
    )

    private data class AppGroup(
        val id: String,
        val title: String,
        val titleEmoji: String,
        val subtitle: String,
        val apps: List<DesktopApp>,
    )

    private val cPrimary get() = OctopusColors.Primary.toArgb()
    private val cText get() = OctopusColors.TextPrimary.toArgb()
    private val cMuted get() = OctopusColors.TextMuted.toArgb()

    // 壁纸上的浅色文字（壁纸始终深色，故文字始终浅色，不随主题变）
    private val cOnWallpaper: Int get() = Color.argb(235, 255, 255, 255)
    private val cOnWallpaperMuted: Int get() = Color.argb(170, 210, 210, 225)
    private val cOnWallpaperFaint: Int get() = Color.argb(140, 180, 180, 200)

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
    private fun homeGlass(): Int = Color.argb(if (OctopusColors.isLight) 120 else 70, 255, 255, 255)

    /** 玻璃描边色（亮色=深色描边，暗色=浅色描边，模拟玻璃边缘） */
    private val glassStroke: Int get() = if (OctopusColors.isLight) withAlpha(cText, 30) else withAlpha(cOnWallpaper, 35)

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

    /** 给 BottomSheetDialog 的 window 加真实背景模糊（API31+），低版本由 glassOverlay 兜底 */
    private fun applyDialogBlur(sheet: com.google.android.material.bottomsheet.BottomSheetDialog) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
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

    private val appGroups by lazy { buildAppGroups() }
    private val dockApps by lazy { buildDockApps() }

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
    private lateinit var homeLayer: LinearLayout
    private lateinit var contentLayer: LinearLayout
    private var isHomeVisible = true
    private val bookmarkManager = BookmarkManager()
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

        engine = BrowserEngineFactory.selectBest(this)
        Log.i(TAG, "Browser engine: ${engine.name}")

        val root = buildLayout()
        setContentView(root)
        runCatching { window.statusBarColor = Color.TRANSPARENT }
        runCatching { window.navigationBarColor = Color.TRANSPARENT }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
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
    }

    override fun onDestroy() {
        super.onDestroy()
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
                    marginStart = dp(8)
                    marginEnd = dp(8)
                    topMargin = dp(4)
                    bottomMargin = dp(4)
                }
                outlineProvider = android.view.ViewOutlineProvider.BOUNDS
                clipToOutline = false
                background = glassBg(20, 0.78f)
                elevation = dp(4).toFloat()
            }
            contentLayer.addView(browserContainer)

            // Loading 遮罩
            loadingOverlay = LinearLayout(this@BrowserActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(glassOverlay)
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

    private fun buildHomeLayer(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            visibility = if (isHomeVisible) View.VISIBLE else View.GONE

            // 顶部控制条(首页隐藏了网页顶栏,这里保留壁纸/关闭入口),含状态栏安全区
            addView(LinearLayout(this@BrowserActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                setPadding(dp(12), dp(32), dp(12), dp(4))
                addView(View(this@BrowserActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(0, dp(1), 1f)
                })
                addView(TextView(this@BrowserActivity).apply {
                    text = "🎨"
                    textSize = 15f
                    gravity = Gravity.CENTER
                    val s = dp(36)
                    layoutParams = LinearLayout.LayoutParams(s, s)
                    background = capsuleBg(withAlpha(cOnWallpaper, 25), 18)
                    setOnClickListener { showWallpaperPicker() }
                    contentDescription = "Wallpaper"
                })
                addView(TextView(this@BrowserActivity).apply {
                    text = "✕"
                    textSize = 15f
                    gravity = Gravity.CENTER
                    setTextColor(cOnWallpaper)
                    val s = dp(36)
                    layoutParams = LinearLayout.LayoutParams(s, s).apply { marginStart = dp(8) }
                    background = capsuleBg(withAlpha(cOnWallpaper, 25), 18)
                    setOnClickListener { finish() }
                    contentDescription = getString(R.string.advanced_action_close)
                })
            })

            // 品牌区域 —— 章鱼 Logo + 品牌名 + Slogan
            addView(buildBrandHeader())

            // 搜索栏
            addView(buildHomeSearchBar())

            // 中间滚动区域
            addView(ScrollView(this@BrowserActivity).apply {
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
                isFillViewport = true

                val scrollContent = LinearLayout(this@BrowserActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                    setPadding(dp(16), dp(12), dp(16), dp(12))

                    // 分类图标网格
                    addView(buildCategoryGrid())

                    // 收藏标签
                    addView(buildBookmarksSection())
                }
                addView(scrollContent)
            })
        }
    }

    /** 品牌头部区域 */
    private fun buildBrandHeader(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            setPadding(0, dp(20), 0, dp(16))

            // 章鱼 Logo
            addView(TextView(this@BrowserActivity).apply {
                text = "🐙"
                textSize = 48f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
            })

            // 品牌名
            addView(TextView(this@BrowserActivity).apply {
                text = getString(R.string.browser_brand_name)
                textSize = 26f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(cOnWallpaper)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                    topMargin = dp(6)
                }
            })

            // Slogan
            addView(TextView(this@BrowserActivity).apply {
                text = getString(R.string.browser_brand_slogan)
                textSize = 13f
                setTextColor(cOnWallpaperMuted)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                    topMargin = dp(4)
                }
            })
        }
    }

    /** 首页搜索栏 */
    private fun buildHomeSearchBar(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                marginStart = dp(16)
                marginEnd = dp(16)
            }
            setPadding(0, dp(8), 0, dp(8))

            // 搜索输入框
            val searchBox = LinearLayout(this@BrowserActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f)
                background = capsuleBg(Color.argb(160, 255, 255, 255), 23, withAlpha(cPrimary, 40), 1)
                setPadding(dp(14), 0, dp(8), 0)

                // 搜索图标
                addView(TextView(this@BrowserActivity).apply {
                    text = "🔍"
                    textSize = 16f
                    layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
                })

                // 输入框
                addView(EditText(this@BrowserActivity).apply {
                    hint = getString(R.string.browser_url_hint)
                    setSingleLine(true)
                    textSize = 14f
                    setTextColor(Color.argb(230, 30, 30, 40))
                    setHintTextColor(Color.argb(120, 80, 80, 100))
                    background = null
                    layoutParams = LinearLayout.LayoutParams(0, MATCH_PARENT, 1f)
                    setPadding(dp(8), 0, dp(4), 0)
                    imeOptions = EditorInfo.IME_ACTION_GO
                    inputType = InputType.TYPE_TEXT_VARIATION_URI
                    setOnEditorActionListener { _, actionId, _ ->
                        if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                            val t = text.toString().trim()
                            if (t.isNotEmpty()) {
                                hideKeyboard()
                                navigateTo(t)
                            }
                            true
                        } else false
                    }
                })
            }
            addView(searchBox)

            // AI 按钮
            addView(TextView(this@BrowserActivity).apply {
                text = "✨"
                textSize = 18f
                gravity = Gravity.CENTER
                val s = dp(42)
                layoutParams = LinearLayout.LayoutParams(s, s).apply { marginStart = dp(8) }
                background = capsuleBg(cPrimary, 21)
                setOnClickListener { showAiSheet() }
                contentDescription = getString(R.string.browser_ask_ai_button)
            })
        }
    }

    /** 分类图标网格 */
    private fun buildCategoryGrid(): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }

        // 标题
        container.addView(TextView(this).apply {
            text = getString(R.string.browser_home_categories)
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(cOnWallpaper)
            setPadding(0, dp(4), 0, dp(10))
        })

        // 2x2 网格
        val gridContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }

        for (rowIdx in appGroups.indices step 2) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            }

            for (colIdx in 0..1) {
                val groupIdx = rowIdx + colIdx
                if (groupIdx < appGroups.size) {
                    row.addView(buildCategoryCard(appGroups[groupIdx]))
                }
            }
            gridContainer.addView(row)
        }

        container.addView(gridContainer)
        return container
    }

    /** 单个分类:紧凑卡片(只包住 2×2 图标)+ 卡外下方分类标题(避免外围撑大) */
    private fun buildCategoryCard(group: AppGroup): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                marginStart = dp(5)
                marginEnd = dp(5)
                bottomMargin = dp(10)
            }

            // 紧凑卡片:宽度 WRAP_CONTENT,刚好包住 2×2 图标(不再填满半屏)
            addView(LinearLayout(this@BrowserActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
                background = capsuleBg(withAlpha(cOnWallpaper, 70), 18, withAlpha(cOnWallpaper, 35), 1)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                isClickable = true
                setOnClickListener { showGroupDetail(group) }

                for (r in 0..1) {
                    addView(LinearLayout(this@BrowserActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER
                        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                            if (r == 1) topMargin = dp(6)
                        }
                        for (c in 0..1) {
                            val idx = r * 2 + c
                            if (idx < group.apps.size) {
                                addView(buildAppIcon(group.apps[idx]))
                            } else {
                                addView(View(this@BrowserActivity).apply {
                                    layoutParams = LinearLayout.LayoutParams(dp(52), dp(44)).apply {
                                        marginStart = dp(3)
                                        marginEnd = dp(3)
                                    }
                                })
                            }
                        }
                    })
                }
            })

            // 卡外下方:分类标题(emoji + 名称),居中
            addView(TextView(this@BrowserActivity).apply {
                text = "${group.titleEmoji} ${group.title}"
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(cOnWallpaper)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                setPadding(0, dp(6), 0, 0)
            })
        }
    }

    /** 单个应用图标 */
    private fun buildAppIcon(app: DesktopApp): LinearLayout {
        return LinearLayout(this@BrowserActivity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(dp(52), WRAP_CONTENT).apply {
                marginStart = dp(3)
                marginEnd = dp(3)
            }

            // 真实网站图标(favicon);加载前/失败用品牌色块兜底
            addView(ImageView(this@BrowserActivity).apply {
                val s = dp(40)
                layoutParams = LinearLayout.LayoutParams(s, s)
                background = GradientDrawable().apply {
                    setColor(app.color)
                    cornerRadius = dp(12).toFloat()
                }
                val pad = dp(7)
                setPadding(pad, pad, pad, pad)
                scaleType = ImageView.ScaleType.FIT_CENTER
                runCatching {
                    com.bumptech.glide.Glide.with(this@BrowserActivity)
                        .load(faviconUrl(app.url))
                        .into(this)
                }
            })

            // 名称
            addView(TextView(this@BrowserActivity).apply {
                text = app.name
                textSize = 9f
                setTextColor(cOnWallpaperMuted)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                setPadding(0, dp(3), 0, 0)
                setSingleLine(true)
            })

            setOnClickListener { navigateTo(app.url) }
        }
    }

    /** 由站点 URL 取真实 favicon(Google s2 服务,稳定可用)。 */
    private fun faviconUrl(url: String): String {
        val host = runCatching { java.net.URI(url).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: url
        return "https://www.google.com/s2/favicons?sz=128&domain=$host"
    }

    /** 收藏标签区 */
    private fun buildBookmarksSection(): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            setPadding(0, dp(8), 0, 0)
        }

        container.addView(LinearLayout(this@BrowserActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)

            addView(TextView(this@BrowserActivity).apply {
                text = getString(R.string.browser_home_favorites)
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(cOnWallpaper)
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            })

            addView(TextView(this@BrowserActivity).apply {
                text = getString(R.string.browser_home_tools)
                textSize = 11f
                setTextColor(cOnWallpaperFaint)
                setOnClickListener { showBookmarkDialog() }
            })
        })

        // 收藏标签横向滚动
        val chipScroll = HorizontalScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            isHorizontalScrollBarEnabled = false
        }

        val chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
            setPadding(0, dp(8), 0, dp(4))
        }

        // 默认收藏标签
        val defaultBookmarks = listOf(
            "⭐ GitHub" to "https://github.com/",
            "⭐ Bilibili" to "https://www.bilibili.com/",
            "⭐ YouTube" to "https://www.youtube.com/",
            "⭐ 知乎" to "https://www.zhihu.com/",
            "⭐ Wikipedia" to "https://www.wikipedia.org/",
        )

        for ((label, url) in defaultBookmarks) {
            chipRow.addView(TextView(this).apply {
                text = label
                textSize = 12f
                setTextColor(cOnWallpaper)
                background = capsuleBg(withAlpha(cOnWallpaper, 70), 16, withAlpha(cOnWallpaper, 30), 1)
                setPadding(dp(12), dp(7), dp(12), dp(7))
                layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                    marginEnd = dp(8)
                }
                setOnClickListener { navigateTo(url) }
            })
        }

        chipScroll.addView(chipRow)
        container.addView(chipScroll)
        return container
    }

    /** Dock 栏 */
    private fun buildDockBar(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            setPadding(dp(8), dp(6), dp(8), dp(10))

            // 毛玻璃背景
            background = capsuleBg(withAlpha(cOnWallpaper, 120), 24, withAlpha(cOnWallpaper, 50), 1)

            for (app in dockApps) {
                addView(LinearLayout(this@BrowserActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)

                    addView(TextView(this@BrowserActivity).apply {
                        text = app.emoji
                        textSize = 22f
                        gravity = Gravity.CENTER
                        val s = dp(44)
                        layoutParams = LinearLayout.LayoutParams(s, s)
                        background = GradientDrawable().apply {
                            setColor(app.color)
                            cornerRadius = dp(14).toFloat()
                        }
                    })

                    addView(TextView(this@BrowserActivity).apply {
                        text = app.name
                        textSize = 9f
                        setTextColor(cOnWallpaperMuted)
                        gravity = Gravity.CENTER
                        setSingleLine(true)
                        setPadding(0, dp(2), 0, 0)
                    })

                    setOnClickListener { navigateTo(app.url) }
                })
            }
        }
    }

    /** 展开分类详情 */
    private fun showGroupDetail(group: AppGroup) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(glassOverlay)
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }

        // 标题行
        container.addView(LinearLayout(this@BrowserActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)

            addView(TextView(this@BrowserActivity).apply {
                text = "${group.titleEmoji} ${group.title}"
                textSize = 18f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(cOnWallpaper)
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            })

            addView(TextView(this@BrowserActivity).apply {
                text = "✕"
                textSize = 18f
                setTextColor(cOnWallpaperMuted)
                val s = dp(32)
                layoutParams = LinearLayout.LayoutParams(s, s)
                gravity = Gravity.CENTER
                background = capsuleBg(withAlpha(cOnWallpaper, 60), 16)
            })
        })

        container.addView(TextView(this@BrowserActivity).apply {
            text = group.subtitle
            textSize = 12f
            setTextColor(cOnWallpaperFaint)
            setPadding(0, dp(4), 0, dp(16))
        })

        // 应用列表
        for (app in group.apps) {
            container.addView(LinearLayout(this@BrowserActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    bottomMargin = dp(8)
                }
                background = capsuleBg(withAlpha(cOnWallpaper, 60), 16)
                setPadding(dp(12), dp(10), dp(12), dp(10))

                addView(TextView(this@BrowserActivity).apply {
                    text = app.emoji
                    textSize = 20f
                    gravity = Gravity.CENTER
                    val s = dp(36)
                    layoutParams = LinearLayout.LayoutParams(s, s)
                    background = GradientDrawable().apply {
                        setColor(app.color)
                        cornerRadius = dp(10).toFloat()
                    }
                })

                addView(LinearLayout(this@BrowserActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                        marginStart = dp(10)
                    }

                    addView(TextView(this@BrowserActivity).apply {
                        text = app.name
                        textSize = 14f
                        setTextColor(cOnWallpaper)
                        setTypeface(typeface, Typeface.BOLD)
                    })

                    addView(TextView(this@BrowserActivity).apply {
                        text = app.url
                        textSize = 10f
                        setTextColor(cOnWallpaperFaint)
                        setSingleLine(true)
                    })
                })

                setOnClickListener {
                    navigateTo(app.url)
                    // 关闭弹窗
                    (parent as? android.view.ViewGroup)?.let { p ->
                        (p.parent as? android.view.ViewGroup)?.let { pp ->
                            if (pp.parent is com.google.android.material.bottomsheet.BottomSheetDialog) {
                                (pp.parent as com.google.android.material.bottomsheet.BottomSheetDialog).dismiss()
                            }
                        }
                    }
                }
            })
        }

        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        sheet.setContentView(container)
        sheet.show()
        applyDialogBlur(sheet)
    }

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

    private fun buildAppGroups(): List<AppGroup> {
        val aiColor = Color.parseColor("#FF3B82F6")
        val videoColor = Color.parseColor("#FFEF4444")
        val devColor = Color.parseColor("#FF8B5CF6")
        val knowledgeColor = Color.parseColor("#FF06B6D4")

        return listOf(
            AppGroup("ai", getString(R.string.browser_cat_ai), "🤖",
                getString(R.string.browser_cat_ai_sub), listOf(
                    DesktopApp("Gemini", "https://gemini.google.com/app", "✨", aiColor, "ai"),
                    DesktopApp("ChatGPT", "https://chatgpt.com/", "💬", Color.parseColor("#FF6B7280"), "ai"),
                    DesktopApp("DeepSeek", "https://chat.deepseek.com/", "🧠", Color.parseColor("#FF4338CA"), "ai"),
                    DesktopApp("豆包", "https://www.doubao.com/chat/", "🗣", Color.parseColor("#FF10B981"), "ai"),
                    DesktopApp("Kimi", "https://www.kimi.com/", "🎓", Color.parseColor("#FF8B5CF6"), "ai"),
                    DesktopApp("Claude", "https://claude.ai/", "🪶", Color.parseColor("#FF78716C"), "ai"),
                    DesktopApp("通义", "https://chat.qwen.ai/", "🔮", Color.parseColor("#FF2563EB"), "ai"),
                    DesktopApp("Perplexity", "https://www.perplexity.ai/", "🔎", Color.parseColor("#FF0EA5E9"), "ai"),
                )),
            AppGroup("video", getString(R.string.browser_cat_video), "🎬",
                getString(R.string.browser_cat_video_sub), listOf(
                    DesktopApp("YouTube", "https://www.youtube.com/", "▶", videoColor, "video"),
                    DesktopApp("Bilibili", "https://www.bilibili.com/", "📺", Color.parseColor("#FF0EA5E9"), "video"),
                )),
            AppGroup("dev", getString(R.string.browser_cat_dev), "💻",
                getString(R.string.browser_cat_dev_sub), listOf(
                    DesktopApp("GitHub", "https://github.com/", "🐙", Color.parseColor("#FF1F2937"), "dev"),
                    DesktopApp("StackOverflow", "https://stackoverflow.com/", "📋", Color.parseColor("#FFF97316"), "dev"),
                    DesktopApp("MDN", "https://developer.mozilla.org/", "📖", devColor, "dev"),
                )),
            AppGroup("knowledge", getString(R.string.browser_cat_knowledge), "📚",
                getString(R.string.browser_cat_knowledge_sub), listOf(
                    DesktopApp("知乎", "https://www.zhihu.com/", "💡", Color.parseColor("#FF2563EB"), "knowledge"),
                    DesktopApp("Wikipedia", "https://www.wikipedia.org/", "🌍", Color.parseColor("#FF475569"), "knowledge"),
                )),
        )
    }

    private fun buildDockApps(): List<DesktopApp> {
        return listOf(
            DesktopApp("Gemini", "https://gemini.google.com/app", "✨", Color.parseColor("#FF3B82F6"), "ai"),
            DesktopApp("DeepSeek", "https://chat.deepseek.com/", "🧠", Color.parseColor("#FF4338CA"), "ai"),
            DesktopApp("YouTube", "https://www.youtube.com/", "▶", Color.parseColor("#FFEF4444"), "video"),
            DesktopApp("GitHub", "https://github.com/", "🐙", Color.parseColor("#FF1F2937"), "dev"),
            DesktopApp("Bilibili", "https://www.bilibili.com/", "📺", Color.parseColor("#FF0EA5E9"), "video"),
        )
    }

    // ── 毛玻璃顶栏 ──

    private fun buildTopBar(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            background = glassBg(0, 0.72f, stroke = false)  // 顶栏无圆角，全宽
            setPadding(dp(6), dp(8), dp(6), dp(8))

            // 返回胶囊按钮
            addView(TextView(this@BrowserActivity).apply {
                text = "←"
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(cMuted)
                val s = dp(36)
                layoutParams = LinearLayout.LayoutParams(s, s)
                background = capsuleBg(withAlpha(cPrimary, 30), 18)
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
                background = capsuleBg(glassCard, 20, withAlpha(cPrimary, 50), 1)
                setPadding(dp(8), 0, dp(4), 0)
            }

            aiBadge = TextView(this@BrowserActivity).apply {
                text = "✨"
                textSize = 14f
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
                setColorFilter(cMuted)
                background = capsuleBg(Color.TRANSPARENT, 14)
                setPadding(dp(4), dp(4), dp(4), dp(4))
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
                setTextColor(cMuted)
                val s = dp(36)
                layoutParams = LinearLayout.LayoutParams(s, s)
                background = capsuleBg(withAlpha(cPrimary, 30), 18)
                setOnClickListener {
                    if (isHomeVisible) hideHome() else showHome()
                }
                contentDescription = getString(R.string.browser_home_button)
            })

            // 壁纸切换胶囊
            addView(TextView(this@BrowserActivity).apply {
                text = "🎨"
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(cMuted)
                val s = dp(36)
                layoutParams = LinearLayout.LayoutParams(s, s).apply { marginStart = dp(2) }
                background = capsuleBg(withAlpha(cPrimary, 30), 18)
                setOnClickListener { showWallpaperPicker() }
                contentDescription = "Wallpaper"
            })
        }
    }

    // ── 毛玻璃底栏 ──

    /** 悬浮长胶囊底栏(浏览网页时):左 ☰ 设置 · 中 后退/前进/AI · 右 窗口数 */
    private fun buildBottomCapsule(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = glassBg(26, 0.82f)
            elevation = dp(10).toFloat()
            setPadding(dp(6), dp(6), dp(6), dp(6))
            // 悬浮固定在底部（导航栏上方）
            layoutParams = FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dp(16)
                marginStart = dp(16)
                marginEnd = dp(16)
            }

            // 左:三横 → 上滑出浏览器设置
            addView(makeCapsuleIcon("☰", "浏览器设置") { showBrowserSettingsSheet() })
            // 中:后退 / 前进 / AI(主页键地址栏已有,这里精简掉以缩短)
            addView(makeCapsuleIcon("‹", getString(R.string.browser_back_button)) { engine.evaluateJs("window.history.back()") })
            addView(makeCapsuleIcon("›", getString(R.string.browser_forward_button)) { engine.evaluateJs("window.history.forward()") })
            addView(makeCapsuleIcon("✨", getString(R.string.browser_ask_ai_button), accent = true) { showAiSheet() })
            // 右:窗口数 → 弹出所有窗口
            windowCountView = TextView(this@BrowserActivity).apply {
                text = windows.size.coerceAtLeast(1).toString()
                textSize = 13f
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(cText)
                val s = dp(32)
                layoutParams = LinearLayout.LayoutParams(s, s).apply { marginStart = dp(3); marginEnd = dp(1) }
                background = capsuleBg(withAlpha(cPrimary, 70), 8, withAlpha(cPrimary, 160), 1)
                contentDescription = "窗口"
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
            textSize = if (accent) 15f else 17f
            gravity = Gravity.CENTER
            setTextColor(if (accent) OctopusColors.OnPrimary.toArgb() else cText)
            background = if (accent) capsuleBg(cPrimary, 20) else capsuleBg(withAlpha(cText, 25), 20)
            contentDescription = desc
            setOnClickListener { onClick() }
        }
    }

    /** ☰ 上滑出的浏览器设置面板 */
    private fun showBrowserSettingsSheet() {
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(glassOverlay)
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        container.addView(TextView(this).apply {
            text = "浏览器设置"
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(cText)
            setPadding(dp(4), 0, 0, dp(8))
        })
        fun row(emoji: String, title: String, onClick: () -> Unit) {
            container.addView(TextView(this).apply {
                text = "$emoji   $title"
                textSize = 15f
                setTextColor(cOnWallpaper)
                setPadding(dp(6), dp(14), dp(6), dp(14))
                isClickable = true
                background = capsuleBg(withAlpha(Color.WHITE, 10), 12)
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(6) }
                setOnClickListener { sheet.dismiss(); onClick() }
            })
        }
        row("🔁", "刷新页面") { navigateTo(engine.currentUrl()) }
        row("🌐", "浏览器与引擎设置") {
            runCatching { startActivity(Intent(this, com.apk.claw.android.ui.featurescreens.BrowserSettingsActivity::class.java)) }
        }
        row("🎨", "更换壁纸") { showWallpaperPicker() }
        row("☆", "书签") { showBookmarkDialog() }
        row("🧩", "扩展") { showExtensionDialog() }
        row("🔗", "复制链接") {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("url", engine.currentUrl()))
            Toast.makeText(this, "已复制链接", Toast.LENGTH_SHORT).show()
        }
        row("↗", "用系统浏览器打开") {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(engine.currentUrl()))) }
        }
        sheet.setContentView(container)
        sheet.show()
        applyDialogBlur(sheet)
    }

    // ── 多窗口 ──

    private fun ensureWindow() {
        if (windows.isEmpty()) {
            val w = BrowserWindow("新标签页", "", windowSeq++)
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
        val w = BrowserWindow("新标签页", "", windowSeq++)
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
            setBackgroundColor(glassOverlay)
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        // 标题 + 新窗口
        container.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@BrowserActivity).apply {
                text = "窗口 (${windows.size})"
                textSize = 17f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(cText)
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            })
            addView(TextView(this@BrowserActivity).apply {
                text = "+ 新窗口"
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
                setPadding(dp(12), dp(7), dp(12), dp(7))
                background = capsuleBg(cPrimary, 16)
                setOnClickListener { sheet.dismiss(); newWindow() }
            })
        })
        // 列表
        windows.forEach { w ->
            container.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(12), dp(8), dp(12))
                val active = w.id == currentWindowId
                background = capsuleBg(
                    if (active) withAlpha(cPrimary, 45) else withAlpha(Color.WHITE, 12), 12,
                    if (active) cPrimary else Color.TRANSPARENT, 1,
                )
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) }
                isClickable = true
                setOnClickListener { sheet.dismiss(); switchWindow(w) }

                addView(LinearLayout(this@BrowserActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                    addView(TextView(this@BrowserActivity).apply {
                        text = w.title.ifBlank { "新标签页" }
                        textSize = 14f
                        setTextColor(cText)
                        setSingleLine(true)
                    })
                    addView(TextView(this@BrowserActivity).apply {
                        text = w.url.ifBlank { "—" }
                        textSize = 11f
                        setTextColor(cOnWallpaperMuted)
                        setSingleLine(true)
                    })
                })
                addView(TextView(this@BrowserActivity).apply {
                    text = "✕"
                    textSize = 15f
                    gravity = Gravity.CENTER
                    setTextColor(cOnWallpaperMuted)
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
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(glassOverlay)
            setPadding(dp(20), dp(20), dp(20), dp(20))
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
                setTextColor(cOnWallpaper)
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            })

            addView(TextView(this@BrowserActivity).apply {
                text = "✕"
                textSize = 18f
                setTextColor(cOnWallpaperMuted)
                val s = dp(32)
                layoutParams = LinearLayout.LayoutParams(s, s)
                gravity = Gravity.CENTER
            })
        })

        // 预设壁纸标题
        container.addView(TextView(this@BrowserActivity).apply {
            text = getString(R.string.browser_wallpaper_presets)
            textSize = 13f
            setTextColor(cOnWallpaperMuted)
            setPadding(0, dp(14), 0, dp(8))
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
                            marginStart = dp(4)
                            marginEnd = dp(4)
                            bottomMargin = dp(8)
                        }

                        // 缩略图
                        addView(ImageView(this@BrowserActivity).apply {
                            val size = dp(80)
                            layoutParams = LinearLayout.LayoutParams(size, size)
                            setImageDrawable(GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, wallpapers[idx]).apply {
                                cornerRadius = dp(14).toFloat()
                            })
                            if (isSelected) {
                                foreground = GradientDrawable().apply {
                                    setStroke(dp(2), cPrimary)
                                    cornerRadius = dp(14).toFloat()
                                }
                            }
                        })

                        // 选中指示
                        addView(View(this@BrowserActivity).apply {
                            val indicatorSize = if (isSelected) dp(6) else 0
                            layoutParams = LinearLayout.LayoutParams(indicatorSize, indicatorSize)
                            background = GradientDrawable().apply {
                                setColor(if (isSelected) cPrimary else Color.TRANSPARENT)
                                cornerRadius = dp(3).toFloat()
                            }
                            setPadding(0, dp(4), 0, 0)
                        })

                        setOnClickListener {
                            selectPresetWallpaper(idx)
                            // 关闭弹窗
                            (parent as? android.view.ViewGroup)?.let { p ->
                                (p.parent as? android.view.ViewGroup)?.let { pp ->
                                    (pp.parent as? com.google.android.material.bottomsheet.BottomSheetDialog)?.dismiss()
                                }
                            }
                        }
                    })
                }
            }
            container.addView(rowLayout)
        }

        // 分隔线
        container.addView(View(this@BrowserActivity).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(1)).apply {
                topMargin = dp(8)
                bottomMargin = dp(8)
            }
            setBackgroundColor(withAlpha(cOnWallpaper, 40))
        })

        // 上传壁纸按钮
        container.addView(LinearLayout(this@BrowserActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = dp(4)
            }
            background = capsuleBg(withAlpha(cOnWallpaper, 60), 16)
            setPadding(dp(14), dp(12), dp(14), dp(12))

            addView(TextView(this@BrowserActivity).apply {
                text = "📷"
                textSize = 20f
                layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
            })

            addView(LinearLayout(this@BrowserActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                    marginStart = dp(10)
                }

                addView(TextView(this@BrowserActivity).apply {
                    text = getString(R.string.browser_wallpaper_upload)
                    textSize = 14f
                    setTextColor(cOnWallpaper)
                    setTypeface(typeface, Typeface.BOLD)
                })

                addView(TextView(this@BrowserActivity).apply {
                    text = getString(R.string.browser_wallpaper_upload_hint)
                    textSize = 11f
                    setTextColor(cOnWallpaperFaint)
                })
            })

            setOnClickListener {
                imagePickerLauncher.launch(arrayOf("image/*"))
                // 关闭弹窗
                (parent as? android.view.ViewGroup)?.let { p ->
                    (p.parent as? android.view.ViewGroup)?.let { pp ->
                        (pp.parent as? com.google.android.material.bottomsheet.BottomSheetDialog)?.dismiss()
                    }
                }
            }
        })

        // 当前使用自定义壁纸时显示删除按钮
        if (useCustomWallpaper) {
            container.addView(TextView(this@BrowserActivity).apply {
                text = getString(R.string.browser_wallpaper_remove_custom)
                textSize = 12f
                setTextColor(Color.argb(180, 255, 100, 100))
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, 0)
                setOnClickListener {
                    useCustomWallpaper = false
                    val file = File(filesDir, CUSTOM_WALLPAPER_FILE)
                    file.delete()
                    applyWallpaper()
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                        .putBoolean(KEY_USE_CUSTOM_WALLPAPER, false).apply()
                    // 关闭弹窗
                    (parent as? android.view.ViewGroup)?.let { p ->
                        (p.parent as? android.view.ViewGroup)?.let { pp ->
                            (pp.parent as? com.google.android.material.bottomsheet.BottomSheetDialog)?.dismiss()
                        }
                    }
                }
            })
        }

        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
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
        aiBadge.text = if (aiMode) "✨" else "🌐"
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

        val pad = dp(16)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(glassOverlay)
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
                setTextColor(OctopusColors.Warning.toArgb())
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

        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, dp(8))
        }
        chip(chips, getString(R.string.browser_chip_summarize), cPrimary) { runAi(answer, getString(R.string.browser_ai_prompt_summarize), pageText) }
        chip(chips, getString(R.string.browser_chip_key_points), cPrimary) { runAi(answer, getString(R.string.browser_ai_prompt_key_points), pageText) }
        chip(chips, getString(R.string.browser_chip_translate), cPrimary) { runAi(answer, getString(R.string.browser_ai_prompt_translate), pageText) }
        container.addView(chips)

        val chips2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(10))
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
            background = capsuleBg(glassCard, 20, withAlpha(cPrimary, 40), 1)
            setPadding(dp(14), dp(10), dp(14), dp(10))
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
            setTextColor(Color.WHITE)
            background = capsuleBg(cPrimary, 20)
            setPadding(dp(14), dp(10), dp(14), dp(10))
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
            setTextColor(Color.WHITE)
            background = capsuleBg(OctopusColors.Warning.toArgb(), 20)
            setPadding(dp(14), dp(10), dp(14), dp(10))
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
            background = capsuleBg(withAlpha(accent, 38), 16)
            setPadding(dp(12), dp(7), dp(12), dp(7))
            val lp = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
            lp.marginEnd = dp(8)
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
            setPadding(dp(20), dp(20), dp(20), dp(20))
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

    // ── 扩展 Dialog ──

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
}
