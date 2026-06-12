package com.apk.claw.android.ui.featurescreens

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apk.claw.android.octopus_mobile.ActivityLog

class ActivityActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ActivityScreen(onBack = { finish() }) }
        runCatching { window.statusBarColor = android.graphics.Color.BLACK }
    }
}

private val cDanger = Color(0xFFFF453B)

private fun outcomeLabel(o: String) = when (o) {
    "success" -> "成功"
    "cancelled" -> "已停止"
    else -> "失败"
}

private fun outcomeColor(o: String) = when (o) {
    "success" -> FSuccess
    "cancelled" -> FMuted
    else -> cDanger
}

private fun relTime(ts: Long): String {
    val now = System.currentTimeMillis()
    val d = now - ts
    return when {
        d < 60_000 -> "刚刚"
        d < 3_600_000 -> "${d / 60_000} 分钟前"
        d < 86_400_000 -> "${d / 3_600_000} 小时前"
        else -> java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ts))
    }
}

@Composable
fun ActivityScreen(onBack: () -> Unit) {
    var entries by remember { mutableStateOf(ActivityLog.all()) }

    FeatureScaffold(
        title = "活动",
        onBack = onBack,
        action = {
            if (entries.isNotEmpty()) {
                Text("清空", color = FPrimary, fontSize = 14.sp,
                    modifier = Modifier.clickable { ActivityLog.clear(); entries = emptyList() }.padding(8.dp))
            }
        },
    ) {
        if (entries.isEmpty()) {
            FEmpty("还没有活动记录。Agent 每完成一次任务，都会在这里留下一条可回看的审计（指令 / 目标 / 步数 / 结果）。")
            return@FeatureScaffold
        }
        LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(vertical = 6.dp)) {
            item {
                Text("共 ${entries.size} 条 · 跨所有会话与设备", color = FMuted, fontSize = 11.sp,
                    modifier = Modifier.padding(start = 16.dp, bottom = 4.dp))
            }
            items(entries, key = { it.id }) { e ->
                FCard {
                    Text(e.task, color = FText, fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 19.sp, maxLines = 2)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FPill(outcomeLabel(e.outcome), outcomeColor(e.outcome))
                        Spacer(Modifier.width(6.dp))
                        FPill(e.target, if (e.target == "本机") FPrimary else FWarning)
                        Spacer(Modifier.weight(1f))
                        Text("${e.steps} 步 · ${relTime(e.ts)}", color = FMuted, fontSize = 10.sp)
                    }
                    if (e.detail.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(e.detail, color = FSub, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 2)
                    }
                }
            }
        }
    }
}
