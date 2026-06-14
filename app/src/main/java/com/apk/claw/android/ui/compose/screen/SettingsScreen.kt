package com.apk.claw.android.ui.compose.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Api
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SettingsAccessibility
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.apk.claw.android.octopus_mobile.browser.BrowserEngineFactory
import com.apk.claw.android.server.ConfigServerManager
import com.apk.claw.android.service.ClawAccessibilityService
import com.apk.claw.android.shizuku.ShizukuManager
import com.apk.claw.android.ui.compose.theme.OctopusColors
import com.apk.claw.android.ui.settings.LlmConfigActivity
import com.apk.claw.android.ui.settings.RuntimeConfigActivity
import com.apk.claw.android.utils.KVUtils
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apk.claw.android.R

// ── 真实权限/状态探测（非 Composable，可在 remember 中调用）────────────
private fun isNotifEnabled(c: Context): Boolean =
    runCatching { NotificationManagerCompat.from(c).areNotificationsEnabled() }.getOrDefault(false)

private fun isOverlayGranted(c: Context): Boolean =
    runCatching { Settings.canDrawOverlays(c) }.getOrDefault(false)

private fun isBatteryUnrestricted(c: Context): Boolean = runCatching {
    val pm = c.getSystemService(Context.POWER_SERVICE) as? PowerManager
    pm?.isIgnoringBatteryOptimizations(c.packageName) ?: false
}.getOrDefault(false)

