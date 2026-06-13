package com.apk.claw.android.ui.compose.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.apk.claw.android.octopus_mobile.browser.BrowserEngineFactory
import com.apk.claw.android.server.ConfigServerManager
import com.apk.claw.android.service.ClawAccessibilityService
import com.apk.claw.android.shizuku.ShizukuManager
import com.apk.claw.android.ui.settings.ChannelConfigActivity
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

private val PrimaryColor = Color(0xFF0A84FF)
private val SuccessColor = Color(0xFF30D158)
private val WarningColor = Color(0xFFFF9F0A)
private val ErrorColor = Color(0xFFFF453B)
private val SurfaceColor = Color(0xFF1C1C1E)
private val SurfaceVariantColor = Color(0xFF2C2C2E)
private val BackgroundColor = Color(0xFF000000)
private val TextPrimary = Color(0xFFFFFFFF)
private val TextSecondary = Color(0xFF98989D)
private val TextMuted = Color(0xFF8E8E93)
private val BorderColor = Color(0xFF38383A)

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
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(BackgroundColor).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 标题
        item {
            Text("⚙ " + stringResource(R.string.settings_title), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimary, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
        }

        // 权限状态
        item {
            SettingsCard(stringResource(R.string.settings_section_permissions)) {
                // 真实权限状态，回到前台时刷新
                val states = remember(refreshTick) {
                    listOf(
                        ClawAccessibilityService.isRunning(),
                        isNotifEnabled(context),
                        isOverlayGranted(context),
                        isBatteryUnrestricted(context),
                        isStorageGranted(context),
                        isShizukuReady(),
                    )
                }
                val perms = listOf(
                    stringResource(R.string.perm_accessibility) to states[0], stringResource(R.string.perm_notification) to states[1],
                    stringResource(R.string.perm_overlay) to states[2], stringResource(R.string.perm_battery) to states[3],
                    stringResource(R.string.perm_storage) to states[4], "Shizuku" to states[5],
                )
                // 2 列网格
                perms.chunked(2).forEach { row ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { (name, ok) ->
                            Surface(
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp),
                                color = SurfaceVariantColor,
                            ) {
                                Row(modifier = Modifier.padding(6.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(if (ok) "✓" else "✗", fontSize = 8.sp, color = if (ok) SuccessColor else ErrorColor)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(name, fontSize = 12.sp, color = TextPrimary)
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }
        }

        // 模型配置（点击进入真实 LLM 配置页）
        item {
            val model = KVUtils.getLlmModelName().ifBlank { "—" }
            val baseUrl = KVUtils.getLlmBaseUrl().ifBlank { "未配置" }
            val apiKey = KVUtils.getLlmApiKey()
            val keyMasked = if (apiKey.length >= 8) apiKey.take(5) + "••••" + apiKey.takeLast(4) else if (apiKey.isBlank()) "未配置" else "已设置"
            SettingsCard(stringResource(R.string.settings_section_model), onClick = {
                context.startActivity(Intent(context, LlmConfigActivity::class.java))
            }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(model, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                    Spacer(modifier = Modifier.weight(1f))
                    Text("›", fontSize = 18.sp, color = TextMuted)
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text("Base URL: $baseUrl", fontSize = 11.sp, color = TextMuted)
                Text("API Key: $keyMasked", fontSize = 11.sp, color = TextMuted)
            }
        }

        // 母体连接（octopus-agent Runtime）—— RPC 远程大脑
        item {
            val rpcUrl = remember(refreshTick) {
                KVUtils.getOctopusRpcUrl().ifBlank { "未配置（默认 ws://10.0.2.2:8765）" }
            }
            SettingsCard("母体连接 · Octopus Runtime", onClick = {
                context.startActivity(Intent(context, RuntimeConfigActivity::class.java))
            }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("RPC 远程大脑（母体下发 tool/execute）", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                    Spacer(modifier = Modifier.weight(1f))
                    Text("›", fontSize = 18.sp, color = TextMuted)
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(rpcUrl, fontSize = 11.sp, color = TextMuted)
            }
        }

        // 消息渠道
        item {
            SettingsCard(stringResource(R.string.settings_section_channels), onClick = {
                context.startActivity(Intent(context, ChannelConfigActivity::class.java))
            }) {
                // 真实「是否已配置」：检查各渠道凭据是否已填写
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
                val channels = listOf(
                    "💬" to stringResource(R.string.channel_dingtalk) to cfg[0],
                    "🐦" to stringResource(R.string.channel_feishu) to cfg[1],
                    "🐧" to "QQ" to cfg[2],
                    "🎮" to "Discord" to cfg[3],
                    "✈️" to "Telegram" to cfg[4],
                    "💚" to stringResource(R.string.channel_wechat) to cfg[5],
                )
                // 3 列网格
                channels.chunked(3).forEach { row ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { (iconName, connected) ->
                            val (icon, name) = iconName
                            val statusText = if (connected) stringResource(R.string.status_connected) else stringResource(R.string.status_not_configured)
                            Surface(
                                modifier = Modifier.weight(1f).clickable {
                                    onMessage("$name · $statusText")
                                },
                                shape = RoundedCornerShape(10.dp),
                                color = SurfaceVariantColor,
                                border = if (connected) BorderStroke(1.dp, SuccessColor.copy(alpha = 0.2f)) else BorderStroke(1.dp, Color.Transparent),
                            ) {
                                Column(
                                    modifier = Modifier.padding(vertical = 10.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(icon, fontSize = 14.sp)
                                    Text(name, fontSize = 11.sp, color = TextSecondary)
                                    Text(if (connected) stringResource(R.string.status_connected) else stringResource(R.string.status_not_configured), fontSize = 9.sp, color = if (connected) SuccessColor else TextMuted, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }
        }

        // 其他设置
        item {
            SettingsCard(stringResource(R.string.settings_section_other)) {
                // 真实数据：局域网配置服务地址（未启动则提示）+ 实际选用的浏览器引擎
                val lanAddr = remember(refreshTick) {
                    runCatching { ConfigServerManager.getAddress() }.getOrNull()
                }
                val engineName = remember(refreshTick) { selectedEngineName(context) }
                val notRunning = stringResource(R.string.status_not_connected)
                listOf(
                    stringResource(R.string.settings_lan_config) to (lanAddr ?: notRunning),
                    stringResource(R.string.settings_browser_engine) to engineName,
                ).forEach { (label, value) ->
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable { onMessage("$label · $value") }
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(label, fontSize = 13.sp, color = TextPrimary)
                        Text(value, fontSize = 11.sp, color = TextMuted)
                    }
                }
            }
        }

        // 版本
        item {
            Text(
                "Octopus Mobile v0.0.2 · Apache 2.0",
                fontSize = 11.sp, color = TextMuted,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun SettingsCard(title: String, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = SurfaceColor,
        border = BorderStroke(1.dp, BorderColor),
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextSecondary, letterSpacing = 0.5.sp)
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}
