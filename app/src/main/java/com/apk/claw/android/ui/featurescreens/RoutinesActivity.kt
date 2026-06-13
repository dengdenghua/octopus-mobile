package com.apk.claw.android.ui.featurescreens

import android.content.Context
import android.os.Bundle
import android.widget.Toast
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.octopus_mobile.ControlTarget
import com.apk.claw.android.octopus_mobile.RoutineStore
import com.apk.claw.android.ui.compose.screen.ChatAgentBridge

/**
 * 例程页 —— 列出已保存的例程，一键重放。
 *
 * 例程来源：在对话里长按你发过的指令 →「存为例程」。
 * 重放：恢复该例程保存的目标设备 → 用原始指令调起 Agent（[ChatAgentBridge.run]），
 * 实时进度走悬浮控制层（LiveControlOverlay，含停止键），结果落活动审计。
 */
class RoutinesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { RoutinesScreen(onBack = { finish() }) }
        runCatching { window.statusBarColor = android.graphics.Color.BLACK }
    }
}

@Composable
private fun RoutinesScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var items by remember { mutableStateOf(RoutineStore.all()) }
    fun refresh() { items = RoutineStore.all() }

    FeatureScaffold(title = "例程", onBack = onBack) {
        if (items.isEmpty()) {
            FEmpty("还没有例程。\n\n在对话里长按你发过的一条指令 →「存为例程」，这里就能一键重放。重放会让 Agent 按指令重新看屏规划执行。")
        } else {
            LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(vertical = 6.dp)) {
                item {
                    Text(
                        "共 ${items.size} 条 · 重放让 Agent 按指令重新规划执行",
                        color = FMuted, fontSize = 11.sp,
                        modifier = Modifier.padding(start = 16.dp, bottom = 4.dp),
                    )
                }
                items(items, key = { it.id }) { r ->
                    FCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(r.name, color = FText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "目标 ${r.targetLabel} · 运行 ${r.runCount} 次",
                                    color = FMuted, fontSize = 10.sp,
                                )
                            }
                            Text(
                                "▶ 运行", color = FPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .clickable { runRoutine(ctx, r); refresh() }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                            Text(
                                "✕", color = FMuted, fontSize = 14.sp,
                                modifier = Modifier.clickable { RoutineStore.remove(r.id); refresh() }.padding(4.dp),
                            )
                        }
                        if (r.prompt != r.name) {
                            Spacer(Modifier.height(6.dp))
                            Text(r.prompt, color = FSub, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 3)
                        }
                    }
                }
            }
        }
    }
}

/** 恢复目标设备 → 用原始指令重放。 */
private fun runRoutine(ctx: Context, r: RoutineStore.Routine) {
    // 恢复目标
    if (r.targetId.isBlank() || r.targetId == "local") {
        ControlTarget.setLocal()
    } else {
        val dev = ClawApplication.instance.deviceRegistry.getDevice(r.targetId)
        if (dev != null && dev.online) {
            ControlTarget.setRemote(dev)
        } else {
            ControlTarget.setLocal()
            Toast.makeText(ctx, "目标「${r.targetLabel}」不在线，改在本机执行", Toast.LENGTH_SHORT).show()
        }
    }

    if (!ChatAgentBridge.isConfigured()) {
        Toast.makeText(ctx, "未配置模型，请到 设置 → 模型配置", Toast.LENGTH_LONG).show()
        return
    }

    RoutineStore.touch(r.id)
    Toast.makeText(ctx, "开始运行：${r.name}", Toast.LENGTH_LONG).show()
    // 进度与停止由悬浮控制层负责；结果落活动审计。这里回调留空即可。
    ChatAgentBridge.run(
        prompt = r.prompt,
        onTool = { _, _, _, _ -> },
        onText = { },
        onDone = { },
        onError = { msg -> },
    )
}
