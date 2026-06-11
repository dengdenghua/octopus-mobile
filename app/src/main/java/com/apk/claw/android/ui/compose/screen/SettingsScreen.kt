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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val PrimaryColor = Color(0xFF6C5CE7)
private val SuccessColor = Color(0xFF00D2A0)
private val WarningColor = Color(0xFFFFC048)
private val ErrorColor = Color(0xFFFF5C72)
private val SurfaceColor = Color(0xFF1A1A28)
private val SurfaceVariantColor = Color(0xFF12121A)
private val BackgroundColor = Color(0xFF0A0A0F)
private val TextPrimary = Color(0xFFE8E8F0)
private val TextSecondary = Color(0xFF8888A8)
private val TextMuted = Color(0xFF55556A)
private val BorderColor = Color(0xFF2A2A40)

@Composable
fun SettingsScreen() {
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(BackgroundColor).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 标题
        item {
            Text("⚙ 设置", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimary, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
        }

        // 权限状态
        item {
            SettingsCard("权限状态") {
                val perms = listOf(
                    "无障碍" to true, "通知" to true,
                    "悬浮窗" to true, "电池" to true,
                    "存储" to true, "Shizuku" to false,
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

        // 模型配置
        item {
            SettingsCard("模型配置") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("gpt-4o", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("· OpenAI", fontSize = 11.sp, color = TextMuted)
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text("Base URL: https://api.openai.com/v1", fontSize = 11.sp, color = TextMuted)
                Text("API Key: sk-••••••••••••3f7a", fontSize = 11.sp, color = TextMuted)
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(shape = RoundedCornerShape(6.dp), color = PrimaryColor.copy(alpha = 0.15f)) {
                        Text("VLM ✓", modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), fontSize = 10.sp, color = PrimaryColor, fontWeight = FontWeight.SemiBold)
                    }
                    Surface(shape = RoundedCornerShape(6.dp), color = SuccessColor.copy(alpha = 0.15f)) {
                        Text("本地降级 ✓", modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), fontSize = 10.sp, color = SuccessColor, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // 消息渠道
        item {
            SettingsCard("消息渠道") {
                val channels = listOf(
                    "💬" to "钉钉" to true,
                    "🐦" to "飞书" to false,
                    "🐧" to "QQ" to false,
                    "🎮" to "Discord" to true,
                    "✈️" to "Telegram" to true,
                    "💚" to "微信" to false,
                )
                // 3 列网格
                channels.chunked(3).forEach { row ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { (iconName, connected) ->
                            val (icon, name) = iconName
                            Surface(
                                modifier = Modifier.weight(1f),
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
                                    Text(if (connected) "已连接" else "未配置", fontSize = 9.sp, color = if (connected) SuccessColor else TextMuted, fontWeight = FontWeight.SemiBold)
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
            SettingsCard("其他") {
                listOf(
                    "局域网配置" to "192.168.1.105:9527",
                    "设备管理" to "2 台设备",
                    "浏览器引擎" to "GeckoView 151",
                    "投屏控制" to "未连接",
                    "插件管理" to "3 个已加载",
                ).forEach { (label, value) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
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
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = SurfaceColor,
        border = BorderStroke(1.dp, BorderColor),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextSecondary, letterSpacing = 0.5.sp)
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}
