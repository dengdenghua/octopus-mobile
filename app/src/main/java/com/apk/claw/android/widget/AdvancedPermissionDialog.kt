package com.apk.claw.android.widget

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.apk.claw.android.R
import com.apk.claw.android.shizuku.ShizukuManager

/**
 * 高级权限引导弹窗.
 *
 * 入口：首页"高级权限"卡片点击.
 * 状态机：
 *  - Shizuku 已就绪    → 仅展示成功状态 + 关闭按钮
 *  - 已装但未授权     → 提示授权 + "打开 Shizuku" 主按钮
 *  - 未安装           → "安装 Shizuku" 主按钮（跳官网下载页）
 *
 * "了解更多" 始终展示官方文档.
 */
class AdvancedPermissionDialog private constructor(context: Context) :
    Dialog(context, R.style.DialogStyle) {

    companion object {
        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

        @JvmStatic
        fun show(context: Context): AdvancedPermissionDialog =
            AdvancedPermissionDialog(context).apply { show() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_advanced_permission)
        setCancelable(true)
        setCanceledOnTouchOutside(true)

        window?.apply {
            setGravity(Gravity.CENTER)
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(
                (context.resources.displayMetrics.widthPixels * 0.88).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT
            )
        }

        bindState()
    }

    private fun bindState() {
        val tvStatus = findViewById<TextView>(R.id.tvAdvancedStatus)
        val tvSteps = findViewById<TextView>(R.id.tvAdvancedSteps)
        val btnPrimary = findViewById<KButton>(R.id.btnAdvancedPrimary)
        val btnLearnMore = findViewById<KButton>(R.id.btnAdvancedLearnMore)
        val btnClose = findViewById<KButton>(R.id.btnAdvancedClose)

        val installed = ShizukuManager.isShizukuInstalled(context.packageManager)
        val ready = ShizukuManager.isAvailable()

        when {
            ready -> {
                tvStatus.setText(R.string.advanced_dialog_status_ready)
                tvStatus.setTextColor(ContextCompat.getColor(context, R.color.colorSuccessPrimary))
                tvSteps.visibility = View.GONE
                btnPrimary.visibility = View.GONE
            }
            installed -> {
                tvStatus.setText(R.string.advanced_dialog_status_installed_no_perm)
                tvStatus.setTextColor(ContextCompat.getColor(context, R.color.colorTextSecondary))
                btnPrimary.setText(R.string.advanced_action_open_shizuku)
                btnPrimary.setOnClickListener {
                    openShizukuApp()
                    dismiss()
                }
            }
            else -> {
                tvStatus.setText(R.string.advanced_dialog_status_not_installed)
                tvStatus.setTextColor(ContextCompat.getColor(context, R.color.colorTextSecondary))
                btnPrimary.setText(R.string.advanced_action_install)
                btnPrimary.setOnClickListener {
                    openInBrowser(context.getString(R.string.advanced_url_download))
                    dismiss()
                }
            }
        }

        btnLearnMore.setBgColor(ContextCompat.getColor(context, R.color.colorContainerBase))
        btnLearnMore.setTextColor(ContextCompat.getColor(context, R.color.colorTextSecondary))
        btnLearnMore.setBorderColor(ContextCompat.getColor(context, R.color.colorBorderBase))
        btnLearnMore.setOnClickListener {
            openInBrowser(context.getString(R.string.advanced_url_guide))
        }

        btnClose.setBgColor(ContextCompat.getColor(context, R.color.colorBgPrimary))
        btnClose.setTextColor(ContextCompat.getColor(context, R.color.colorTextTertiary))
        btnClose.setBorderColor(ContextCompat.getColor(context, R.color.colorBgPrimary))
        btnClose.setOnClickListener { dismiss() }
    }

    private fun openShizukuApp() {
        val pm = context.packageManager
        val launch = pm.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launch)
        } else {
            openInBrowser(context.getString(R.string.advanced_url_download))
        }
    }

    private fun openInBrowser(url: String) {
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}
