package com.apk.claw.android.ui.compose.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// 颜色常量
private val PrimaryColor = Color(0xFF6C5CE7)
private val SuccessColor = Color(0xFF00D2A0)
private val WarningColor = Color(0xFFFFC048)
private val ErrorColor = Color(0xFFFF5C72)
private val SurfaceColor = Color(0xFF1A1A28)
private val BackgroundColor = Color(0xFF0A0A0F)
private val TextPrimary = Color(0xFFE8E8F0)
private val TextSecondary = Color(0xFF8888A8)
private val TextMuted = Color(0xFF55556A)
private val BorderColor = Color(0xFF2A2A40)

@Composable
fun DiscoverScreen() {
    var searchText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundColor)
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(60.dp))

        // Logo
        Text(
            text = "🐙",
            fontSize = 42.sp,
            modifier = Modifier.shadow(20.dp, CircleShape)
        )
        Spacer(modifier = Modifier.height(6.dp))

        // 标题
        Text(
            text = "Octopus",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = PrimaryColor,
        )
        Spacer(modifier = Modifier.height(28.dp))

        // 搜索栏
        OutlinedTextField(
            value = searchText,
            onValueChange = { searchText = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("搜索或输入指令...", color = TextMuted) },
            leadingIcon = { Icon(Icons.Default.Search, null, tint = TextMuted) },
            trailingIcon = {
                // 发送按钮
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(PrimaryColor, CircleShape)
                        .clickable { /* 发送指令 */ },
                    contentAlignment = Alignment.Center
                ) {
                    Text("➤", color = Color.White, fontSize = 14.sp)
                }
            },
            shape = RoundedCornerShape(20.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = PrimaryColor,
                unfocusedBorderColor = BorderColor,
                focusedContainerColor = SurfaceColor,
                unfocusedContainerColor = SurfaceColor,
                cursorColor = PrimaryColor,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
            ),
            singleLine = true,
            textStyle = TextStyle(fontSize = 15.sp),
        )
        Spacer(modifier = Modifier.height(32.dp))

        // 快捷入口 4x2 网格
        val shortcuts = listOf(
            "🌐" to "浏览器",
            "☁️" to "网盘",
            "🎬" to "视频",
            "🧩" to "插件",
            "🖥" to "投屏",
            "📱" to "多窗口",
            "💾" to "记忆",
            "🧬" to "自进化",
        )

        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            shortcuts.chunked(4).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    row.forEach { (icon, name) ->
                        ShortcutItem(icon, name)
                    }
                }
            }
        }
        Spacer(modifier = Modifier.weight(1f))

        // AI 建议 - 底部横排
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("🌤 查天气" to "智能", "📦 整理网盘" to "效率", "▶️ 继续看剧" to "推荐").forEach { (text, tag) ->
                SuggestionChip(text, tag)
            }
        }
        Spacer(modifier = Modifier.height(12.dp))

        // 底部状态条
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("🧬 82%", color = TextMuted, fontSize = 10.sp)
            Spacer(modifier = Modifier.width(16.dp))
            Text("💾 5 条记忆", color = TextMuted, fontSize = 10.sp)
            Spacer(modifier = Modifier.width(16.dp))
            Text("🔔 2 条规则", color = TextMuted, fontSize = 10.sp)
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

// 快捷入口项
@Composable
private fun ShortcutItem(icon: String, name: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable { /* 导航 */ }
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(SurfaceColor, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(icon, fontSize = 22.sp)
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(name, fontSize = 11.sp, color = TextSecondary, fontWeight = FontWeight.Medium)
    }
}

// AI 建议标签
@Composable
private fun RowScope.SuggestionChip(text: String, tag: String) {
    Box(
        modifier = Modifier
            .weight(1f)
            .background(PrimaryColor.copy(alpha = 0.06f), RoundedCornerShape(10.dp))
            .border(1.dp, PrimaryColor.copy(alpha = 0.1f), RoundedCornerShape(10.dp))
            .clickable { }
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text, fontSize = 12.sp, color = TextSecondary)
        }
    }
}
