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
            setTitle(device?.deviceName ?: "设备详情")
            showBackButton(true) { finish() }
        })

        val padding = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp16, dp8, dp16, dp16)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }

        // ── 状态指示 ──
        tvStatus = TextView(this).apply {
            text = if (device?.online == true) "● 在线" else "○ 离线"
            setTextColor(if (device?.online == true) Color.parseColor("#4CAF50") else Color.GRAY)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp8)
        }
        padding.addView(tvStatus)

        // ── 信息卡片 ──
        val infoCard = MenuGroup(this).apply {
            setTitle("设备信息")
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp12 }
        }

        tvName = TextView(this).apply { textSize = 14f; setPadding(dp8, dp8, dp8, dp4) }
        tvIp = TextView(this).apply { textSize = 14f; setPadding(dp8, dp4, dp8, dp4) }
        tvAndroid = TextView(this).apply { textSize = 14f; setPadding(dp8, dp4, dp8, dp4) }
        tvApp = TextView(this).apply { textSize = 14f; setPadding(dp8, dp4, dp8, dp4) }
        tvLastSeen = TextView(this).apply { textSize = 14f; setPadding(dp8, dp4, dp8, dp8) }

        // Use MenuItems for info display
        infoCard.addMenuItem(R.drawable.ic_devices, "名称", {}, showDivider = true)
            .setTrailingText(device?.deviceName ?: "N/A")
        infoCard.addMenuItem(R.drawable.ic_runtime, "地址", {}, showDivider = true)
            .setTrailingText("${device?.ip}:${device?.configServerPort}")
        infoCard.addMenuItem(R.drawable.ic_settings, "Android", {}, showDivider = true)
            .setTrailingText(device?.androidVersion ?: "N/A")
        infoCard.addMenuItem(R.drawable.ic_settings, "App 版本", {}, showDivider = true)
            .setTrailingText(device?.appVersion ?: "N/A")
        infoCard.addMenuItem(R.drawable.ic_settings, "最后心跳", {}, showDivider = false)
            .setTrailingText(dateFormat.format(Date(device?.lastSeenTs ?: 0)))

        padding.addView(infoCard)

        // ── 截图预览 ──
        val btnScreenshot = KButton(this).apply {
            text = "截屏预览"
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
            setTitle("远程操作")
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp12 }
        }
        controlGroup.addMenuItem(R.drawable.ic_settings, "Home", { sendAction("home") }, showDivider = true)
        controlGroup.addMenuItem(R.drawable.ic_back, "Back", { sendAction("back") }, showDivider = true)
        controlGroup.addMenuItem(R.drawable.ic_settings, "输入文本", { showInputDialog() }, showDivider = true)
        controlGroup.addMenuItem(R.drawable.ic_settings, "Recent Apps", { sendAction("recent") }, showDivider = false)
        padding.addView(controlGroup)

        // ── 在外接屏启动 ──
        val castGroup = MenuGroup(this).apply {
            setTitle("在外接屏启动")
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp12 }
        }
        castGroup.addMenuItem(R.drawable.ic_settings, "微信", { launchOnCast("com.tencent.mm") }, showDivider = true)
        castGroup.addMenuItem(R.drawable.ic_settings, "浏览器", { launchOnCast("com.android.chrome") }, showDivider = true)
        castGroup.addMenuItem(R.drawable.ic_settings, "自定义包名", { showPackageInputDialog() }, showDivider = false)
        padding.addView(castGroup)

        content.addView(padding)
        scrollView.addView(content)
        return scrollView
    }

    private fun bindDevice() {
        val d = device ?: return
        tvStatus.text = if (d.online) "● 在线" else "○ 离线"
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
                Toast.makeText(this@DeviceDetailActivity, "截图失败", Toast.LENGTH_SHORT).show()
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
            if (!ok) Toast.makeText(this@DeviceDetailActivity, "操作失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showInputDialog() {
        val et = EditText(this).apply {
            hint = "输入文本"
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("远程输入文本")
            .setView(et)
            .setPositiveButton("发送") { _, _ ->
                val text = et.text.toString()
                if (text.isNotEmpty()) {
                    val d = device ?: return@setPositiveButton
                    lifecycleScope.launch { remoteControl.sendText(d, text) }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun launchOnCast(packageName: String) {
        val castService = ScreenCastService.getInstance(this)
        val ok = castService.launchAppOnExternalDisplay(packageName)
        Toast.makeText(this, if (ok) "已启动 $packageName" else "启动失败（需先投屏）", Toast.LENGTH_SHORT).show()
    }

    private fun showPackageInputDialog() {
        val et = EditText(this).apply {
            hint = "com.example.app"
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("输入包名")
            .setView(et)
            .setPositiveButton("启动") { _, _ ->
                val pkg = et.text.toString().trim()
                if (pkg.isNotEmpty()) launchOnCast(pkg)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
