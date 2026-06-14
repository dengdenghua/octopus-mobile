package com.apk.claw.android.ui.device

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.R
import com.apk.claw.android.base.BaseActivity
import com.apk.claw.android.octopus_mobile.DeviceInfo
import com.apk.claw.android.octopus_mobile.DeviceRemoteControl
import com.apk.claw.android.widget.CommonToolbar
import com.apk.claw.android.widget.KButton
import com.apk.claw.android.widget.MenuGroup
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * 局域网设备列表页面
 */
class DeviceListActivity : BaseActivity() {

    companion object {
        private const val TAG = "DeviceListActivity"
    }

    private val registry get() = ClawApplication.instance.deviceRegistry
    private val discoveryManager get() = ClawApplication.instance.deviceDiscoveryManager
    private val remoteControl = DeviceRemoteControl()

    private lateinit var batchGroup: MenuGroup
    private lateinit var onlineGroup: MenuGroup
    private lateinit var offlineGroup: MenuGroup
    private lateinit var tvEmpty: TextView
    private lateinit var btnRefresh: KButton
    private var onlineDevices: List<DeviceInfo> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = buildLayout()
        setContentView(root)
        observeDevices()
        // 自动启动发现
        if (!discoveryManager.isRunning()) {
            discoveryManager.start()
        }
    }

    override fun onResume() {
        super.onResume()
        // 刷新时重新触发发现
        if (!discoveryManager.isRunning()) {
            discoveryManager.start()
        }
    }

    private fun buildLayout(): LinearLayout {
        val dp16 = dp(16)
        val dp8 = dp(8)

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)

            // Toolbar
            addView(CommonToolbar(this@DeviceListActivity).apply {
                setTitle(getString(R.string.device_list_toolbar_title))
                showBackButton(true) { finish() }
            })

            // ScrollView
            val scrollView = ScrollView(this@DeviceListActivity).apply {
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
                isFillViewport = true
            }
            val scrollContent = LinearLayout(this@DeviceListActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                setPadding(dp16, dp8, dp16, dp16)
            }

            // 群控快捷操作
            batchGroup = MenuGroup(this@DeviceListActivity).apply {
                setTitle(getString(R.string.device_batch_group_title))
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    bottomMargin = dp8
                }
            }
            batchGroup.addMenuItem(R.drawable.ic_settings, getString(R.string.device_batch_home), { batchAction("home") }, showDivider = true)
            batchGroup.addMenuItem(R.drawable.ic_back, getString(R.string.device_batch_back), { batchAction("back") }, showDivider = true)
            batchGroup.addMenuItem(R.drawable.ic_settings, getString(R.string.device_batch_recent), { batchAction("recent") }, showDivider = true)
            batchGroup.addMenuItem(R.drawable.ic_settings, getString(R.string.device_batch_input_text), { showBatchTextDialog() }, showDivider = true)
            batchGroup.addMenuItem(R.drawable.ic_settings, getString(R.string.device_batch_open_app), { showBatchPackageDialog() }, showDivider = false)
            scrollContent.addView(batchGroup)

            // 在线设备
            onlineGroup = MenuGroup(this@DeviceListActivity).apply {
                setTitle(getString(R.string.device_list_online_group_title))
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    bottomMargin = dp8
                }
            }
            scrollContent.addView(onlineGroup)

            // 离线设备
            offlineGroup = MenuGroup(this@DeviceListActivity).apply {
                setTitle(getString(R.string.device_list_offline_group_title))
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    bottomMargin = dp8
                }
            }
            scrollContent.addView(offlineGroup)

            // 空状态提示
            tvEmpty = TextView(this@DeviceListActivity).apply {
                text = getString(R.string.device_list_empty_state_text)
                textSize = 14f
                setTextColor(Color.GRAY)
                gravity = Gravity.CENTER
                setPadding(0, dp(40), 0, dp(40))
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            }
            scrollContent.addView(tvEmpty)

            // 刷新按钮
            btnRefresh = KButton(this@DeviceListActivity).apply {
                text = getString(R.string.device_list_refresh_button_text)
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    topMargin = dp8
                }
                setOnClickListener {
                    if (!discoveryManager.isRunning()) {
                        discoveryManager.start()
                    }
                    // 强制刷新
                    registry.markStaleOffline()
                }
            }
            scrollContent.addView(btnRefresh)

            scrollView.addView(scrollContent)
            addView(scrollView)
        }
    }

    private fun observeDevices() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                registry.deviceList.collect { devices ->
                    updateDeviceList(devices)
                }
            }
        }
    }

    private fun updateDeviceList(devices: List<DeviceInfo>) {
        onlineGroup.clearMenuItems()
        offlineGroup.clearMenuItems()

        onlineDevices = devices.filter { it.online }
        val offlineDevices = devices.filter { !it.online }
        batchGroup.visibility = if (onlineDevices.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE

        for (device in onlineDevices) {
            onlineGroup.addMenuItem(
                leadingIcon = R.drawable.ic_device_online,
                title = device.deviceName,
                onClick = { openDetail(device.deviceId) },
                showDivider = true
            ).apply {
                setTrailingText("${device.ip}:${device.configServerPort}")
            }
        }

        for (device in offlineDevices) {
            offlineGroup.addMenuItem(
                leadingIcon = R.drawable.ic_device_offline,
                title = device.deviceName,
                onClick = { openDetail(device.deviceId) },
                showDivider = true
            ).apply {
                setTrailingText("${device.ip}:${device.configServerPort}")
            }
        }

        tvEmpty.visibility = if (devices.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun openDetail(deviceId: String) {
        val intent = Intent(this, DeviceDetailActivity::class.java).apply {
            putExtra(DeviceDetailActivity.EXTRA_DEVICE_ID, deviceId)
        }
        startActivity(intent)
    }

    private fun batchAction(action: String) {
        val targets = onlineDevices
        if (targets.isEmpty()) {
            toast(getString(R.string.device_batch_no_online))
            return
        }
        lifecycleScope.launch {
            val results = targets.map { device ->
                async {
                    when (action) {
                        "home" -> remoteControl.pressHome(device)
                        "back" -> remoteControl.pressBack(device)
                        "recent" -> remoteControl.pressRecents(device)
                        else -> false
                    }
                }
            }.map { it.await() }
            showBatchResult(results.count { it }, targets.size)
        }
    }

    private fun showBatchTextDialog() {
        val et = EditText(this).apply {
            hint = getString(R.string.tool_name_input_text)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.device_batch_input_text))
            .setView(et)
            .setPositiveButton(getString(R.string.screen_cast_send_button)) { _, _ ->
                val text = et.text.toString()
                if (text.isNotEmpty()) batchText(text)
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show()
    }

    private fun batchText(text: String) {
        val targets = onlineDevices
        if (targets.isEmpty()) {
            toast(getString(R.string.device_batch_no_online))
            return
        }
        lifecycleScope.launch {
            val results = targets.map { device ->
                async { remoteControl.sendText(device, text) }
            }.map { it.await() }
            showBatchResult(results.count { it }, targets.size)
        }
    }

    private fun showBatchPackageDialog() {
        val et = EditText(this).apply {
            hint = "com.tencent.mm"
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.device_batch_open_app))
            .setView(et)
            .setPositiveButton(getString(R.string.screen_cast_launch_button)) { _, _ ->
                val pkg = et.text.toString().trim()
                if (pkg.isNotEmpty()) batchOpenApp(pkg)
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show()
    }

    private fun batchOpenApp(packageName: String) {
        val targets = onlineDevices
        if (targets.isEmpty()) {
            toast(getString(R.string.device_batch_no_online))
            return
        }
        lifecycleScope.launch {
            val results = targets.map { device ->
                async { remoteControl.openApp(device, packageName) }
            }.map { it.await() }
            showBatchResult(results.count { it }, targets.size)
        }
    }

    private fun showBatchResult(success: Int, total: Int) {
        toast(getString(R.string.device_batch_result, success, total))
    }

    private fun toast(text: String) {
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
