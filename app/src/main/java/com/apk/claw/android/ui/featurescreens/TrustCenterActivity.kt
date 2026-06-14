package com.apk.claw.android.ui.featurescreens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.apk.claw.android.R
import com.apk.claw.android.octopus_mobile.ControlTarget
import com.apk.claw.android.server.ConfigServerManager
import com.apk.claw.android.service.ClawAccessibilityService
import com.apk.claw.android.shizuku.ShizukuManager
import com.apk.claw.android.utils.KVUtils

class TrustCenterActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TrustCenterScreen(onBack = { finish() }) }
        runCatching { window.statusBarColor = com.apk.claw.android.ui.compose.theme.OctopusColors.statusBarArgb }
    }
}

private fun deviceModel(): String =
    "${Build.MANUFACTURER} ${Build.MODEL}".trim().replaceFirstChar { it.uppercase() }

private fun batteryPct(c: Context): Int = runCatching {
    (c.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager)
        ?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
}.getOrDefault(-1)

private fun lanIp(): String = runCatching {
    java.net.NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address && it.isSiteLocalAddress }
        ?.hostAddress ?: "—"
}.getOrDefault("—")

private fun openIntent(c: Context, action: String, withPkg: Boolean = false) {
    runCatching {
        val i = Intent(action)
        if (withPkg) i.data = Uri.parse("package:" + c.packageName)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        c.startActivity(i)
    }
}

@Composable
fun TrustCenterScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var tick by remember { mutableStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val a11y = remember(tick) { ClawAccessibilityService.isRunning() }
    val overlay = remember(tick) { runCatching { Settings.canDrawOverlays(ctx) }.getOrDefault(false) }
    val battery = remember(tick) {
        runCatching {
            (ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isIgnoringBatteryOptimizations(ctx.packageName) ?: false
        }.getOrDefault(false)
    }
    val notif = remember(tick) { runCatching { NotificationManagerCompat.from(ctx).areNotificationsEnabled() }.getOrDefault(false) }
    val shizuku = remember(tick) { runCatching { ShizukuManager.isAvailable() }.getOrDefault(false) }
    val storage = remember(tick) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) true
        else androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    val lanAddr = remember(tick) { runCatching { ConfigServerManager.getAddress() }.getOrNull() }
    val targetLabel = remember(tick) { ControlTarget.label() }
    var lanOn by remember(tick) { mutableStateOf(KVUtils.isLanControlEnabled()) }

    FeatureScaffold(title = stringResource(R.string.trustcenter_title), onBack = onBack) {
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text(
                stringResource(R.string.trustcenter_description),
                color = FMuted, fontSize = 12.sp, lineHeight = 17.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            FSectionTitle(stringResource(R.string.device_this_device))
            val batteryLevel = remember(tick) { batteryPct(ctx) }
            val ip = remember(tick) { lanIp() }
            FCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("📱", fontSize = 20.sp)
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(deviceModel(), color = FText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text("Android ${Build.VERSION.RELEASE}  ·  🌐 $ip", color = FMuted, fontSize = 11.sp)
                    }
                    if (batteryLevel in 0..100) {
                        Text("🔋 $batteryLevel%", fontSize = 12.sp, color = if (batteryLevel <= 20) FWarning else FSub)
                    }
                }
            }

            FSectionTitle(stringResource(R.string.trustcenter_section_capabilities))
            CapabilityRow("♿", stringResource(R.string.home_card_accessibility_title), stringResource(R.string.trustcenter_capability_accessibility_desc), a11y) {
                openIntent(ctx, Settings.ACTION_ACCESSIBILITY_SETTINGS)
            }
            CapabilityRow("🪟", stringResource(R.string.perm_overlay), stringResource(R.string.trustcenter_capability_overlay_desc), overlay) {
                openIntent(ctx, Settings.ACTION_MANAGE_OVERLAY_PERMISSION, withPkg = true)
            }
            CapabilityRow("🔋", stringResource(R.string.trustcenter_capability_battery), stringResource(R.string.trustcenter_capability_battery_desc), battery) {
                openIntent(ctx, Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, withPkg = true)
            }
            CapabilityRow("🔔", stringResource(R.string.ctrl_notifications), stringResource(R.string.trustcenter_capability_notification_desc), notif) {
                openIntent(ctx, Settings.ACTION_APPLICATION_DETAILS_SETTINGS, withPkg = true)
            }
            CapabilityRow("⚡", stringResource(R.string.trustcenter_capability_shizuku), stringResource(R.string.trustcenter_capability_shizuku_desc), shizuku) {
                runCatching { ShizukuManager.requestPermission() }
            }
            CapabilityRow("💾", stringResource(R.string.perm_storage), stringResource(R.string.trustcenter_capability_storage_desc), storage) {
                openIntent(ctx, Settings.ACTION_APPLICATION_DETAILS_SETTINGS, withPkg = true)
            }

            FSectionTitle(stringResource(R.string.trustcenter_section_lannetwork))
            FCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.trustcenter_lan_allow_toggle), color = FText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.trustcenter_lan_allow_desc), color = FMuted, fontSize = 10.sp, lineHeight = 14.sp)
                    }
                    Switch(
                        checked = lanOn,
                        onCheckedChange = { lanOn = it; KVUtils.setLanControlEnabled(it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = FSuccess, checkedThumbColor = Color.White),
                    )
                }
            }
            FCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.trustcenter_lan_discoverable_label), color = FText, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text(lanAddr ?: stringResource(R.string.trustcenter_lan_not_started), color = if (lanAddr != null) FSuccess else FMuted, fontSize = 12.sp)
                }
            }

            FSectionTitle(stringResource(R.string.trustcenter_section_target))
            FCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.trustcenter_target_label), color = FText, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    FPill(targetLabel, if (ControlTarget.isRemote()) FWarning else FPrimary)
                }
            }

            // 一键收回（可程序化的部分：关闭局域网广播 + 目标改回本机）
            Spacer(modifier = Modifier.height(8.dp))
            FCard {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable {
                        KVUtils.setLanControlEnabled(false)
                        lanOn = false
                        ControlTarget.setLocal()
                        tick++
                    },
                ) {
                    Text("🛑", fontSize = 16.sp)
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.trustcenter_action_revoke), color = FText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.trustcenter_action_revoke_desc), color = FMuted, fontSize = 10.sp)
                    }
                }
            }
            Text(
                stringResource(R.string.trustcenter_instruction_manual_disable),
                color = FMuted, fontSize = 10.sp, lineHeight = 14.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun CapabilityRow(icon: String, name: String, desc: String, granted: Boolean, onManage: () -> Unit) {
    FCard {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(onClick = onManage)) {
            Text(icon, fontSize = 18.sp)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(name, color = FText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(desc, color = FMuted, fontSize = 10.sp, lineHeight = 14.sp)
            }
            Spacer(Modifier.width(8.dp))
            FPill(if (granted) stringResource(R.string.trust_center_granted) else stringResource(R.string.trust_center_not_granted), if (granted) FSuccess else FMuted)
            Text("›", color = FMuted, fontSize = 18.sp, modifier = Modifier.padding(start = 6.dp))
        }
    }
}
