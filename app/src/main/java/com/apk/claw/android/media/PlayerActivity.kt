package com.apk.claw.android.media

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.apk.claw.android.base.BaseActivity
import com.apk.claw.android.utils.XLog

/**
 * mpv 播放界面 —— 支持 TV D-pad 和手机触控双操作模式。
 *
 * D-pad 快捷键：
 * - OK/Enter：暂停/恢复
 * - Left：快退 10 秒
 * - Right：快进 10 秒
 * - Down：快退 60 秒
 * - Up：快进 60 秒
 * - Menu/Back：退出播放器
 * - Volume Up/Down：调节音量
 *
 * 启动方式：
 * - Intent: PlayerActivity.intent(context, path, subtitlePath, startPositionMs)
 * - Agent: play_media tool
 */
class PlayerActivity : BaseActivity() {

    companion object {
        private const val TAG = "PlayerActivity"
        const val EXTRA_PATH = "path"
        const val EXTRA_SUBTITLE = "subtitle"
        const val EXTRA_START_MS = "start_ms"
        const val EXTRA_TITLE = "title"

        fun intent(
            context: android.content.Context,
            path: String,
            subtitlePath: String? = null,
            startPositionMs: Long = 0,
            title: String? = null
        ): Intent {
            return Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_PATH, path)
                subtitlePath?.let { putExtra(EXTRA_SUBTITLE, it) }
                putExtra(EXTRA_START_MS, startPositionMs)
                title?.let { putExtra(EXTRA_TITLE, it) }
            }
        }
    }

    private lateinit var surfaceView: SurfaceView
    private lateinit var osdText: TextView   // 屏幕显示文字（进度/音量）
    private var osdHideRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 全屏 + 保持屏幕常亮
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        // 创建播放界面
        setupUI()

        // 初始化 mpv
        MpvController.initialize(applicationContext)
        MpvController.attachSurfaceView(surfaceView)

        // 注册播放回调
        MpvController.onPositionChanged = { pos, dur ->
            runOnUiThread { updateOSD(pos, dur) }
        }
        MpvController.onPlaybackEnd = {
            runOnUiThread {
                XLog.i(TAG, "Playback ended, finishing activity")
                finish()
            }
        }

        // 开始播放
        val path = intent.getStringExtra(EXTRA_PATH)
        val subtitle = intent.getStringExtra(EXTRA_SUBTITLE)
        val startMs = intent.getLongExtra(EXTRA_START_MS, 0)

        if (path.isNullOrEmpty()) {
            XLog.e(TAG, "No path provided")
            finish()
            return
        }

        MpvController.play(path, subtitle, startMs)
        showOSD("Playing: ${path.substringAfterLast('/')}")
    }

    /**
     * 创建播放界面布局（纯代码，不依赖 XML）。
     */
    private fun setupUI() {
        val frame = android.widget.FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
        }

        // 视频渲染 SurfaceView
        surfaceView = SurfaceView(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        frame.addView(surfaceView)

        // OSD 文字覆盖层
        osdText = TextView(this).apply {
            setTextColor(android.graphics.Color.WHITE)
            textSize = 18f
            setShadowLayer(4f, 0f, 0f, android.graphics.Color.BLACK)
            setPadding(40, 40, 40, 40)
            visibility = View.GONE
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
            ).apply {
                bottomMargin = 80
            }
        }
        frame.addView(osdText)

        setContentView(frame)
    }

    // ======================== D-pad 按键处理 ========================

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            // 暂停/恢复
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                MpvController.togglePause()
                val playing = MpvController.isPlaying()
                showOSD(if (playing) "▶ Playing" else "⏸ Paused")
                true
            }

            // 快进 10 秒
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                MpvController.seekRelative(10_000)
                showProgress()
                true
            }

            // 快退 10 秒
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                MpvController.seekRelative(-10_000)
                showProgress()
                true
            }

            // 快进 60 秒
            KeyEvent.KEYCODE_DPAD_UP -> {
                MpvController.seekRelative(60_000)
                showProgress()
                true
            }

            // 快退 60 秒
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                MpvController.seekRelative(-60_000)
                showProgress()
                true
            }

            // 音量增加
            KeyEvent.KEYCODE_VOLUME_UP -> {
                val vol = (MpvController.getVolume() + 5).coerceAtMost(150)
                MpvController.setVolume(vol)
                showOSD("🔊 $vol%")
                true
            }

            // 音量减少
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                val vol = (MpvController.getVolume() - 5).coerceAtLeast(0)
                MpvController.setVolume(vol)
                showOSD("🔊 $vol%")
                true
            }

            // 退出
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_MENU -> {
                MpvController.stop()
                finish()
                true
            }

            // 切换字幕（按字幕键 / M 键）
            KeyEvent.KEYCODE_CAPTIONS, KeyEvent.KEYCODE_M -> {
                // 循环切换字幕轨道
                cycleSubtitle()
                true
            }

            // 切换音轨（按 A 键）
            KeyEvent.KEYCODE_A -> {
                cycleAudioTrack()
                true
            }

            else -> super.onKeyDown(keyCode, event)
        }
    }

    // ======================== OSD 显示 ========================

    private fun showOSD(text: String) {
        osdText.text = text
        osdText.visibility = View.VISIBLE
        scheduleOSDHide()
    }

    private fun showProgress() {
        val pos = MpvController.getPositionMs()
        val dur = MpvController.getDurationMs()
        updateOSD(pos, dur)
    }

    private fun updateOSD(posMs: Long, durMs: Long) {
        val posStr = formatTime(posMs)
        val durStr = formatTime(durMs)
        val progress = if (durMs > 0) (posMs * 100 / durMs) else 0
        osdText.text = "$posStr / $durStr  [$progress%]"
        osdText.visibility = View.VISIBLE
        scheduleOSDHide()
    }

    private fun scheduleOSDHide() {
        osdHideRunnable?.let { osdText.removeCallbacks(it) }
        osdHideRunnable = Runnable { osdText.visibility = View.GONE }
        osdText.postDelayed(osdHideRunnable, 3000)
    }

    // ======================== 轨道循环切换 ========================

    private var currentSubTrack = 1
    private var currentAudioTrack = 1

    private fun cycleSubtitle() {
        currentSubTrack = if (currentSubTrack >= 3) 0 else currentSubTrack + 1
        MpvController.setSubtitleTrack(currentSubTrack)
        showOSD(if (currentSubTrack == 0) "Subtitle: OFF" else "Subtitle: Track $currentSubTrack")
    }

    private fun cycleAudioTrack() {
        currentAudioTrack = if (currentAudioTrack >= 4) 1 else currentAudioTrack + 1
        MpvController.setAudioTrack(currentAudioTrack)
        showOSD("Audio: Track $currentAudioTrack")
    }

    // ======================== 触控支持（手机模式）========================

    private var lastTapTime = 0L

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.action == android.view.MotionEvent.ACTION_UP) {
            val now = System.currentTimeMillis()
            if (now - lastTapTime < 300) {
                // 双击：暂停/恢复
                MpvController.togglePause()
                showOSD(if (MpvController.isPlaying()) "▶ Playing" else "⏸ Paused")
            } else {
                // 单击：显示进度
                showProgress()
            }
            lastTapTime = now
        }
        return super.dispatchTouchEvent(ev)
    }

    // ======================== 生命周期 ========================

    override fun onPause() {
        super.onPause()
        // 后台暂停
        if (MpvController.isPlaying()) {
            MpvController.pause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        MpvController.stop()
    }

    // ======================== 工具方法 ========================

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        val hours = totalSec / 3600
        val minutes = (totalSec % 3600) / 60
        val seconds = totalSec % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
        else "%d:%02d".format(minutes, seconds)
    }
}
