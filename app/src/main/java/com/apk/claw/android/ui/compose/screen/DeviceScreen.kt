package com.apk.claw.android.ui.compose.screen

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.items
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalContext
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.R
import com.apk.claw.android.octopus_mobile.DeviceInfo
import com.apk.claw.android.octopus_mobile.DeviceRemoteControl
import com.apk.claw.android.server.ConfigServerManager
import com.apk.claw.android.service.ClawAccessibilityService
import com.apk.claw.android.utils.KVUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val PrimaryColor = Color(0xFF0A84FF)
private val SuccessColor = Color(0xFF30D158)
private val WarningColor = Color(0xFFFF9F0A)
private val SurfaceColor = Color(0xFF1C1C1E)
private val SurfaceVariantColor = Color(0xFF2C2C2E)
private val BackgroundColor = Color(0xFF000000)
private val TextPrimary = Color(0xFFFFFFFF)
private val TextSecondary = Color(0xFF98989D)
private val TextMuted = Color(0xFF8E8E93)
private val BorderColor = Color(0xFF38383A)

/**
 * 设备页 —— 只展示与控制「本机」，全部数据真实：
 *  - 真实型号 / Android 版本 / 电量 / 局域网 IP
 *  - 控制按钮通过无障碍服务真实执行（截图 / 主屏 / 返回 / 最近 / 通知 / 锁屏）
 *  - 无障碍未开启时按钮置灰并给出开启入口
 * 已移除：局域网多设备发现、投屏、多窗口、浏览器卡片（均无真实后端）。
 */
