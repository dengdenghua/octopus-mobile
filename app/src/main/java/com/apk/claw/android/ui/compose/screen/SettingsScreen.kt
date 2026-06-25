package com.apk.claw.android.ui.compose.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Api
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SettingsAccessibility
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import com.apk.claw.android.BuildConfig
import com.apk.claw.android.server.ConfigServerManager
import com.apk.claw.android.server.RemoteConsoleGateway
import com.apk.claw.android.service.ClawAccessibilityService
import com.apk.claw.android.shizuku.ShizukuManager
import com.apk.claw.android.ui.compose.theme.OctopusBackground
import com.apk.claw.android.ui.compose.theme.OctopusColors
import com.apk.claw.android.ui.compose.theme.OctopusGlass
import com.apk.claw.android.ui.compose.theme.OctopusGlassQuality
import com.apk.claw.android.ui.compose.theme.OctopusIconSize
import com.apk.claw.android.ui.compose.theme.OctopusLayout
import com.apk.claw.android.ui.compose.theme.OctopusShape
import com.apk.claw.android.ui.compose.theme.OctopusSpacing
import com.apk.claw.android.ui.compose.theme.OctopusType
import com.apk.claw.android.ui.settings.LlmConfigActivity
import com.apk.claw.android.ui.settings.RuntimeConfigActivity
import com.apk.claw.android.account.AccountStore
import com.apk.claw.android.ui.account.AccountActivity
import com.apk.claw.android.ui.account.LoginActivity
import com.apk.claw.android.utils.KVUtils
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apk.claw.android.R
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// ── 真实权限/状态探测 ──────────────────────────────────

private fun isNotifEnabled(c: Context): Boolean =
    runCatching { NotificationManagerCompat.from(c).areNotificationsEnabled() }.getOrDefault(false)

private fun isOverlayGranted(c: Context): Boolean =
    runCatching { Settings.canDrawOverlays(c) }.getOrDefault(false)

private fun isBatteryUnrestricted(c: Context): Boolean = runCatching {
    val pm = c.getSystemService(Context.POWER_SERVICE) as? PowerManager
    pm?.isIgnoringBatteryOptimizations(c.packageName) ?: false
}.getOrDefault(false)

private fun isStorageGranted(c: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return true
    return androidx.core.content.ContextCompat.checkSelfPermission(
        c, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
}

private fun isShizukuReady(): Boolean = runCatching { ShizukuManager.isAvailable() }.getOrDefault(false)

/** 未授权权限的跳转 Intent */
private fun permissionIntent(index: Int, context: Context): Intent? = when (index) {
    0 -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)           // 无障碍
    1 -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {  // 通知
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    }
    2 -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply { // 悬浮窗
        data = Uri.parse("package:${context.packageName}")
    }
    3 -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) // 电池
    4 -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { // 存储
        data = Uri.parse("package:${context.packageName}")
    }
    5 -> null // Shizuku 需要单独 App，无法直接跳转
    else -> null
}

private const val SHOW_BYO_MODEL_CONFIG = false

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

private data class PermissionUi(val icon: ImageVector, val name: String, val ok: Boolean)

