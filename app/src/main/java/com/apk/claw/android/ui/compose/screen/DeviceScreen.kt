package com.apk.claw.android.ui.compose.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apk.claw.android.R

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

data class DeviceInfo(
    val id: String, val name: String, val type: String,
    val model: String, val status: String, val battery: Int,
    val currentApp: String, val ip: String, val isLocal: Boolean,
)

@Composable
fun DeviceScreen(onMessage: (String) -> Unit = {}) {
    var selectedDeviceId by remember { mutableStateOf("local") }
    var browserTab by remember { mutableStateOf(0) } // 0=tabs, 1=extensions
    var visionOn by remember { mutableStateOf(true) }

    val devices = remember {
        listOf(
            DeviceInfo("local", "我的手机", "phone", "Xiaomi 14", "online", 72, "微信", "192.168.1.105", true),
            DeviceInfo("tv1", "客厅电视", "tv", "Mi TV Stick 4K", "online", -1, "YouTube", "192.168.1.203", false),
            DeviceInfo("phone2", "备用机", "phone", "Pixel 7", "offline", 15, "-", "192.168.1.178", false),
        )
    }

    val selected = devices.find { it.id == selectedDeviceId }

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
                val onlineCount = devices.count { it.status == "online" }
                Text(stringResource(R.string.device_online_count, onlineCount, devices.size), fontSize = 11.sp, color = TextMuted)
            }
        }

        // 设备列表 - 横排 3 卡片
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                devices.forEach { device ->
                    DeviceCard(
                        device = device,
                        selected = device.id == selectedDeviceId,
                        onClick = { selectedDeviceId = device.id },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // 选中设备详情
        if (selected != null) {
            item {
                DeviceDetailCard(selected, onMessage)
            }
        }

        // 浏览器引擎
        item {
            BrowserCard(browserTab) { browserTab = it }
        }

        // 投屏 + 视觉理解 并排
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // 投屏
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                    color = SurfaceColor,
                    border = BorderStroke(1.dp, BorderColor),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(stringResource(R.string.device_cast), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextSecondary)
                        Spacer(modifier = Modifier.height(8.dp))
                        Box(
                            modifier = Modifier.fillMaxWidth().height(48.dp).background(SurfaceVariantColor, RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(stringResource(R.string.device_cast_disconnected), fontSize = 10.sp, color = TextMuted)
                        }
                    }
                }
                // 视觉理解
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                    color = SurfaceColor,
                    border = BorderStroke(1.dp, BorderColor),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.device_vision), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextSecondary)
                            Spacer(modifier = Modifier.weight(1f))
                            // 开关（可点击切换）
                            Box(
                                modifier = Modifier
                                    .size(30.dp, 16.dp)
                                    .background(
                                        if (visionOn) SuccessColor else TextMuted.copy(alpha = 0.4f),
                                        RoundedCornerShape(8.dp)
                                    )
                                    .clickable { visionOn = !visionOn }
                                    .padding(2.dp),
                                contentAlignment = if (visionOn) Alignment.CenterEnd else Alignment.CenterStart,
                            ) {
                                Box(modifier = Modifier.size(12.dp).background(Color.White, CircleShape))
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(stringResource(R.string.device_vision_desc), fontSize = 10.sp, color = TextMuted, lineHeight = 14.sp)
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(10.dp)) }
    }
}

@Composable
private fun DeviceCard(device: DeviceInfo, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) PrimaryColor.copy(alpha = 0.1f) else SurfaceColor,
        border = BorderStroke(1.dp, if (selected) PrimaryColor.copy(alpha = 0.3f) else BorderColor),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (device.type == "tv") "📺" else "📱", fontSize = 16.sp)
                Spacer(modifier = Modifier.width(6.dp))
                Text(device.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                if (device.isLocal) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Surface(shape = RoundedCornerShape(4.dp), color = SuccessColor.copy(alpha = 0.15f)) {
                        Text(stringResource(R.string.device_this_device), modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp), fontSize = 8.sp, color = SuccessColor, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(device.model, fontSize = 10.sp, color = TextMuted)
                Text(if (device.status == "online") stringResource(R.string.status_online) else stringResource(R.string.status_offline), fontSize = 10.sp, color = if (device.status == "online") SuccessColor else TextMuted)
            }
        }
    }
}