@Composable
fun DeviceScreen(onMessage: (String) -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    var refreshTick by remember { mutableStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) refreshTick++ }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val a11yOk = remember(refreshTick) { ClawAccessibilityService.isRunning() }
    val model = remember { "${Build.MANUFACTURER} ${Build.MODEL}".trim().replaceFirstChar { it.uppercase() } }
    val androidVer = remember { Build.VERSION.RELEASE ?: "" }
    val battery = remember(refreshTick) { batteryPct(context) }
    val ip = remember(refreshTick) { lanIp() }

    // 局域网真后端：观察发现到的远端设备 + 启动发现/配置服务，让本机既能发现也能被发现/被控
    val remoteControl = remember { DeviceRemoteControl() }
    val nearby by ClawApplication.instance.deviceRegistry.deviceList.collectAsState()
    var selectedRemoteId by remember { mutableStateOf<String?>(null) }
    val discoverableAddr = remember(refreshTick) { runCatching { ConfigServerManager.getAddress() }.getOrNull() }
    LaunchedEffect(Unit) {
        runCatching { ClawApplication.instance.deviceDiscoveryManager.start() }
        runCatching { ConfigServerManager.start(context) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(BackgroundColor).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 顶部
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.device_title), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                StatusPill(a11yOk)
            }
        }

        // 本机信息卡
        item { ThisDeviceCard(model, androidVer, battery, ip) }

        // 无障碍未开启提示
        if (!a11yOk) {
            item {
                A11yPromptCard {
                    runCatching {
                        context.startActivity(android.content.Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                }
            }
        }

        // 快捷控制（通过无障碍真实执行）
        item {
            ControlsCard(enabled = a11yOk) { action ->
                when (action) {
                    "screenshot" -> scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            val svc = ClawAccessibilityService.getInstance() ?: return@withContext false
                            val bmp = runCatching { svc.takeScreenshot(4000) }.getOrNull() ?: return@withContext false
                            saveToGallery(context, bmp)
                        }
                        onMessage(context.getString(if (ok) R.string.device_screenshot_saved else R.string.device_screenshot_failed))
                    }
                    "home" -> ClawAccessibilityService.getInstance()?.pressHome()
                    "back" -> ClawAccessibilityService.getInstance()?.pressBack()
                    "recents" -> ClawAccessibilityService.getInstance()?.openRecentApps()
                    "notifications" -> ClawAccessibilityService.getInstance()?.expandNotifications()
                    "lock" -> ClawAccessibilityService.getInstance()?.lockScreen()
                }
            }
        }

        // 安全开关：是否允许本机被局域网控制（默认关闭，开启才广播控制 token）
        item { LanControlToggle() }

        // ── 局域网附近设备（真实发现 + 真实远程控制）──
        item {
            Column {
                Text(stringResource(R.string.device_nearby), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextSecondary, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
                Text(
                    if (discoverableAddr != null) stringResource(R.string.device_discoverable, discoverableAddr)
                    else stringResource(R.string.device_discoverable_off),
                    fontSize = 10.sp, color = TextMuted,
                )
            }
        }

        if (nearby.isEmpty()) {
            item {
                Surface(shape = RoundedCornerShape(14.dp), color = SurfaceColor, border = BorderStroke(1.dp, BorderColor)) {
                    Text(
                        stringResource(R.string.device_nearby_empty),
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        fontSize = 12.sp, color = TextMuted, lineHeight = 16.sp, textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            items(nearby, key = { it.deviceId }) { device ->
                RemoteDeviceCard(
                    device = device,
                    expanded = selectedRemoteId == device.deviceId,
                    onToggle = { selectedRemoteId = if (selectedRemoteId == device.deviceId) null else device.deviceId },
                    onAction = { action ->
                        scope.launch {
                            val ok = when (action) {
                                "screenshot" -> {
                                    val bytes = withContext(Dispatchers.IO) { runCatching { remoteControl.captureScreenshot(device) }.getOrNull() }
                                    val bmp = bytes?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }
                                    bmp != null && withContext(Dispatchers.IO) { saveToGallery(context, bmp) }
                                }
                                "home" -> withContext(Dispatchers.IO) { runCatching { remoteControl.pressHome(device) }.getOrDefault(false) }
                                "back" -> withContext(Dispatchers.IO) { runCatching { remoteControl.pressBack(device) }.getOrDefault(false) }
                                "recents" -> withContext(Dispatchers.IO) { runCatching { remoteControl.sendKey(device, 187) }.getOrDefault(false) }
                                else -> false
                            }
                            onMessage(
                                if (action == "screenshot" && ok) context.getString(R.string.device_screenshot_saved)
                                else if (ok) context.getString(R.string.device_cmd_sent, device.deviceName)
                                else context.getString(R.string.device_cmd_failed)
                            )
                        }
                    },
                )
            }
        }

        item { Spacer(modifier = Modifier.height(10.dp)) }
    }
}

@Composable
private fun LanControlToggle() {
    var enabled by remember { mutableStateOf(KVUtils.isLanControlEnabled()) }
    Surface(shape = RoundedCornerShape(14.dp), color = SurfaceColor, border = BorderStroke(1.dp, BorderColor)) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.device_allow_control), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                Spacer(modifier = Modifier.height(2.dp))
                Text(stringResource(R.string.device_allow_control_desc), fontSize = 10.sp, color = TextMuted, lineHeight = 14.sp)
            }
            Spacer(modifier = Modifier.width(10.dp))
            Switch(
                checked = enabled,
                onCheckedChange = { enabled = it; KVUtils.setLanControlEnabled(it) },
                colors = SwitchDefaults.colors(checkedTrackColor = SuccessColor, checkedThumbColor = Color.White),
            )
        }
    }
}