@Composable
fun SettingsScreen(onMessage: (String) -> Unit = {}) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var refreshTick by remember { mutableStateOf(0) }
    var showRemotePairDialog by remember { mutableStateOf(false) }
    var pairCode by remember { mutableStateOf("") }
    var pairBusy by remember { mutableStateOf(false) }
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
    val lanAddr = remember(refreshTick) { runCatching { ConfigServerManager.getAddress() }.getOrNull() }
    val loggedIn = remember(refreshTick) { AccountStore.isLoggedIn }
    val credits = remember(refreshTick) { AccountStore.credits }
    val isMember = remember(refreshTick) { AccountStore.byoUnlocked }
    val remotePaired = remember(refreshTick) { RemoteConsoleGateway.isPaired }
    val remoteConnected = remember(refreshTick) { RemoteConsoleGateway.isConnected }

    if (showRemotePairDialog) {
        AlertDialog(
            onDismissRequest = { if (!pairBusy) showRemotePairDialog = false },
            title = { Text(stringResource(R.string.remote_console_pair_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(OctopusSpacing.sm)) {
                    Text(stringResource(R.string.remote_console_pair_desc), color = TextMuted, fontSize = OctopusType.caption, lineHeight = 16.sp)
                    OutlinedTextField(
                        value = pairCode,
                        onValueChange = { pairCode = it.filter(Char::isDigit).take(9) },
                        singleLine = true,
                        label = { Text(stringResource(R.string.remote_console_pair_code_label)) },
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = pairCode.length >= 6 && !pairBusy, onClick = {
                    scope.launch {
                        pairBusy = true
                        val result = runCatching { RemoteConsoleGateway.claimPairCode(pairCode) }
                        pairBusy = false
                        result.onSuccess {
                            onMessage(it)
                            pairCode = ""
                            showRemotePairDialog = false
                            refreshTick++
                        }.onFailure {
                            onMessage(it.message ?: context.getString(R.string.remote_console_pair_failed))
                        }
                    }
                }) {
                    Text(if (pairBusy) stringResource(R.string.status_loading) else stringResource(R.string.remote_console_pair_confirm))
                }
            },
            dismissButton = {
                TextButton(enabled = !pairBusy, onClick = { showRemotePairDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(OctopusBackground.pageBrush()).statusBarsPadding(),
        contentPadding = PaddingValues(
            start = OctopusSpacing.lg,
            end = OctopusSpacing.lg,
            top = OctopusSpacing.sm,
            bottom = OctopusLayout.bottomNavContentPadding,
        ),
        verticalArrangement = Arrangement.spacedBy(OctopusSpacing.sm),
    ) {
        item {
            SettingsHeader(
                readyCount = readyCount,
                lanAddr = lanAddr,
            )
        }

        item {
            val acct = remember(refreshTick) { AccountStore.mobile.ifEmpty { AccountStore.email } }
            SettingsCard(stringResource(R.string.menu_account), Icons.Filled.AccountCircle, compact = true, onClick = {
                val target = if (AccountStore.isLoggedIn) AccountActivity::class.java else LoginActivity::class.java
                context.startActivity(Intent(context, target))
            }) {
                if (loggedIn) {
                    SettingsRow(
                        Icons.Filled.AccountCircle,
                        acct,
                        stringResource(R.string.account_credits_label) + ": " + credits + (if (isMember) " · VIP" else ""),
                    )
                } else {
                    SettingsRow(
                        Icons.Filled.AccountCircle,
                        stringResource(R.string.account_login_title),
                        stringResource(R.string.account_login_tip),
                    )
                }
            }
        }

        // ── 权限：可点击跳转系统设置 ──
        item {
            val permNames = listOf(
                stringResource(R.string.perm_accessibility),
                stringResource(R.string.perm_notification),
                stringResource(R.string.perm_overlay),
                stringResource(R.string.perm_battery),
                stringResource(R.string.perm_storage),
                "Shizuku",
            )
            val permIcons = listOf(
                Icons.Filled.SettingsAccessibility,
                Icons.Filled.Notifications,
                Icons.Filled.PhoneAndroid,
                Icons.Filled.BatteryChargingFull,
                Icons.Filled.Storage,
                Icons.Filled.Security,
            )
            SettingsCard(stringResource(R.string.settings_section_permissions), Icons.Filled.Shield, compact = true) {
                // 摘要行
                val perms = permNames.mapIndexed { i, name -> PermissionUi(permIcons[i], name, permissionStates[i]) }
                PermissionSummaryRow(readyCount, perms)
                Spacer(Modifier.height(OctopusSpacing.sm))
                // 逐项权限（未授权可点击跳转）
                perms.forEachIndexed { i, perm ->
                    PermissionItemRow(
                        permission = perm,
                        onClick = {
                            if (!perm.ok) {
                                permissionIntent(i, context)?.let {
                                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    context.startActivity(it)
                                }
                            }
                        },
                    )
                }
            }
        }

        if (loggedIn && SHOW_BYO_MODEL_CONFIG) {
            item {
                val notConfiguredText = stringResource(R.string.status_not_configured)
                val configuredText = stringResource(R.string.settings_llm_api_key_configured)
                val modelName = KVUtils.getLlmModelName().ifBlank { notConfiguredText }
                val baseUrl = KVUtils.getLlmBaseUrl().ifBlank { notConfiguredText }
                val apiKey = KVUtils.getLlmApiKey()
                val keyMasked = if (apiKey.length >= 8) apiKey.take(5) + "••••" + apiKey.takeLast(4) else if (apiKey.isBlank()) notConfiguredText else configuredText
                SettingsCard(stringResource(R.string.settings_section_model), Icons.Filled.Memory, onClick = {
                    context.startActivity(Intent(context, LlmConfigActivity::class.java))
                }) {
                    SettingsRow(Icons.Filled.Api, modelName, baseUrl, trailing = keyMasked)
                    val byoHint = when {
                        !isMember -> stringResource(R.string.account_byo_member_hint)
                        credits > 0L -> stringResource(R.string.account_byo_credits_hint)
                        else -> null
                    }
                    byoHint?.let {
                        Spacer(Modifier.height(OctopusSpacing.sm))
                        Text(it, color = TextMuted, fontSize = OctopusType.caption, lineHeight = 15.sp)
                    }
                }
            }
        }

        item {
            // 「远程控制电脑」已统一并入顶栏的设备目标选择器（TargetSelector），此处不再重复入口。
            SettingsCard(stringResource(R.string.settings_device_control_section), Icons.Filled.Monitor, compact = true) {
                ClickableSettingsRow(
                    Icons.Filled.Hub,
                    stringResource(R.string.remote_console_title),
                    when {
                        remoteConnected -> stringResource(R.string.remote_console_status_connected)
                        remotePaired -> stringResource(R.string.remote_console_status_paired)
                        else -> stringResource(R.string.remote_console_status_unpaired)
                    },
                ) {
                    if (!AccountStore.isLoggedIn) {
                        onMessage(context.getString(R.string.remote_console_login_required))
                    } else {
                        showRemotePairDialog = true
                    }
                }
            }
        }

        // ── 高级：折叠提示增强 ──
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.settings_advanced_hint), color = TextMuted, fontSize = OctopusType.caption, lineHeight = 15.sp, modifier = Modifier.weight(1f))
                        Icon(Icons.Filled.ExpandMore, contentDescription = null, tint = TextMuted, modifier = Modifier.size(OctopusIconSize.small))
                    }
                }
            }
        }

        // ── 渠道：内联显示各渠道状态 ──
        item {
            val channelNames = listOf(
                stringResource(R.string.channel_name_dingtalk),
                stringResource(R.string.channel_name_feishu),
                stringResource(R.string.channel_name_qq),
                stringResource(R.string.channel_name_discord),
                stringResource(R.string.channel_name_telegram),
                stringResource(R.string.channel_name_wechat),
            )
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
                // 摘要
                SettingsRow(
                    Icons.Filled.Notifications,
                    stringResource(R.string.settings_section_channels),
                    "${cfg.count { it }}/6",
                    trailing = if (cfg.any { it }) stringResource(R.string.status_connected) else stringResource(R.string.status_not_configured),
                )
                Spacer(Modifier.height(OctopusSpacing.sm))
                // 各渠道状态点
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(OctopusSpacing.sm),
                ) {
                    channelNames.forEachIndexed { i, name ->
                        val connected = cfg[i]
                        Surface(
                            shape = OctopusShape.small,
                            color = if (connected) SuccessColor.copy(alpha = 0.10f) else SurfaceVariantColor,
                            border = BorderStroke(1.dp, if (connected) SuccessColor.copy(alpha = 0.20f) else BorderColor.copy(alpha = 0.6f)),
                            modifier = Modifier.weight(1f),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = OctopusSpacing.sm, vertical = OctopusSpacing.xs),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                Box(modifier = Modifier.size(5.dp).background(
                                    if (connected) SuccessColor else TextMuted,
                                    CircleShape,
                                ))
                                Spacer(Modifier.width(OctopusSpacing.xs))
                                Text(name, fontSize = OctopusType.tag, color = if (connected) TextPrimary else TextMuted, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }

        item {
            SettingsCard(stringResource(R.string.settings_appearance), Icons.Filled.LightMode, compact = true) {
                // 主题模式：跟随系统 / 强制亮色 / 强制暗色（三态，统一 Compose 与 XML）
                var themeMode: Boolean? by remember { mutableStateOf(KVUtils.getThemeMode()) }
                var glassBlurRadius by remember { mutableFloatStateOf(KVUtils.getGlassBlurRadius()) }
                var glassQuality by remember { mutableStateOf(OctopusGlassQuality.fromStorage(KVUtils.getGlassQuality())) }
                var glassRefraction by remember { mutableFloatStateOf(KVUtils.getGlassRefraction()) }
                var glassHighlight by remember { mutableFloatStateOf(KVUtils.getGlassHighlight()) }
                var glassNoise by remember { mutableFloatStateOf(KVUtils.getGlassNoise()) }
                var glassAnimation by remember { mutableStateOf(KVUtils.isGlassAnimationEnabled()) }
                val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
                val isLight = themeMode ?: !systemDark

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    IconBubble(Icons.Filled.LightMode, PrimaryColor)
                    Spacer(Modifier.width(OctopusSpacing.md))
                    Text(stringResource(R.string.settings_light_mode), color = TextPrimary, fontSize = OctopusType.body, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Switch(
                        checked = isLight,
                        onCheckedChange = { v ->
                            // 切换时强制亮/暗，写入新的三态 key
                            KVUtils.setThemeMode(v)
                            themeMode = v
                            OctopusColors.isLight = v
                        },
                    )
                }
                // 提示当前模式
                val modeText = if (themeMode == null) {
                    stringResource(R.string.settings_theme_follow_system)
                } else if (themeMode == true) {
                    stringResource(R.string.settings_theme_light)
                } else {
                    stringResource(R.string.settings_theme_dark)
                }
                Spacer(Modifier.height(OctopusSpacing.sm))
                Text(
                    modeText,
                    fontSize = OctopusType.caption,
                    color = TextMuted,
                    modifier = Modifier.padding(start = 44.dp),
                )

                SettingsDivider()

                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(OctopusSpacing.xs),
                    ) {
                        listOf(
                            OctopusGlassQuality.Low,
                            OctopusGlassQuality.Medium,
                            OctopusGlassQuality.High,
                            OctopusGlassQuality.Ultra,
                        ).forEach { quality ->
                            val selected = glassQuality == quality
                            Surface(
                                shape = OctopusShape.capsule,
                                color = if (selected) PrimaryColor.copy(alpha = 0.16f) else OctopusBackground.glassSurface,
                                border = BorderStroke(1.dp, if (selected) PrimaryColor.copy(alpha = 0.42f) else OctopusBackground.glassBorder),
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        glassQuality = quality
                                        OctopusGlass.quality = quality
                                        KVUtils.setGlassQuality(quality.name.lowercase())
                                    },
                            ) {
                                Text(
                                    quality.name,
                                    color = if (selected) PrimaryColor else TextSecondary,
                                    fontSize = OctopusType.tag,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(vertical = OctopusSpacing.sm),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(OctopusSpacing.sm))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        IconBubble(Icons.Filled.GraphicEq, PrimaryColor)
                        Spacer(Modifier.width(OctopusSpacing.md))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.settings_glass_blur),
                                color = TextPrimary,
                                fontSize = OctopusType.body,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                stringResource(R.string.settings_glass_blur_hint),
                                color = TextMuted,
                                fontSize = OctopusType.caption,
                                lineHeight = 15.sp,
                            )
                        }
                        Text(
                            "${glassBlurRadius.roundToInt()}dp",
                            color = PrimaryColor,
                            fontSize = OctopusType.label,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Slider(
                        value = glassBlurRadius,
                        onValueChange = { value ->
                            glassBlurRadius = value
                            OctopusGlass.blurRadius = value.dp
                        },
                        onValueChangeFinished = {
                            KVUtils.setGlassBlurRadius(glassBlurRadius)
                        },
                        valueRange = 0f..48f,
                        steps = 15,
                        modifier = Modifier.padding(start = 44.dp),
                    )
                    GlassTuningSlider(
                        title = stringResource(R.string.settings_glass_refraction),
                        value = glassRefraction,
                        valueText = "${(glassRefraction * 100).roundToInt()}%",
                        onValueChange = {
                            glassRefraction = it
                            OctopusGlass.refraction = it
                        },
                        onValueChangeFinished = { KVUtils.setGlassRefraction(glassRefraction) },
                    )
                    GlassTuningSlider(
                        title = stringResource(R.string.settings_glass_highlight),
                        value = glassHighlight,
                        valueText = "${(glassHighlight * 100).roundToInt()}%",
                        onValueChange = {
                            glassHighlight = it
                            OctopusGlass.highlight = it
                        },
                        onValueChangeFinished = { KVUtils.setGlassHighlight(glassHighlight) },
                    )
                    GlassTuningSlider(
                        title = stringResource(R.string.settings_glass_noise),
                        value = glassNoise,
                        valueText = "${(glassNoise * 100).roundToInt()}%",
                        onValueChange = {
                            glassNoise = it
                            OctopusGlass.noise = it
                        },
                        onValueChangeFinished = { KVUtils.setGlassNoise(glassNoise) },
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 44.dp).fillMaxWidth()) {
                        Text(
                            stringResource(R.string.settings_glass_animation),
                            color = TextPrimary,
                            fontSize = OctopusType.label,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = glassAnimation,
                            onCheckedChange = {
                                glassAnimation = it
                                OctopusGlass.animationEnabled = it
                                KVUtils.setGlassAnimationEnabled(it)
                            },
                        )
                    }
                }
            }
        }

        // ── 版本号从 BuildConfig 读取 ──
        item {
            Text(
                stringResource(R.string.settings_version_template, BuildConfig.VERSION_NAME),
                fontSize = OctopusType.caption, color = TextMuted,
                modifier = Modifier.fillMaxWidth().padding(vertical = OctopusSpacing.sm),
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ── 权限逐项行（未授权可点击跳转）──────────────────────────

@Composable
private fun PermissionItemRow(permission: PermissionUi, onClick: () -> Unit) {
    val clickable = !permission.ok
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (clickable) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = OctopusSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            permission.icon,
            contentDescription = null,
            tint = if (permission.ok) SuccessColor else TextMuted,
            modifier = Modifier.size(OctopusIconSize.small),
        )
        Spacer(Modifier.width(OctopusSpacing.sm))
        Text(
            permission.name,
            fontSize = OctopusType.label,
            color = TextPrimary,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        if (clickable) {
            Text(
                    stringResource(R.string.settings_go_setup),
                    fontSize = OctopusType.tag,
                color = PrimaryColor,
                fontWeight = FontWeight.SemiBold,
            )
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = PrimaryColor,
                modifier = Modifier.size(OctopusIconSize.small),
            )
        } else {
            Surface(
                shape = OctopusShape.small,
                color = SuccessColor.copy(alpha = 0.12f),
            ) {
                Text(
                    stringResource(R.string.settings_status_ok),
                    modifier = Modifier.padding(horizontal = OctopusSpacing.sm, vertical = OctopusSpacing.xs),
                    fontSize = OctopusType.micro,
                    color = SuccessColor,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

// ── Header / Summary ──────────────────────────────────

@Composable
private fun SettingsHeader(readyCount: Int, lanAddr: String?) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.settings_title),
            fontSize = OctopusType.headline,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
            letterSpacing = 0.sp,
        )
        Spacer(Modifier.height(OctopusSpacing.xs))
        Text(stringResource(R.string.settings_subtitle), color = TextMuted, fontSize = OctopusType.label, lineHeight = 16.sp, maxLines = 2)
        Spacer(Modifier.height(OctopusSpacing.lg))
        Row(horizontalArrangement = Arrangement.spacedBy(OctopusSpacing.sm), modifier = Modifier.fillMaxWidth()) {
            HeroMetric(
                label = stringResource(R.string.settings_metric_permissions),
                value = "$readyCount/6",
                ok = readyCount >= 5,
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
        Spacer(Modifier.width(OctopusSpacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text("$readyCount/6", color = TextPrimary, fontSize = OctopusType.title, fontWeight = FontWeight.Bold)
            Text(
                if (missing.isBlank()) stringResource(R.string.status_online) else missing,
                color = TextMuted,
                fontSize = OctopusType.caption,
                lineHeight = 15.sp,
                maxLines = 1,
            )
        }
        Surface(
            shape = OctopusShape.medium,
            color = if (readyCount >= 5) SuccessColor.copy(alpha = 0.12f) else WarningColor.copy(alpha = 0.11f),
        ) {
            Text(
                if (readyCount >= 5) stringResource(R.string.settings_status_ok) else stringResource(R.string.settings_status_set),
                modifier = Modifier.padding(horizontal = OctopusSpacing.sm, vertical = OctopusSpacing.xs),
                color = if (readyCount >= 5) SuccessColor else WarningColor,
                fontSize = OctopusType.tag,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun HeroMetric(label: String, value: String, ok: Boolean, modifier: Modifier = Modifier) {
    val shape = OctopusShape.medium
    val borderColor = when {
        ok -> SuccessColor.copy(alpha = 0.16f)
        value.contains(stringResource(R.string.status_not_configured)) -> WarningColor.copy(alpha = 0.25f)
        else -> OctopusBackground.glassBorder
    }
    Box(
        modifier = modifier
            .clip(shape)
            .background(OctopusBackground.glassSurface, shape)
            .border(1.dp, borderColor, shape),
    ) {
        Column(modifier = Modifier.padding(horizontal = OctopusSpacing.md, vertical = OctopusSpacing.sm)) {
            Text(label, color = TextMuted, fontSize = OctopusType.tag, maxLines = 1)
            Spacer(Modifier.height(OctopusSpacing.xs))
        Text(
            value,
            color = when {
                    ok -> SuccessColor
                    value.contains(stringResource(R.string.status_not_configured)) -> WarningColor
                    else -> TextSecondary
                },
                fontSize = OctopusType.label,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

// ── 通用组件 ──────────────────────────────────────────

@Composable
private fun SettingsRow(icon: ImageVector, title: String, subtitle: String, trailing: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        IconBubble(icon, PrimaryColor)
        Spacer(Modifier.width(OctopusSpacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = OctopusType.body, fontWeight = FontWeight.SemiBold, color = TextPrimary, lineHeight = 17.sp, maxLines = 2)
            Spacer(Modifier.height(OctopusSpacing.xs))
            Text(subtitle, fontSize = OctopusType.caption, color = TextMuted, lineHeight = 15.sp, maxLines = 2)
        }
        trailing?.let {
            Spacer(Modifier.width(OctopusSpacing.sm))
            Text(it, color = if (it == stringResource(R.string.status_not_configured)) WarningColor else SuccessColor, fontSize = OctopusType.caption, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = TextMuted, modifier = Modifier.size(OctopusIconSize.small))
    }
}

@Composable
private fun ClickableSettingsRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = OctopusSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBubble(icon, PrimaryColor)
        Spacer(Modifier.width(OctopusSpacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = OctopusType.body, fontWeight = FontWeight.SemiBold, color = TextPrimary, lineHeight = 17.sp, maxLines = 2)
            Spacer(Modifier.height(OctopusSpacing.xs))
            Text(subtitle, fontSize = OctopusType.caption, color = TextMuted, lineHeight = 15.sp, maxLines = 2)
        }
        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = TextMuted, modifier = Modifier.size(OctopusIconSize.small))
    }
}

@Composable
private fun IconBubble(icon: ImageVector, tint: Color) {
    Surface(shape = OctopusShape.small, color = tint) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.padding(6.dp).size(OctopusIconSize.small))
    }
}

@Composable
private fun SettingsDivider() {
    Spacer(Modifier.height(OctopusSpacing.md))
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BorderColor.copy(alpha = 0.65f)))
    Spacer(Modifier.height(OctopusSpacing.md))
}

@Composable
private fun GlassTuningSlider(
    title: String,
    value: Float,
    valueText: String,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    Column(modifier = Modifier.padding(start = 44.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                title,
                color = TextPrimary,
                fontSize = OctopusType.label,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                valueText,
                color = PrimaryColor,
                fontSize = OctopusType.tag,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = 0f..2f,
            steps = 15,
        )
    }
}

@Composable
private fun SettingsCard(
    title: String,
    icon: ImageVector,
    onClick: (() -> Unit)? = null,
    compact: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = OctopusShape.large
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(OctopusBackground.glassSurface, shape)
            .border(1.dp, OctopusBackground.glassBorder, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Column(modifier = Modifier.padding(horizontal = OctopusSpacing.md, vertical = OctopusSpacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OctopusSpacing.sm)) {
                Icon(icon, contentDescription = null, tint = TextMuted, modifier = Modifier.size(OctopusIconSize.small))
                Text(title, fontSize = OctopusType.label, fontWeight = FontWeight.SemiBold, color = TextSecondary, letterSpacing = 0.sp, lineHeight = 16.sp)
            }
            Spacer(modifier = Modifier.height(if (compact) OctopusSpacing.sm else OctopusSpacing.md))
            content()
        }
    }
}
