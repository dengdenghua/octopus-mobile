package com.apk.claw.android.ui.device

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.R
import com.apk.claw.android.base.BaseActivity
import com.apk.claw.android.octopus_mobile.DeviceInfo
import com.apk.claw.android.octopus_mobile.DeviceRemoteControl
import com.apk.claw.android.utils.KVUtils
import com.apk.claw.android.widget.MjpegImageView
import kotlinx.coroutines.launch
import java.net.URLEncoder
import kotlin.math.abs

/**
 * 横屏远程控制页(类 ToDesk)
 *
 * 全屏 [MjpegImageView] 实时画面 + 手势层:把视图坐标按 FIT_CENTER 映射回设备像素,
 * 经 [DeviceRemoteControl] 注入 tap / swipe / longPress;底部悬浮 返回/Home/多任务/退出。
 */
@Suppress("TooManyFunctions")   // Activity 视图搭建 + 手势 + 画质档,小函数天然多
class RemoteControlActivity : BaseActivity() {

    companion object {
        const val EXTRA_DEVICE_ID = "device_id"
        private const val MODE_KEY = "REMOTE_STREAM_MODE"
        private const val MODE_SMOOTH = "smooth"
        private const val MODE_SHARP = "sharp"
        fun start(ctx: Context, deviceId: String) {
            ctx.startActivity(Intent(ctx, RemoteControlActivity::class.java).putExtra(EXTRA_DEVICE_ID, deviceId))
        }
    }

    private val registry get() = ClawApplication.instance.deviceRegistry
    private val remote = DeviceRemoteControl()
    private lateinit var device: DeviceInfo
    private lateinit var mjpeg: MjpegImageView
    private var modeButton: TextView? = null

    // 设备真实分辨率(用于坐标映射);拿到前先用 0,映射时回退到画面比例
    private var devW = 0
    private var devH = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        val id = intent.getStringExtra(EXTRA_DEVICE_ID)
        val dev = id?.let { registry.getDevice(it) }
        if (dev == null) { finish(); return }
        device = dev

        setContentView(buildLayout())

        // 拉设备分辨率(失败不致命,映射回退到画面像素比例)
        lifecycleScope.launch {
            runCatching { remote.getScreenInfo(device) }.getOrNull()?.let { info ->
                devW = (info["width"] as? Number)?.toInt() ?: 0
                devH = (info["height"] as? Number)?.toInt() ?: 0
            }
        }
    }

    override fun onStart() {
        super.onStart()
        startStream()
    }

    /** 按当前画质档拼流地址并开播。token 由 [MjpegImageView] 自动转成 Authorization: Bearer 头。 */
    private fun startStream() {
        val token = URLEncoder.encode(device.authToken, "UTF-8")
        mjpeg.start("${device.getBaseUrl()}/api/screen/stream?${streamParams(streamMode())}&token=$token")
    }

    private fun streamMode(): String = KVUtils.getString(MODE_KEY, MODE_SMOOTH)

    /** 流畅=高帧率小画面(30fps);清晰=高分辨率高质量、帧率略降(24fps)。局域网带宽足,主要权衡编解码开销。 */
    private fun streamParams(mode: String): String =
        if (mode == MODE_SHARP) "quality=82&maxWidth=1080&fps=24" else "quality=60&maxWidth=720&fps=30"

    private fun modeLabel(): String = if (streamMode() == MODE_SHARP) "🔎 清晰" else "⚡ 流畅"

    private fun toggleMode() {
        KVUtils.putString(MODE_KEY, if (streamMode() == MODE_SHARP) MODE_SMOOTH else MODE_SHARP)
        modeButton?.text = modeLabel()
        mjpeg.stop()
        startStream()
    }

    override fun onStop() {
        super.onStop()
        mjpeg.stop()
    }

    private fun buildLayout(): FrameLayout {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        mjpeg = MjpegImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        }
        root.addView(mjpeg)

        // 手势层:盖在画面上,捕获 tap / swipe / longPress
        val touchPad = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        }
        attachGestures(touchPad)
        root.addView(touchPad)

        // 底部悬浮控制条
        root.addView(buildControlBar())
        return root
    }

    private fun buildControlBar(): LinearLayout {
        fun btn(label: String, onClick: () -> Unit) = TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(8), dp(18), dp(8))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.argb(150, 0, 0, 0)); cornerRadius = dp(18).toFloat()
            }
            isClickable = true
            setOnClickListener { onClick() }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dp(14)
            }
            val gap = dp(10)
            modeButton = btn(modeLabel()) { toggleMode() }
            addView(modeButton, lp(gap))
            addView(btn("‹ ${getString(R.string.remote_back)}") { act { remote.pressBack(device) } }, lp(gap))
            addView(btn("⌂ ${getString(R.string.remote_home)}") { act { remote.pressHome(device) } }, lp(gap))
            addView(btn("▭ ${getString(R.string.remote_recents)}") { act { remote.pressRecents(device) } }, lp(gap))
            addView(btn("✕ ${getString(R.string.remote_exit)}") { finish() }, lp(gap))
        }
    }

    private fun lp(gap: Int) = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = gap / 2; marginEnd = gap / 2 }

    private var downX = 0f
    private var downY = 0f
    private var downT = 0L

    private fun attachGestures(pad: View) {
        pad.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x; downY = e.y; downT = e.eventTime; true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = e.x - downX; val dy = e.y - downY
                    val dist = abs(dx) + abs(dy)
                    val dur = e.eventTime - downT
                    val s = mapPoint(downX, downY, v.width, v.height)
                    val t = mapPoint(e.x, e.y, v.width, v.height)
                    if (s != null && t != null) {
                        when {
                            dist < dp(8) && dur >= 500 -> act { remote.longPress(device, s.first, s.second) }
                            dist < dp(8) -> act { remote.tap(device, s.first, s.second) }
                            else -> act { remote.swipe(device, s.first, s.second, t.first, t.second, dur.coerceIn(80L, 1200L)) }
                        }
                    }
                    v.performClick(); true
                }
                else -> true
            }
        }
    }

    /** 视图坐标 → 设备像素(FIT_CENTER:画面在视图内等比居中,去掉黑边偏移)。 */
    private fun mapPoint(vx: Float, vy: Float, vw: Int, vh: Int): Pair<Int, Int>? {
        if (vw <= 0 || vh <= 0) return null
        val dw = if (devW > 0) devW else vw
        val dh = if (devH > 0) devH else vh
        val scale = minOf(vw.toFloat() / dw, vh.toFloat() / dh)
        val imgW = dw * scale; val imgH = dh * scale
        val offX = (vw - imgW) / 2f; val offY = (vh - imgH) / 2f
        val ix = vx - offX; val iy = vy - offY
        if (ix < 0 || iy < 0 || ix > imgW || iy > imgH) return null  // 点在黑边外
        return Pair((ix / scale).toInt(), (iy / scale).toInt())
    }

    private inline fun act(crossinline block: suspend () -> Unit) {
        lifecycleScope.launch { runCatching { block() } }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