@Composable
private fun RemoteDeviceCard(device: DeviceInfo, expanded: Boolean, onToggle: () -> Unit, onAction: (String) -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (expanded) PrimaryColor.copy(alpha = 0.08f) else SurfaceColor,
        border = BorderStroke(1.dp, if (expanded) PrimaryColor.copy(alpha = 0.3f) else BorderColor),
        modifier = Modifier.clickable(onClick = onToggle),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📱", fontSize = 18.sp)
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(device.deviceName, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                    Text("${device.ip}  ·  Android ${device.androidVersion}", fontSize = 10.sp, color = TextMuted)
                }
                Text(
                    stringResource(if (device.online) R.string.status_online else R.string.status_offline),
                    fontSize = 10.sp, color = if (device.online) SuccessColor else TextMuted, fontWeight = FontWeight.SemiBold,
                )
            }
            if (expanded) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        "📸" to ("screenshot" to stringResource(R.string.ctrl_screenshot)),
                        "🏠" to ("home" to stringResource(R.string.ctrl_home)),
                        "⬅️" to ("back" to stringResource(R.string.ctrl_back)),
                        "🔲" to ("recents" to stringResource(R.string.ctrl_recents)),
                    ).forEach { (icon, pair) ->
                        val (action, label) = pair
                        Column(
                            modifier = Modifier.weight(1f)
                                .background(SurfaceVariantColor, RoundedCornerShape(8.dp))
                                .clickable { onAction(action) }
                                .padding(vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(icon, fontSize = 16.sp)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(label, fontSize = 9.sp, color = TextSecondary, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusPill(on: Boolean) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = (if (on) SuccessColor else TextMuted).copy(alpha = 0.15f),
    ) {
        Text(
            stringResource(if (on) R.string.status_online else R.string.status_offline),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            fontSize = 10.sp,
            color = if (on) SuccessColor else TextMuted,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ThisDeviceCard(model: String, androidVer: String, battery: Int, ip: String) {
    Surface(shape = RoundedCornerShape(14.dp), color = SurfaceColor, border = BorderStroke(1.dp, BorderColor)) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📱", fontSize = 22.sp)
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(model.ifBlank { "—" }, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(shape = RoundedCornerShape(4.dp), color = SuccessColor.copy(alpha = 0.15f)) {
                            Text(stringResource(R.string.device_this_device), modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp), fontSize = 8.sp, color = SuccessColor, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(stringResource(R.string.device_android_version, androidVer), fontSize = 11.sp, color = TextMuted)
                }
                if (battery in 0..100) {
                    Text("🔋 $battery%", fontSize = 12.sp, color = if (battery <= 20) WarningColor else TextSecondary)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text("🌐 $ip", fontSize = 11.sp, color = TextMuted)
        }
    }
}

@Composable
private fun A11yPromptCard(onEnable: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = WarningColor.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, WarningColor.copy(alpha = 0.3f)),
        modifier = Modifier.clickable(onClick = onEnable),
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.device_a11y_required), fontSize = 12.sp, color = TextSecondary, modifier = Modifier.weight(1f), lineHeight = 16.sp)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.device_a11y_enable), fontSize = 12.sp, color = WarningColor, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ControlsCard(enabled: Boolean, onAction: (String) -> Unit) {
    val controls = listOf(
        "📸" to ("screenshot" to stringResource(R.string.ctrl_screenshot)),
        "🏠" to ("home" to stringResource(R.string.ctrl_home)),
        "⬅️" to ("back" to stringResource(R.string.ctrl_back)),
        "🔲" to ("recents" to stringResource(R.string.ctrl_recents)),
        "🔔" to ("notifications" to stringResource(R.string.ctrl_notifications)),
        "🔒" to ("lock" to stringResource(R.string.ctrl_lock)),
    )
    Surface(shape = RoundedCornerShape(14.dp), color = SurfaceColor, border = BorderStroke(1.dp, BorderColor)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(stringResource(R.string.device_controls), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextSecondary)
            Spacer(modifier = Modifier.height(10.dp))
            controls.chunked(3).forEach { row ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { (icon, pair) ->
                        val (action, label) = pair
                        Column(
                            modifier = Modifier.weight(1f)
                                .background(SurfaceVariantColor.copy(alpha = if (enabled) 1f else 0.4f), RoundedCornerShape(8.dp))
                                .let { if (enabled) it.clickable { onAction(action) } else it }
                                .padding(vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(icon, fontSize = 18.sp)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(label, fontSize = 10.sp, color = if (enabled) TextSecondary else TextMuted, textAlign = TextAlign.Center)
                        }
                    }
                    // 补齐空位保持等宽
                    repeat(3 - row.size) { Spacer(modifier = Modifier.weight(1f)) }
                }
                Spacer(modifier = Modifier.height(6.dp))
            }
        }
    }
}

// ── 真实数据获取 ─────────────────────────────────────

private fun batteryPct(context: Context): Int {
    return try {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
        bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
    } catch (e: Exception) {
        -1
    }
}

/** 取本机局域网 IPv4（站点本地地址），失败返回 "—" */
private fun lanIp(): String {
    return try {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress ?: "—"
    } catch (e: Exception) {
        "—"
    }
}

/** 把截图位图存入系统相册 Pictures/Octopus，成功返回 true */
private fun saveToGallery(context: Context, bmp: Bitmap): Boolean {
    return try {
        val soft = if (bmp.config == Bitmap.Config.HARDWARE) bmp.copy(Bitmap.Config.ARGB_8888, false) else bmp
        val name = "octopus_${System.currentTimeMillis()}.png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Octopus")
            }
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return false
        context.contentResolver.openOutputStream(uri)?.use { out ->
            soft.compress(Bitmap.CompressFormat.PNG, 100, out)
        } ?: return false
        true
    } catch (e: Exception) {
        false
    }
}