// Q+ 走分区存储无需授权；Q 以下检查 WRITE_EXTERNAL_STORAGE
private fun isStorageGranted(c: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return true
    return androidx.core.content.ContextCompat.checkSelfPermission(
        c, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
}

private fun isShizukuReady(): Boolean = runCatching { ShizukuManager.isAvailable() }.getOrDefault(false)

private fun selectedEngineName(c: Context): String =
    runCatching { BrowserEngineFactory.selectBest(c).name }.getOrDefault("—")

private val PrimaryColor get() = OctopusColors.Primary
private val SuccessColor get() = OctopusColors.Success
private val WarningColor get() = OctopusColors.Warning
private val ErrorColor get() = OctopusColors.Error
private val AccentColor get() = OctopusColors.Accent
private val SurfaceColor get() = OctopusColors.Surface
private val SurfaceVariantColor get() = OctopusColors.SurfaceVariant
private val BackgroundColor get() = OctopusColors.Background
private val TextPrimary get() = OctopusColors.TextPrimary
private val TextSecondary get() = OctopusColors.TextSecondary
private val TextMuted get() = OctopusColors.TextMuted
private val BorderColor get() = OctopusColors.Border

@Composable
fun SettingsScreen(onMessage: (String) -> Unit = {}) {
    val context = LocalContext.current
    // 离开本页去授权后回来需刷新真实状态
    val lifecycleOwner = LocalLifecycleOwner.current
    var refreshTick by remember { mutableStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) refreshTick++ }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val permissionStates = remember(refreshTick) {
        listOf(
            ClawAccessibilityService.isRunning(),
            isNotifEnabled(context),
            isOverlayGranted(context),
            isBatteryUnrestricted(context),
            isStorageGranted(context),
            isShizukuReady(),
        )
    }
    val readyCount = permissionStates.count { it }
    val modelName = remember(refreshTick) { KVUtils.getLlmModelName().ifBlank { "—" } }
    val apiKeyConfigured = remember(refreshTick) { KVUtils.getLlmApiKey().isNotBlank() }
    val lanAddr = remember(refreshTick) { runCatching { ConfigServerManager.getAddress() }.getOrNull() }
    val engineName = remember(refreshTick) { selectedEngineName(context) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(BackgroundColor).statusBarsPadding(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SettingsHeader(
                readyCount = readyCount,
                modelName = modelName,
                apiKeyConfigured = apiKeyConfigured,
                lanAddr = lanAddr,
            )
        }

        item {
            SettingsCard(stringResource(R.string.settings_section_permissions), Icons.Filled.Shield, compact = true) {
                val perms = listOf(
                    PermissionUi(Icons.Filled.SettingsAccessibility, stringResource(R.string.perm_accessibility), permissionStates[0]),
                    PermissionUi(Icons.Filled.Notifications, stringResource(R.string.perm_notification), permissionStates[1]),
                    PermissionUi(Icons.Filled.PhoneAndroid, stringResource(R.string.perm_overlay), permissionStates[2]),
                    PermissionUi(Icons.Filled.BatteryChargingFull, stringResource(R.string.perm_battery), permissionStates[3]),
                    PermissionUi(Icons.Filled.Storage, stringResource(R.string.perm_storage), permissionStates[4]),
                    PermissionUi(Icons.Filled.Security, "Shizuku", permissionStates[5]),
                )
                PermissionSummaryRow(readyCount, perms)
            }
        }

        item {
            val notConfiguredText = stringResource(R.string.status_not_configured)
            val configuredText = stringResource(R.string.settings_llm_api_key_configured)
            val baseUrl = KVUtils.getLlmBaseUrl().ifBlank { notConfiguredText }
            val apiKey = KVUtils.getLlmApiKey()
            val keyMasked = if (apiKey.length >= 8) apiKey.take(5) + "••••" + apiKey.takeLast(4) else if (apiKey.isBlank()) notConfiguredText else configuredText
            SettingsCard(stringResource(R.string.settings_section_model), Icons.Filled.Memory, onClick = {
                context.startActivity(Intent(context, LlmConfigActivity::class.java))
            }) {
                SettingsRow(Icons.Filled.Api, modelName, baseUrl, trailing = keyMasked)
            }
        }

        // 普通人入口:一个「远程控制电脑」(默认走跨网 WebRTC,类似 ToDesk)
        item {
            SettingsCard(stringResource(R.string.settings_device_control_section), Icons.Filled.Monitor, compact = true, onClick = {
                context.startActivity(Intent(context, com.apk.claw.android.ui.featurescreens.PcRemoteWebrtcActivity::class.java))
            }) {
                SettingsRow(
                    Icons.Filled.Monitor,
                    stringResource(R.string.settings_remote_pc_title),
                    stringResource(R.string.settings_remote_pc_desc),
                )
            }
        }

        // 高级(默认折叠):RPC 主机连接 + 局域网远程桌面,术语都收在这里,普通人看不到
        item {
            var advExpanded by remember { mutableStateOf(false) }
            SettingsCard(stringResource(R.string.settings_advanced_title), Icons.Filled.Tune, compact = true, onClick = { advExpanded = !advExpanded }) {
                if (advExpanded) {
                    ClickableSettingsRow(Icons.Filled.Hub, stringResource(R.string.settings_octopus_runtime_title), stringResource(R.string.settings_runtime_plain_desc)) {
                        context.startActivity(Intent(context, RuntimeConfigActivity::class.java))
                    }
                    SettingsDivider()
                    ClickableSettingsRow(Icons.Filled.GraphicEq, stringResource(R.string.settings_remote_lan_title), "") {
                        context.startActivity(Intent(context, com.apk.claw.android.ui.featurescreens.PcRemoteActivity::class.java))
                    }
                } else {
                    Text(stringResource(R.string.settings_advanced_hint), color = TextMuted, fontSize = 11.sp, lineHeight = 15.sp)
                }
            }
        }

        item {
            val cfg = remember(refreshTick) {
                listOf(
                    KVUtils.getDingtalkAppKey().isNotEmpty() && KVUtils.getDingtalkAppSecret().isNotEmpty(),
                    KVUtils.getFeishuAppId().isNotEmpty() && KVUtils.getFeishuAppSecret().isNotEmpty(),
                    KVUtils.getQqAppId().isNotEmpty() && KVUtils.getQqAppSecret().isNotEmpty(),
                    KVUtils.getDiscordBotToken().isNotEmpty(),
                    KVUtils.getTelegramBotToken().isNotEmpty(),
                    KVUtils.getWechatBotToken().isNotEmpty(),
                )
            }
            SettingsCard(stringResource(R.string.settings_section_channels), Icons.Filled.Notifications, compact = true, onClick = {
                context.startActivity(Intent(context, com.apk.claw.android.ui.featurescreens.ChannelsActivity::class.java))
            }) {
                SettingsRow(
                    Icons.Filled.Notifications,
                    stringResource(R.string.settings_section_channels),
                    "${cfg.count { it }}/6",
                    trailing = if (cfg.any { it }) stringResource(R.string.status_connected) else stringResource(R.string.status_not_configured),
                )
            }
        }

        item {
            SettingsCard(stringResource(R.string.settings_appearance), Icons.Filled.LightMode, compact = true) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    IconBubble(Icons.Filled.LightMode, PrimaryColor)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.settings_light_mode), color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Switch(
                        checked = OctopusColors.isLight,
                        onCheckedChange = { v -> KVUtils.setLightTheme(v); OctopusColors.isLight = v },
                    )
                }
            }
        }

        item {
            SettingsCard(stringResource(R.string.settings_section_other), Icons.Filled.Tune, compact = true) {
                val notRunning = stringResource(R.string.status_not_connected)
                ClickableSettingsRow(Icons.Filled.Lan, stringResource(R.string.settings_lan_config), lanAddr ?: notRunning) {
                    onMessage("${context.getString(R.string.settings_lan_config)} · ${lanAddr ?: notRunning}")
                }
                SettingsDivider()
                ClickableSettingsRow(Icons.Filled.TravelExplore, stringResource(R.string.settings_browser_engine), engineName) {
                    onMessage("${context.getString(R.string.settings_browser_engine)} · $engineName")
                }
                SettingsDivider()
                ClickableSettingsRow(Icons.Filled.Devices, stringResource(R.string.settings_device_mgmt), stringResource(R.string.settings_device_mgmt_desc)) {
                    onMessage(context.getString(R.string.settings_device_mgmt))
                }
                SettingsDivider()
                ClickableSettingsRow(Icons.Filled.Extension, stringResource(R.string.settings_plugin_mgmt), stringResource(R.string.settings_plugin_desc)) {
                    onMessage(context.getString(R.string.settings_plugin_mgmt))
                }
            }
        }

        item {
            Text(
                "Octopus Mobile v0.0.2 · Apache 2.0",
                fontSize = 11.sp, color = TextMuted,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun CompactSettingsGrid(content: @Composable RowScope.() -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
private fun RowScope.CompactSettingsTile(icon: ImageVector, title: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.weight(1f).height(48.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(13.dp),
        color = SurfaceVariantColor,
        border = BorderStroke(1.dp, BorderColor),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(icon, contentDescription = null, tint = PrimaryColor, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(7.dp))
            Text(title, color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

private data class PermissionUi(val icon: ImageVector, val name: String, val ok: Boolean)

@Composable
private fun SettingsHeader(readyCount: Int, modelName: String, apiKeyConfigured: Boolean, lanAddr: String?) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.settings_title),
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
            letterSpacing = 0.sp,
        )
        Spacer(Modifier.height(3.dp))
        Text(stringResource(R.string.settings_subtitle), color = TextMuted, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 2)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            HeroMetric(
                label = stringResource(R.string.settings_metric_permissions),
                value = "$readyCount/6",
                ok = readyCount >= 5,
                modifier = Modifier.weight(1f),
            )
            HeroMetric(
                label = stringResource(R.string.settings_metric_model),
                value = if (apiKeyConfigured) modelName else stringResource(R.string.status_not_configured),
                ok = apiKeyConfigured,
                modifier = Modifier.weight(1f),
            )
            HeroMetric(
                label = stringResource(R.string.settings_metric_lan),
                value = if (lanAddr == null) stringResource(R.string.status_not_connected) else stringResource(R.string.status_online),
                ok = lanAddr != null,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun PermissionSummaryRow(readyCount: Int, perms: List<PermissionUi>) {
    val missing = perms.filterNot { it.ok }.take(3).joinToString(" · ") { it.name }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        IconBubble(Icons.Filled.Shield, if (readyCount >= 5) SuccessColor else WarningColor)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("$readyCount/6", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(
                if (missing.isBlank()) stringResource(R.string.status_online) else missing,
                color = TextMuted,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                maxLines = 1,
            )
        }
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (readyCount >= 5) SuccessColor.copy(alpha = 0.12f) else WarningColor.copy(alpha = 0.11f),
        ) {
            Text(
                if (readyCount >= 5) "OK" else "SET",
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                color = if (readyCount >= 5) SuccessColor else WarningColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun HeroMetric(label: String, value: String, ok: Boolean, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = OctopusColors.SurfaceDeep,
        border = BorderStroke(1.dp, if (ok) SuccessColor.copy(alpha = 0.16f) else BorderColor.copy(alpha = 0.8f)),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp)) {
            Text(label, color = TextMuted, fontSize = 10.sp, maxLines = 1)
            Spacer(Modifier.height(4.dp))
            Text(value, color = if (ok) SuccessColor else TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
private fun PermissionTile(permission: PermissionUi, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(13.dp),
        color = SurfaceVariantColor,
        border = BorderStroke(1.dp, if (permission.ok) SuccessColor.copy(alpha = 0.18f) else BorderColor),
    ) {
        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(permission.icon, contentDescription = null, tint = if (permission.ok) SuccessColor else TextMuted, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(permission.name, fontSize = 12.sp, color = TextPrimary, modifier = Modifier.weight(1f), maxLines = 1)
            Text(if (permission.ok) "OK" else "OFF", fontSize = 9.sp, color = if (permission.ok) SuccessColor else ErrorColor, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PermissionStatusRow(permission: PermissionUi) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBubble(permission.icon, if (permission.ok) SuccessColor else TextMuted)
        Spacer(Modifier.width(10.dp))
        Text(
            permission.name,
            modifier = Modifier.weight(1f),
            fontSize = 13.sp,
            color = TextPrimary,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        Surface(
            shape = RoundedCornerShape(9.dp),
            color = if (permission.ok) SuccessColor.copy(alpha = 0.12f) else ErrorColor.copy(alpha = 0.10f),
        ) {
            Text(
                if (permission.ok) "OK" else "OFF",
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                fontSize = 9.sp,
                color = if (permission.ok) SuccessColor else ErrorColor,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun ChannelTile(name: String, connected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(13.dp),
        color = SurfaceVariantColor,
        border = BorderStroke(1.dp, if (connected) SuccessColor.copy(alpha = 0.2f) else BorderColor),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(shape = RoundedCornerShape(50), color = if (connected) SuccessColor.copy(alpha = 0.12f) else OctopusColors.SurfaceDeep) {
                Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                    Text(name.take(1), fontSize = 11.sp, color = if (connected) SuccessColor else TextMuted, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(name, fontSize = 11.sp, color = TextSecondary, maxLines = 1)
            Text(
                if (connected) stringResource(R.string.status_connected) else stringResource(R.string.status_not_configured),
                fontSize = 9.sp,
                color = if (connected) SuccessColor else TextMuted,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun SettingsRow(icon: ImageVector, title: String, subtitle: String, trailing: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        IconBubble(icon, PrimaryColor)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary, lineHeight = 17.sp, maxLines = 2)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, fontSize = 11.sp, color = TextMuted, lineHeight = 15.sp, maxLines = 2)
        }
        trailing?.let {
            Spacer(Modifier.width(8.dp))
            Text(it, color = if (it == stringResource(R.string.status_not_configured)) WarningColor else SuccessColor, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = TextMuted, modifier = Modifier.size(17.dp))
    }
}

@Composable
private fun ClickableSettingsRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBubble(icon, PrimaryColor)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary, lineHeight = 17.sp, maxLines = 2)
            Spacer(Modifier.height(3.dp))
            Text(subtitle, fontSize = 11.sp, color = TextMuted, lineHeight = 15.sp, maxLines = 2)
        }
        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = TextMuted, modifier = Modifier.size(17.dp))
    }
}

@Composable
private fun IconBubble(icon: ImageVector, tint: Color) {
    // iOS 风格:实色圆角方形 + 白色字形(与「功能」中心一致)
    Surface(shape = RoundedCornerShape(9.dp), color = tint) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.padding(7.dp).size(18.dp))
    }
}

@Composable
private fun SettingsDivider() {
    Spacer(Modifier.height(10.dp))
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BorderColor.copy(alpha = 0.65f)))
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun SettingsCard(
    title: String,
    icon: ImageVector,
    onClick: (() -> Unit)? = null,
    compact: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = SurfaceColor,
        border = BorderStroke(1.dp, BorderColor.copy(alpha = 0.85f)),
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    ) {
        Column(modifier = Modifier.padding(if (compact) 12.dp else 13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Icon(icon, contentDescription = null, tint = TextMuted, modifier = Modifier.size(15.dp))
                Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextSecondary, letterSpacing = 0.sp, lineHeight = 16.sp)
            }
            Spacer(modifier = Modifier.height(if (compact) 8.dp else 9.dp))
            content()
        }
    }
}
