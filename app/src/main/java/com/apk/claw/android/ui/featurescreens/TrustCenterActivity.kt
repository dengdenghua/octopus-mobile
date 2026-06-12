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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.apk.claw.android.octopus_mobile.ControlTarget
import com.apk.claw.android.server.ConfigServerManager
import com.apk.claw.android.service.ClawAccessibilityService
import com.apk.claw.android.shizuku.ShizukuManager
import com.apk.claw.android.utils.KVUtils

class TrustCenterActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TrustCenterScreen(onBack = { finish() }) }
        runCatching { window.statusBarColor = android.graphics.Color.BLACK }
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

    FeatureScaffold(title = "信任中心", onBack = onBack) {
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text(
                "Agent 通过下列权限操作你的设备。可随时查看状态、点开管理或一键收回。",
                color = FMuted, fontSize = 12.sp, lineHeight = 17.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            FSectionTitle("本机")
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

            FSectionTitle("设备控制能力")
            CapabilityRow("♿", "无障碍服务", "核心：读屏与点击/输入（控制本机）", a11y) {
                openIntent(ctx, Settings.ACTION_ACCESSIBILITY_SETTINGS)
            }
            CapabilityRow("🪟", "悬浮窗", "实时控制层：操作时悬浮显示步骤+停止", overlay) {
                openIntent(ctx, Settings.ACTION_MANAGE_OVERLAY_PERMISSION, withPkg = true)
            }
            CapabilityRow("🔋", "电池优化豁免", "后台长时间任务不被系统杀死", battery) {
                openIntent(ctx, Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, withPkg = true)
            }
            CapabilityRow("🔔", "通知", "前台服务常驻通知 / 任务提醒", notif) {
                openIntent(ctx, Settings.ACTION_APPLICATION_DETAILS_SETTINGS, withPkg = true)
            }
            CapabilityRow("⚡", "Shizuku", "shell 级增强：小窗 / 截屏 / 系统设置", shizuku) {
                runCatching { ShizukuManager.requestPermission() }
            }
            CapabilityRow("💾", "存储", "读写媒体文件（截图保存等）", storage) {
                openIntent(ctx, Settings.ACTION_APPLICATION_DETAILS_SETTINGS, withPkg = true)
            }

            FSectionTitle("局域网")
            FCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("允许被局域网控制", color = FText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text("开启后本机才向同 Wi-Fi 广播控制 token", color = FMuted, fontSize = 10.sp, lineHeight = 14.sp)
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
                    Text("本机可被发现", color = FText, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text(lanAddr ?: "未启动", color = if (lanAddr != null) FSuccess else FMuted, fontSize = 12.sp)
                }
            }

            FSectionTitle("当前执行目标")
            FCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Agent 将操作", color = FText, fontSize = 14.sp, modifier = Modifier.weight(1f))
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
                        Text("一键收回联网控制", color = FText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text("关闭局域网广播并把执行目标改回本机", color = FMuted, fontSize = 10.sp)
                    }
                }
            }
            Text(
                "无障碍 / 悬浮窗等系统权限需在系统设置中手动关闭（点上方对应项进入）。",
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
            FPill(if (granted) "已授权" else "未授权", if (granted) FSuccess else FMuted)
            Text("›", color = FMuted, fontSize = 18.sp, modifier = Modifier.padding(start = 6.dp))
        }
    }
}
