package com.apk.claw.android.ui.device

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import androidx.lifecycle.lifecycleScope
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.R
import com.apk.claw.android.base.BaseActivity
import com.apk.claw.android.cast.ScreenCastService
import com.apk.claw.android.octopus_mobile.DeviceInfo
import com.apk.claw.android.octopus_mobile.DeviceRemoteControl
import com.apk.claw.android.widget.CommonToolbar
import com.apk.claw.android.widget.KButton
import com.apk.claw.android.widget.MenuGroup
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * 设备详情 + 远程控制页面
 */
class DeviceDetailActivity : BaseActivity() {

    companion object {
        const val EXTRA_DEVICE_ID = "device_id"
    }

    private val registry get() = ClawApplication.instance.deviceRegistry
    private val remoteControl = DeviceRemoteControl()

    private var device: DeviceInfo? = null

    private lateinit var tvName: TextView
    private lateinit var tvIp: TextView
    private lateinit var tvAndroid: TextView
    private lateinit var tvApp: TextView
    private lateinit var tvLastSeen: TextView
    private lateinit var tvStatus: TextView
    private lateinit var ivScreenshot: ImageView
    private lateinit var controlGroup: MenuGroup

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID) ?: run {
            finish()
            return
        }
        device = registry.getDevice(deviceId) ?: run {
            finish()
            return
        }

        val root = buildLayout()
        setContentView(root)
        bindDevice()
    }

    private fun buildLayout(): ScrollView {
        val dp16 = dp(16)
        val dp8 = dp(8)
        val dp12 = dp(12)
        val dp4 = dp(4)

        val scrollView = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            isFillViewport = true
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }

        // Toolbar
        content.addView(CommonToolbar(this).apply {
            setTitle(device?.deviceName ?: getString(R.string.device_detail_toolbar_title))
            showBackButton(true) { finish() }
        })

        val padding = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp16, dp8, dp16, dp16)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }

        // ── 状态指示 ──
        tvStatus = TextView(this).apply {
            text = if (device?.online == true) getString(R.string.device_detail_status_online) else getString(R.string.device_detail_status_offline)
            setTextColor(if (device?.online == true) Color.parseColor("#4CAF50") else Color.GRAY)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp8)
        }
        padding.addView(tvStatus)

        // ── 信息卡片 ──
        val infoCard = MenuGroup(this).apply {
            setTitle(getString(R.string.tool_name_device_info))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp12 }
        }

        tvName = TextView(this).apply { textSize = 14f; setPadding(dp8, dp8, dp8, dp4) }
        tvIp = TextView(this).apply { textSize = 14f; setPadding(dp8, dp4, dp8, dp4) }
        tvAndroid = TextView(this).apply { textSize = 14f; setPadding(dp8, dp4, dp8, dp4) }
        tvApp = TextView(this).apply { textSize = 14f; setPadding(dp8, dp4, dp8, dp4) }
        tvLastSeen = TextView(this).apply { textSize = 14f; setPadding(dp8, dp4, dp8, dp8) }

        // Use MenuItems for info display
        infoCard.addMenuItem(R.drawable.ic_devices, getString(R.string.device_detail_name_label), {}, showDivider = true)
            .setTrailingText(device?.deviceName ?: "N/A")
        infoCard.addMenuItem(R.drawable.ic_runtime, getString(R.string.device_detail_address_label), {}, showDivider = true)
            .setTrailingText("${device?.ip}:${device?.configServerPort}")
        infoCard.addMenuItem(R.drawable.ic_settings, getString(R.string.device_detail_android_label), {}, showDivider = true)
            .setTrailingText(device?.androidVersion ?: "N/A")
        infoCard.addMenuItem(R.drawable.ic_settings, getString(R.string.device_detail_app_version_label), {}, showDivider = true)
            .setTrailingText(device?.appVersion ?: "N/A")
        infoCard.addMenuItem(R.drawable.ic_settings, getString(R.string.device_detail_last_seen_label), {}, showDivider = false)
            .setTrailingText(dateFormat.format(Date(device?.lastSeenTs ?: 0)))

        padding.addView(infoCard)

        // ── 截图预览 ──
        val btnScreenshot = KButton(this).apply {
            text = getString(R.string.device_detail_screenshot_button)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp8 }
            setOnClickListener { captureScreenshot() }
        }
        padding.addView(btnScreenshot)

        ivScreenshot = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(300)).apply { bottomMargin = dp12 }
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.parseColor("#F5F5F5"))
            visibility = android.view.View.GONE
        }
        padding.addView(ivScreenshot)

        // ── 远程操作 ──
        controlGroup = MenuGroup(this).apply {
            setTitle(getString(R.string.screen_cast_remote_control_title))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp12 }
        }
        controlGroup.addMenuItem(R.drawable.ic_settings, "Home", { sendAction("home") }, showDivider = true)
        controlGroup.addMenuItem(R.drawable.ic_back, "Back", { sendAction("back") }, showDivider = true)
        controlGroup.addMenuItem(R.drawable.ic_settings, getString(R.string.tool_name_input_text), { showInputDialog() }, showDivider = true)
        controlGroup.addMenuItem(R.drawable.ic_settings, "Recent Apps", { sendAction("recent") }, showDivider = false)
        padding.addView(controlGroup)

        // ── 在外接屏启动 ──
        val castGroup = MenuGroup(this).apply {
            setTitle(getString(R.string.screen_cast_launch_external_title))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp12 }
        }
        castGroup.addMenuItem(R.drawable.ic_settings, getString(R.string.menu_wechat), { launchOnCast("com.tencent.mm") }, showDivider = true)
        castGroup.addMenuItem(R.drawable.ic_settings, getString(R.string.discover_shortcut_browser), { launchOnCast("com.android.chrome") }, showDivider = true)
        castGroup.addMenuItem(R.drawable.ic_settings, getString(R.string.screen_cast_custom_package_menu), { showPackageInputDialog() }, showDivider = false)
        padding.addView(castGroup)

        content.addView(padding)
        scrollView.addView(content)
        return scrollView
    }

    private fun bindDevice() {
        val d = device ?: return
        tvStatus.text = if (d.online) getString(R.string.device_detail_status_online) else getString(R.string.device_detail_status_offline)
        tvStatus.setTextColor(if (d.online) Color.parseColor("#4CAF50") else Color.GRAY)
    }

    private fun captureScreenshot() {
        val d = device ?: return
        lifecycleScope.launch {
            val jpeg = remoteControl.captureScreenshot(d)
            if (jpeg != null && jpeg.isNotEmpty()) {
                val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                ivScreenshot.setImageBitmap(bmp)
                ivScreenshot.visibility = android.view.View.VISIBLE
            } else {
                Toast.makeText(this@DeviceDetailActivity, getString(R.string.device_detail_screenshot_failed_toast), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun sendAction(action: String) {
        val d = device ?: return
        lifecycleScope.launch {
            val ok = when (action) {
                "home" -> remoteControl.pressHome(d)
                "back" -> remoteControl.pressBack(d)
                "recent" -> remoteControl.sendKey(d, 187) // KEYCODE_APP_SWITCH
                else -> false
            }
            if (!ok) Toast.makeText(this@DeviceDetailActivity, getString(R.string.device_detail_operation_failed_toast), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showInputDialog() {
        val et = EditText(this).apply {
            hint = getString(R.string.tool_name_input_text)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.device_detail_remote_input_dialog_title))
            .setView(et)
            .setPositiveButton(getString(R.string.screen_cast_send_button)) { _, _ ->
                val text = et.text.toString()
                if (text.isNotEmpty()) {
                    val d = device ?: return@setPositiveButton
                    lifecycleScope.launch { remoteControl.sendText(d, text) }
                }
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show()
    }

    private fun launchOnCast(packageName: String) {
        val castService = ScreenCastService.getInstance(this)
        val ok = castService.launchAppOnExternalDisplay(packageName)
        Toast.makeText(this, if (ok) getString(R.string.device_detail_launched_toast, packageName) else getString(R.string.device_detail_launch_failed_toast), Toast.LENGTH_SHORT).show()
    }

    private fun showPackageInputDialog() {
        val et = EditText(this).apply {
            hint = "com.example.app"
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.screen_cast_enter_package_name))
            .setView(et)
            .setPositiveButton(getString(R.string.screen_cast_launch_button)) { _, _ ->
                val pkg = et.text.toString().trim()
                if (pkg.isNotEmpty()) launchOnCast(pkg)
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