@Composable
private fun DeviceDetailCard(device: DeviceInfo, onMessage: (String) -> Unit = {}) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = SurfaceColor,
        border = BorderStroke(1.dp, BorderColor),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${device.name} · ${device.currentApp}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextSecondary)
                if (device.battery >= 0) Text("🔋 ${device.battery}%", fontSize = 10.sp, color = TextMuted)
            }
            Spacer(modifier = Modifier.height(10.dp))
            // 远程控制按钮
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    "📸" to stringResource(R.string.ctrl_screenshot),
                    "👆" to stringResource(R.string.ctrl_tap),
                    "📱" to stringResource(R.string.ctrl_open),
                    "🏠" to "Home",
                    "⬅️" to stringResource(R.string.ctrl_back),
                ).forEach { (icon, label) ->
                    Column(
                        modifier = Modifier.weight(1f).background(SurfaceVariantColor, RoundedCornerShape(8.dp))
                            .clickable { onMessage("$label → ${device.name}") }
                            .padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(icon, fontSize = 16.sp)
                        Text(label, fontSize = 10.sp, color = TextSecondary)
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            // 多窗口
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Surface(shape = RoundedCornerShape(8.dp), color = SurfaceVariantColor) {
                    Row(modifier = Modifier.padding(6.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("📱", fontSize = 12.sp)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("微信", fontSize = 11.sp, color = TextPrimary)
                        Spacer(modifier = Modifier.width(4.dp))
                        Surface(shape = RoundedCornerShape(4.dp), color = PrimaryColor.copy(alpha = 0.15f)) {
                            Text("freeform", modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp), fontSize = 8.sp, color = PrimaryColor, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                Surface(shape = RoundedCornerShape(8.dp), color = SurfaceVariantColor) {
                    Row(modifier = Modifier.padding(6.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("📺", fontSize = 12.sp)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("YouTube", fontSize = 11.sp, color = TextPrimary)
                        Spacer(modifier = Modifier.width(4.dp))
                        Surface(shape = RoundedCornerShape(4.dp), color = WarningColor.copy(alpha = 0.15f)) {
                            Text("display:1", modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp), fontSize = 8.sp, color = WarningColor, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowserCard(selectedTab: Int, onTabChange: (Int) -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = SurfaceColor,
        border = BorderStroke(1.dp, BorderColor),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.device_browser), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextSecondary)
                Spacer(modifier = Modifier.weight(1f))
                Surface(shape = RoundedCornerShape(4.dp), color = SuccessColor.copy(alpha = 0.15f)) {
                    Text("GeckoView 151", modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), fontSize = 8.sp, color = SuccessColor, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            // Tab 切换
            Row(modifier = Modifier.fillMaxWidth().background(SurfaceVariantColor, RoundedCornerShape(12.dp)).padding(3.dp)) {
                listOf(stringResource(R.string.device_tabs), stringResource(R.string.device_extensions)).forEachIndexed { index, label ->
                    Surface(
                        modifier = Modifier.weight(1f).clickable { onTabChange(index) },
                        shape = RoundedCornerShape(10.dp),
                        color = if (selectedTab == index) PrimaryColor else Color.Transparent,
                    ) {
                        Text(label, modifier = Modifier.padding(8.dp).fillMaxWidth(), fontSize = 11.sp, fontWeight = FontWeight.Medium, color = if (selectedTab == index) Color.White else TextMuted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            if (selectedTab == 0) {
                // 标签页
                listOf("京东 - iPhone 16 Pro" to "jd.com", "GitHub - octopus-mobile" to "github.com").forEachIndexed { i, (title, url) ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (i == 0) PrimaryColor.copy(alpha = 0.08f) else SurfaceVariantColor,
                        border = BorderStroke(1.dp, if (i == 0) PrimaryColor.copy(alpha = 0.2f) else Color.Transparent),
                    ) {
                        Row(modifier = Modifier.padding(6.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("📄", fontSize = 12.sp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(title, fontSize = 11.sp, color = TextPrimary, modifier = Modifier.weight(1f))
                            if (i == 0) {
                                Surface(shape = RoundedCornerShape(4.dp), color = PrimaryColor.copy(alpha = 0.15f)) {
                                    Text(stringResource(R.string.badge_active), modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp), fontSize = 8.sp, color = PrimaryColor, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                    if (i == 0) Spacer(modifier = Modifier.height(4.dp))
                }
            } else {
                // 扩展
                listOf("🛡️" to "AdBlock Plus", "🐙" to "Octopus Agent").forEach { (icon, name) ->
                    Row(modifier = Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(icon, fontSize = 14.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(name, fontSize = 12.sp, color = TextPrimary, modifier = Modifier.weight(1f))
                        Box(modifier = Modifier.size(30.dp, 16.dp).background(PrimaryColor, RoundedCornerShape(8.dp)))
                    }
                }
            }
        }
    }
}
