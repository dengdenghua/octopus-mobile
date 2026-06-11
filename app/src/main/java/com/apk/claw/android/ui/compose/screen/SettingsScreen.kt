package com.apk.claw.android.ui.compose.screen

import android.widget.Toast
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apk.claw.android.R

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
    val context = LocalContext.current
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
                val perms = listOf(
                    stringResource(R.string.perm_accessibility) to true, stringResource(R.string.perm_notification) to true,
                    stringResource(R.string.perm_overlay) to true, stringResource(R.string.perm_battery) to true,
                    stringResource(R.string.perm_storage) to true, "Shizuku" to false,
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
            SettingsCard(stringResource(R.string.settings_section_model)) {
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
                        Text(stringResource(R.string.settings_local_fallback), modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), fontSize = 10.sp, color = SuccessColor, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // 消息渠道
        item {
            SettingsCard(stringResource(R.string.settings_section_channels)) {
                val channels = listOf(
                    "💬" to stringResource(R.string.channel_dingtalk) to true,
                    "🐦" to stringResource(R.string.channel_feishu) to false,
                    "🐧" to "QQ" to false,
                    "🎮" to "Discord" to true,
                    "✈️" to "Telegram" to true,
                    "💚" to stringResource(R.string.channel_wechat) to false,
                )
                // 3 列网格
                channels.chunked(3).forEach { row ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { (iconName, connected) ->
                            val (icon, name) = iconName
                            val statusText = if (connected) stringResource(R.string.status_connected) else stringResource(R.string.status_not_configured)
                            Surface(
                                modifier = Modifier.weight(1f).clickable {
                                    Toast.makeText(context, "$name · $statusText", Toast.LENGTH_SHORT).show()
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
                listOf(
                    stringResource(R.string.settings_lan_config) to "192.168.1.105:9527",
                    stringResource(R.string.settings_device_mgmt) to stringResource(R.string.settings_val_devices),
                    stringResource(R.string.settings_browser_engine) to "GeckoView 151",
                    stringResource(R.string.settings_cast_control) to stringResource(R.string.status_not_connected),
                    stringResource(R.string.settings_plugin_mgmt) to stringResource(R.string.settings_val_plugins_loaded),
                ).forEach { (label, value) ->
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable { Toast.makeText(context, "$label · $value", Toast.LENGTH_SHORT).show() }
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
