package com.apk.claw.android.ui.media

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.apk.claw.android.R
import com.apk.claw.android.account.AccountStore
import com.apk.claw.android.base.BaseActivity
import com.apk.claw.android.media.MediaRepository
import com.apk.claw.android.media.VideoTask
import com.apk.claw.android.ui.account.LoginActivity
import com.apk.claw.android.widget.CommonToolbar
import com.apk.claw.android.widget.KButton
import com.bumptech.glide.Glide
import kotlinx.coroutines.launch

/**
 * 生图 / 生视频(Agnes 增值)。输入提示词 → 调 [MediaRepository] 走服务端中转端点。
 * 会员免费 / 非会员扣积分 / 视频配额都由服务端处理;本页只负责发起、显示结果、把服务端
 * 友好错误(积分不足 / 繁忙等)toast 出来。生图同步秒回;生视频异步,边轮询边更新进度。
 */
class MediaActivity : BaseActivity() {

    private lateinit var etPrompt: EditText
    private lateinit var tvStatus: TextView
    private lateinit var ivResult: ImageView
    private lateinit var btnGenImage: KButton
    private lateinit var btnGenVideo: KButton
    private lateinit var btnOpenVideo: KButton
    private var lastVideoUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AccountStore.isLoggedIn) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_media)

        findViewById<CommonToolbar>(R.id.toolbar).apply {
            setTitle("生图 / 生视频")
            showBackButton(true) { finish() }
        }
        etPrompt = findViewById(R.id.etPrompt)
        tvStatus = findViewById(R.id.tvStatus)
        ivResult = findViewById(R.id.ivResult)
        btnGenImage = findViewById(R.id.btnGenImage)
        btnGenVideo = findViewById(R.id.btnGenVideo)
        btnOpenVideo = findViewById(R.id.btnOpenVideo)

        btnGenImage.setOnClickListener { genImage() }
        btnGenVideo.setOnClickListener { genVideo() }
        btnOpenVideo.setOnClickListener {
            lastVideoUrl?.let { url ->
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    .onFailure { toast("无法打开视频") }
            }
        }
    }

    private fun prompt(): String = etPrompt.text.toString().trim()

    private fun setBusy(busy: Boolean) {
        btnGenImage.isEnabled = !busy
        btnGenVideo.isEnabled = !busy
    }

    private fun genImage() {
        val p = prompt()
        if (p.isEmpty()) {
            toast("请先输入提示词")
            return
        }
        setBusy(true)
        ivResult.visibility = View.GONE
        btnOpenVideo.visibility = View.GONE
        tvStatus.text = "正在生成图片…"
        lifecycleScope.launch {
            MediaRepository.generateImage(p)
                .onSuccess { img ->
                    tvStatus.text = "图片已生成"
                    ivResult.visibility = View.VISIBLE
                    Glide.with(this@MediaActivity).load(img.url).into(ivResult)
                }
                .onFailure {
                    tvStatus.text = ""
                    toast(it.message ?: "生成失败")
                }
            setBusy(false)
        }
    }

    private fun genVideo() {
        val p = prompt()
        if (p.isEmpty()) {
            toast("请先输入提示词")
            return
        }
        setBusy(true)
        ivResult.visibility = View.GONE
        btnOpenVideo.visibility = View.GONE
        lastVideoUrl = null
        tvStatus.text = "正在提交视频任务…"
        lifecycleScope.launch {
            MediaRepository.generateVideo(p) { task -> tvStatus.text = videoStatusText(task) }
                .onSuccess { task ->
                    val url = task.url
                    if (!url.isNullOrEmpty()) {
                        lastVideoUrl = url
                        tvStatus.text = "视频已生成"
                        btnOpenVideo.visibility = View.VISIBLE
                    } else {
                        tvStatus.text = "视频已生成(任务 ${task.taskId})"
                    }
                }
                .onFailure {
                    tvStatus.text = ""
                    toast(it.message ?: "视频生成失败")
                }
            setBusy(false)
        }
    }

    private fun videoStatusText(t: VideoTask): String = when {
        t.isDone -> "视频生成完成"
        t.isFailed -> "视频生成失败"
        t.progress > 0 -> "视频生成中… ${t.progress}%"
        else -> "视频排队/生成中…(${t.status})"
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
