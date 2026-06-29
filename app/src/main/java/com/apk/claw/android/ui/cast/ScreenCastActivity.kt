package com.apk.claw.android.ui.cast

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.apk.claw.android.R
import com.apk.claw.android.base.BaseActivity
import com.apk.claw.android.cast.ScreenCastService
import com.apk.claw.android.octopus_mobile.DeviceRemoteControl
import com.apk.claw.android.widget.CommonToolbar
import com.apk.claw.android.widget.KButton
import com.apk.claw.android.widget.MenuGroup
import com.apk.claw.android.widget.MjpegImageView

/**
 * 投屏控制面板
 *
 * - 状态卡片：投屏状态 + 外接显示器信息 + 开始/停止按钮
 * - MjpegImageView 远程屏幕预览
 * - 远程操作 MenuGroup
 * - 在外接屏启动 App MenuGroup
 */
class ScreenCastActivity : BaseActivity() {

    companion object {
        private const val TAG = "ScreenCastActivity"
    }

    private val castService get() = ScreenCastService.getInstance(this)
    private val remoteControl = DeviceRemoteControl()

    private lateinit var tvCastStatus: TextView
    private lateinit var tvDisplayInfo: TextView
    private lateinit var btnToggle: KButton
    private lateinit var mjpegView: MjpegImageView
    private lateinit var etStreamUrl: EditText
    private lateinit var btnPreview: KButton
    private lateinit var remoteGroup: MenuGroup
    private lateinit var launchGroup: MenuGroup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = buildLayout()
        setContentView(root)
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        mjpegView.stop()
    }

    // ── 布局构建 ─────────────────────────────────────

    private fun buildLayout(): ScrollView {
        val dp16 = dp(16)
        val dp8 = dp(8)
        val dp12 = dp(12)
        val dp4 = dp(4)

        val scrollView = ScrollView(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            isFillViewport = true
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }

        // Toolbar
        content.addView(CommonToolbar(this@ScreenCastActivity).apply {
            setTitle(getString(R.string.settings_cast_control))
            showBackButton(true) { finish() }
        })

        val padding = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp16, dp8, dp16, dp16)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }

        // ── 状态卡片 ──
        val statusCard = MenuGroup(this).apply {
            setTitle(getString(R.string.screen_cast_status_title))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp12 }
        }

        // 状态行
        tvCastStatus = TextView(this).apply {
            text = getString(R.string.screen_cast_not_casting)
            setTextColor(Color.GRAY)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp8, dp8, dp8, dp4)
        }
        // 添加到 statusCard 之前（因为 MenuGroup 内部结构不适合放自定义子 View）
        padding.addView(statusCard)

        // 显示器信息
        tvDisplayInfo = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#666666"))
            setPadding(dp8, 0, dp8, dp8)
            text = castService.displayManager.getExternalDisplayInfo()
        }
        padding.addView(tvDisplayInfo)

        // 开始/停止按钮
        btnToggle = KButton(this).apply {
            text = if (castService.isCasting) getString(R.string.screen_cast_stop_button) else getString(R.string.screen_cast_start_button)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp12 }
            setOnClickListener {
                if (castService.isCasting) {
                    castService.stop()
                } else {
                    castService.start()
                }
                refreshStatus()
            }
        }
        padding.addView(btnToggle)

        // ── MJPEG 远程屏幕预览 ──
        val previewLabel = TextView(this).apply {
            text = getString(R.string.screen_cast_preview_label)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp4)
        }
        padding.addView(previewLabel)

        mjpegView = MjpegImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(240)).apply { bottomMargin = dp8 }
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.parseColor("#F0F0F0"))
        }
        padding.addView(mjpegView)

        // 流地址输入 + 预览按钮
        val streamRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp12 }
        }
        etStreamUrl = EditText(this).apply {
            hint = "http://192.168.x.x:8080/api/screen/stream?quality=65&maxWidth=900&fps=20"
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            setSingleLine(true)
            setPadding(dp8, dp8, dp8, dp8)
        }
        streamRow.addView(etStreamUrl)

        btnPreview = KButton(this).apply {
            text = getString(R.string.screen_cast_preview_button)
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp8 }
            setOnClickListener {
                val url = etStreamUrl.text.toString().trim()
                if (url.isNotEmpty()) {
                    mjpegView.start(url)
                    Toast.makeText(this@ScreenCastActivity, getString(R.string.screen_cast_connecting_toast), Toast.LENGTH_SHORT).show()
                }
            }
        }
        streamRow.addView(btnPreview)
        padding.addView(streamRow)

        // ── 远程操作 ──
        remoteGroup = MenuGroup(this).apply {
            setTitle(getString(R.string.screen_cast_remote_control_title))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp12 }
        }
        remoteGroup.addMenuItem(R.drawable.ic_settings, "Home", { sendLocalAction("home") }, showDivider = true)
        remoteGroup.addMenuItem(R.drawable.ic_back, "Back", { sendLocalAction("back") }, showDivider = true)
        remoteGroup.addMenuItem(R.drawable.ic_settings, getString(R.string.tool_name_screenshot), { captureLocalScreen() }, showDivider = true)
        remoteGroup.addMenuItem(R.drawable.ic_settings, getString(R.string.tool_name_input_text), { showInputDialog() }, showDivider = false)
        padding.addView(remoteGroup)

        // ── 在外接屏启动 App ──
        launchGroup = MenuGroup(this).apply {
            setTitle(getString(R.string.screen_cast_launch_external_title))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp12 }
        }
        launchGroup.addMenuItem(R.drawable.ic_settings, getString(R.string.menu_wechat), { launchOnCast("com.tencent.mm") }, showDivider = true)
        launchGroup.addMenuItem(R.drawable.ic_browser, getString(R.string.discover_shortcut_browser), { launchOnCast("com.android.chrome") }, showDivider = true)
        launchGroup.addMenuItem(R.drawable.ic_settings, getString(R.string.screen_cast_custom_package_menu), { showPackageInputDialog() }, showDivider = false)
        padding.addView(launchGroup)

        content.addView(padding)
        scrollView.addView(content)
        return scrollView
    }

    // ── 状态刷新 ─────────────────────────────────────

    private fun refreshStatus() {
        val casting = castService.isCasting
        tvCastStatus.text = if (casting) getString(R.string.screen_cast_casting) else getString(R.string.screen_cast_not_casting_indicator)
        tvCastStatus.setTextColor(if (casting) Color.parseColor("#4CAF50") else Color.GRAY)
        tvDisplayInfo.text = castService.displayManager.getExternalDisplayInfo()
        btnToggle.text = if (casting) getString(R.string.screen_cast_stop_button) else getString(R.string.screen_cast_start_button)
    }

    // ── 远程操作（本地设备通过 AccessibilityService） ──

    private fun sendLocalAction(action: String) {
        // 通过 AccessibilityService 发送本地按键
        val service = com.apk.claw.android.service.ClawAccessibilityService.getInstance()
        if (service == null) {
            Toast.makeText(this, getString(R.string.screen_cast_service_not_running), Toast.LENGTH_SHORT).show()
            return
        }
        when (action) {
            "home" -> service.pressHome()
            "back" -> service.pressBack()
            "recent" -> service.openRecentApps()
        }
        Toast.makeText(this, getString(R.string.screen_cast_action_sent, action), Toast.LENGTH_SHORT).show()
    }

    private fun captureLocalScreen() {
        // 通过 ScreenCaptureManager 截取本地屏幕
        Toast.makeText(this, getString(R.string.screen_cast_screenshot_api_required), Toast.LENGTH_LONG).show()
    }

    private fun showInputDialog() {
        val et = EditText(this).apply {
            hint = getString(R.string.tool_name_input_text)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.tool_name_input_text))
            .setView(et)
            .setPositiveButton(getString(R.string.screen_cast_send_button)) { _, _ ->
                val text = et.text.toString()
                if (text.isNotEmpty()) {
                    val service = com.apk.claw.android.service.ClawAccessibilityService.getInstance()
                    service?.let {
                        // 通过剪贴板 + 粘贴实现文本输入
                        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("input", text))
                        Toast.makeText(this, getString(R.string.screen_cast_text_copied), Toast.LENGTH_SHORT).show()
                    } ?: Toast.makeText(this, getString(R.string.screen_cast_service_not_running), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show()
    }

    // ── 在外接屏启动 ─────────────────────────────────

    private fun launchOnCast(packageName: String) {
        val ok = castService.launchAppOnExternalDisplay(packageName)
        Toast.makeText(
            this,
            if (ok) getString(R.string.screen_cast_app_launched, packageName) else getString(R.string.screen_cast_launch_failed),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun showPackageInputDialog() {
        val et = EditText(this).apply {
            hint = "com.example.app"
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.screen_cast_enter_package_name))
            .setView(et)
            .setPositiveButton(getString(R.string.screen_cast_launch_button)) { _, _ ->
                val pkg = et.text.toString().trim()
                if (pkg.isNotEmpty()) launchOnCast(pkg)
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show()
    }

    // ── 辅助 ─────────────────────────────────────────

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
