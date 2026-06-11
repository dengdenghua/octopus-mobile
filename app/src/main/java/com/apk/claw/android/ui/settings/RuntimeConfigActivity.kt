package com.apk.claw.android.ui.settings

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.apk.claw.android.R
import com.apk.claw.android.appViewModel
import com.apk.claw.android.base.BaseActivity
import com.apk.claw.android.octopus_mobile.ConnectionState
import com.apk.claw.android.utils.KVUtils
import com.apk.claw.android.utils.XLog
import com.apk.claw.android.widget.CommonToolbar
import com.apk.claw.android.widget.KButton
import kotlinx.coroutines.launch

/**
 * Octopus Runtime 连接配置页
 */
class RuntimeConfigActivity : BaseActivity() {

    companion object {
        private const val TAG = "RuntimeConfigActivity"
    }

    private lateinit var etRuntimeUrl: EditText
    private lateinit var etAuthToken: EditText
    private lateinit var rgBrainMode: RadioGroup
    private lateinit var rbLocal: RadioButton
    private lateinit var rbRemote: RadioButton
    private lateinit var rbDual: RadioButton
    private lateinit var tvStatus: TextView
    private lateinit var btnConnect: KButton
    private lateinit var btnDisconnect: KButton
    private lateinit var btnSave: KButton
    private lateinit var cbAutoConnect: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildLayout()
        loadConfig()
        observeConnectionState()
    }

    private fun buildLayout() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF5F5F5.toInt())
        }

        // Toolbar
        val toolbar = CommonToolbar(this)
        toolbar.id = View.generateViewId()
        toolbar.setTitle("Octopus Runtime")
        toolbar.showBackButton(true) { finish() }
        root.addView(toolbar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val scroll = ScrollView(this)
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 32)
        }
        scroll.addView(content)

        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }

        // ── Runtime URL ──
        content.addView(makeLabel("Runtime URL"))
        etRuntimeUrl = EditText(this).apply {
            hint = "ws://192.168.1.10:8765"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            setBackgroundColor(Color.WHITE)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        content.addView(etRuntimeUrl, makeFieldParams())

        // ── Auth Token ──
        content.addView(makeLabel("Auth Token (可选)"))
        etAuthToken = EditText(this).apply {
            hint = "留空则无认证"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
            setBackgroundColor(Color.WHITE)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        content.addView(etAuthToken, makeFieldParams())

        // ── Brain Mode ──
        content.addView(makeLabel("启动模式"))
        rgBrainMode = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        rbLocal = RadioButton(this).apply { text = "LOCAL_ONLY — 纯本地 Agent" }
        rbRemote = RadioButton(this).apply { text = "RPC_ONLY — 纯远程 (依赖母体)" }
        rbDual = RadioButton(this).apply { text = "DUAL — 远程优先，离线降级" }
        rbLocal.id = View.generateViewId()
        rbRemote.id = View.generateViewId()
        rbDual.id = View.generateViewId()
        rgBrainMode.addView(rbLocal)
        rgBrainMode.addView(rbRemote)
        rgBrainMode.addView(rbDual)
        content.addView(rgBrainMode, makeFieldParams())

        // ── Auto Connect ──
        cbAutoConnect = CheckBox(this).apply {
            text = "启动时自动连接"
            setBackgroundColor(Color.WHITE)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        content.addView(cbAutoConnect, makeFieldParams())

        // ── 连接状态 ──
        content.addView(makeLabel("连接状态"))
        tvStatus = TextView(this).apply {
            text = "DISCONNECTED"
            textSize = 16f
            typeface = Typeface.MONOSPACE
            setTextColor(0xFF888888.toInt())
            setBackgroundColor(Color.WHITE)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            gravity = Gravity.CENTER
        }
        content.addView(tvStatus, makeFieldParams())

        // ── 按钮行 ──
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, dp(8))
        }
        btnConnect = KButton(this).apply {
            text = "连接"
            setBackgroundColor(0xFF1F6FEB.toInt())
            setTextColor(Color.WHITE)
        }
        btnDisconnect = KButton(this).apply {
            text = "断开"
            setBackgroundColor(0xFFDA3633.toInt())
            setTextColor(Color.WHITE)
        }
        val halfParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply {
            setMargins(dp(8), dp(4), dp(8), dp(4))
        }
        btnRow.addView(btnConnect, halfParams)
        btnRow.addView(btnDisconnect, halfParams)
        content.addView(btnRow)

        // ── Save ──
        btnSave = KButton(this).apply {
            text = "保存配置"
            setBackgroundColor(0xFF238636.toInt())
            setTextColor(Color.WHITE)
        }
        content.addView(btnSave, LinearLayout.LayoutParams(MATCH_PARENT, dp(44)).apply {
            setMargins(0, dp(16), 0, dp(16))
        })

        setContentView(root)

        // ── 事件 ──
        btnConnect.setOnClickListener { onConnect() }
        btnDisconnect.setOnClickListener { onDisconnect() }
        btnSave.setOnClickListener { onSave() }
    }

    private fun makeLabel(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(0xFF666666.toInt())
            setPadding(0, (24 * resources.displayMetrics.density).toInt(), 0,
                (8 * resources.displayMetrics.density).toInt())
        }
    }

    private fun makeFieldParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            bottomMargin = (4 * resources.displayMetrics.density).toInt()
        }
    }

    private fun loadConfig() {
        etRuntimeUrl.setText(KVUtils.getOctopusRpcUrl())
        etAuthToken.setText(KVUtils.getOctopusAuthToken())
        cbAutoConnect.isChecked = KVUtils.isOctopusAutoConnect()

        when (KVUtils.getOctopusBrainMode()) {
            "LOCAL_ONLY" -> rgBrainMode.check(rbLocal.id)
            "RPC_ONLY" -> rgBrainMode.check(rbRemote.id)
            else -> rgBrainMode.check(rbDual.id)
        }
    }

    private fun observeConnectionState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                appViewModel.connectionState.collect { state ->
                    updateStatusUI(state)
                }
            }
        }
    }

    private fun updateStatusUI(state: ConnectionState) {
        val (text, color) = when (state) {
            ConnectionState.DISCONNECTED -> "DISCONNECTED" to 0xFF888888.toInt()
            ConnectionState.CONNECTING -> "CONNECTING..." to 0xFFFFA000.toInt()
            ConnectionState.CONNECTED -> "CONNECTED (waiting hello ack)" to 0xFFFFA000.toInt()
            ConnectionState.HELLO_SENT -> "HELLO_SENT (handshaking)" to 0xFFFFA000.toInt()
            ConnectionState.ONLINE -> "ONLINE ✓" to 0xFF2EA043.toInt()
            ConnectionState.RECONNECTING -> "RECONNECTING..." to 0xFFFFA000.toInt()
            ConnectionState.OFFLINE -> "OFFLINE" to 0xFFDA3633.toInt()
        }
        tvStatus.text = text
        tvStatus.setTextColor(color)
    }

    private fun onConnect() {
        val url = etRuntimeUrl.text.toString().trim()
        if (url.isEmpty()) {
            Toast.makeText(this, "请先填写 Runtime URL", Toast.LENGTH_SHORT).show()
            return
        }
        // 先保存再连接
        saveToStorage()
        appViewModel.connectRuntime()
    }

    private fun onDisconnect() {
        appViewModel.disconnectRuntime()
    }

    private fun onSave() {
        saveToStorage()
        Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show()
    }

    private fun saveToStorage() {
        KVUtils.setOctopusRpcUrl(etRuntimeUrl.text.toString().trim())
        KVUtils.setOctopusAuthToken(etAuthToken.text.toString().trim())
        KVUtils.setOctopusAutoConnect(cbAutoConnect.isChecked)

        val mode = when (rgBrainMode.checkedRadioButtonId) {
            rbLocal.id -> "LOCAL_ONLY"
            rbRemote.id -> "RPC_ONLY"
            else -> "DUAL"
        }
        KVUtils.setOctopusBrainMode(mode)
        XLog.i(TAG, "Config saved: url=${KVUtils.getOctopusRpcUrl()}, mode=$mode")
    }
}
