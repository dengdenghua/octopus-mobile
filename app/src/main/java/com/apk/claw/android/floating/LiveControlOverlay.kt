package com.apk.claw.android.floating

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.R
import com.lzf.easyfloat.EasyFloat
import com.lzf.easyfloat.enums.ShowPattern
import com.lzf.easyfloat.enums.SidePattern
import com.lzf.easyfloat.interfaces.OnFloatCallbacks
import android.view.MotionEvent

/**
 * 实时控制层 —— Agent 操作手机时，悬浮在所有 App 之上显示「当前步骤 + 停止」。
 *
 * 这是「对话即操作」体验的信任命门：用户随时看得见 Agent 在做什么，并能一键叫停，
 * 即使 Agent 已经跳出本 App 去操作微信/浏览器等。
 *
 * 由 [com.apk.claw.android.ui.compose.screen.ChatAgentBridge] 在任务生命周期内驱动。
 */
object LiveControlOverlay {

    private const val TAG = "live_control_float"
    private val main = Handler(Looper.getMainLooper())
    private var showing = false
    private var onStop: (() -> Unit)? = null

    private val cBg = Color.parseColor("#1C1C1E")
    private val cBorder = Color.parseColor("#38383A")
    private val cText = Color.parseColor("#FFFFFF")
    private val cPrimary = Color.parseColor("#0A84FF")
    private val cDanger = Color.parseColor("#FF453B")
    private val cSuccess = Color.parseColor("#30D158")

    private fun dp(v: Int): Int = (v * android.content.res.Resources.getSystem().displayMetrics.density).toInt()

    /** 开始一次任务：显示控制条。onStop 在用户点「停止」时回调。 */
    fun show(step: String, onStop: () -> Unit) {
        this.onStop = onStop
        val app = ClawApplication.instance
        if (!Settings.canDrawOverlays(app)) return  // 无悬浮窗权限则静默跳过（对话页内仍有步骤显示）
        main.post {
            if (showing) {
                applyStep(step, running = true)
                return@post
            }
            EasyFloat.with(app)
                .setLayout(R.layout.layout_live_control)
                .setShowPattern(ShowPattern.ALL_TIME)
                .setSidePattern(SidePattern.DEFAULT)
                .setGravity(Gravity.CENTER_HORIZONTAL or Gravity.TOP, 0, dp(64))
                .setDragEnable(true)
                .hasEditText(false)
                .setTag(TAG)
                .registerCallbacks(object : OnFloatCallbacks {
                    override fun createdResult(isCreated: Boolean, msg: String?, view: View?) {
                        view ?: return
                        view.findViewById<View>(R.id.lcRoot)?.background = GradientDrawable().apply {
                            setColor(cBg)
                            cornerRadius = dp(22).toFloat()
                            setStroke(dp(1), cBorder)
                        }
                        view.findViewById<TextView>(R.id.lcStep)?.setTextColor(cText)
                        view.findViewById<ProgressBar>(R.id.lcSpinner)?.indeterminateTintList =
                            android.content.res.ColorStateList.valueOf(cPrimary)
                        view.findViewById<TextView>(R.id.lcStop)?.apply {
                            setTextColor(cDanger)
                            background = GradientDrawable().apply {
                                setColor(Color.argb(40, 255, 69, 59))
                                cornerRadius = dp(16).toFloat()
                            }
                            setOnClickListener {
                                this@LiveControlOverlay.onStop?.invoke()
                                hide()
                            }
                        }
                        applyStep(step, running = true)
                    }

                    override fun dismiss() { showing = false }
                    override fun hide(view: View) { showing = false }
                    override fun show(view: View) { showing = true }
                    override fun drag(view: View, event: MotionEvent) {}
                    override fun dragEnd(view: View) {}
                    override fun touchEvent(view: View, event: MotionEvent) {}
                })
                .show()
            showing = true
        }
    }

    /** 更新当前步骤文案。 */
    fun updateStep(step: String) {
        main.post { applyStep(step, running = true) }
    }

    /** 任务结束：短暂显示结果后自动收起。 */
    fun finish(success: Boolean, text: String) {
        main.post {
            applyStep((if (success) "✓ " else "✗ ") + text, running = false, tint = if (success) cSuccess else cDanger)
            main.postDelayed({ hide() }, 1600)
        }
    }

    fun hide() {
        if (showing) {
            runCatching { EasyFloat.dismiss(TAG) }
            showing = false
        }
    }

    private fun applyStep(step: String, running: Boolean, tint: Int = cPrimary) {
        val view = EasyFloat.getFloatView(TAG) ?: return
        view.findViewById<TextView>(R.id.lcStep)?.text = step
        view.findViewById<ProgressBar>(R.id.lcSpinner)?.visibility = if (running) View.VISIBLE else View.GONE
        if (!running) {
            view.findViewById<TextView>(R.id.lcStep)?.setTextColor(tint)
        } else {
            view.findViewById<TextView>(R.id.lcStep)?.setTextColor(cText)
        }
    }
}
