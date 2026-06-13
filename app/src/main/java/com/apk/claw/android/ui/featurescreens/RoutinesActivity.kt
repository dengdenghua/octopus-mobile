package com.apk.claw.android.ui.featurescreens

import android.app.AlertDialog
import android.app.TimePickerDialog
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
import com.apk.claw.android.octopus_mobile.RoutineStore
import com.apk.claw.android.service.RoutineScheduler
import com.apk.claw.android.ui.compose.screen.RoutineRunner
import java.util.Calendar

/**
 * 例程页 —— 列出已保存的例程，一键重放，可设定时。
 *
 * 例程来源：在对话里长按你发过的指令 →「存为例程」。
 * 重放（[RoutineRunner]）：恢复目标设备 → 用原始指令调起 Agent；进度走悬浮控制层，结果落审计。
 * 定时（[RoutineScheduler]）：AlarmManager 到点由 RoutineAlarmReceiver 重放。
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
            FEmpty("还没有例程。\n\n在对话里长按你发过的一条指令 →「存为例程」，这里就能一键重放或设定时。重放会让 Agent 按指令重新看屏规划执行。")
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
                                    buildString {
                                        append("目标 ${r.targetLabel} · 运行 ${r.runCount} 次")
                                        if (r.isScheduled) {
                                            val t = "%02d:%02d".format(r.scheduleHour, r.scheduleMinute)
                                            append(if (r.scheduleDaily) " · ⏰ 每天 $t" else " · ⏰ 一次 $t")
                                        }
                                    },
                                    color = if (r.isScheduled) FPrimary else FMuted, fontSize = 10.sp,
                                )
                            }
                            Text(
                                "⏰", fontSize = 16.sp,
                                modifier = Modifier
                                    .clickable { openSchedule(ctx, r) { refresh() } }
                                    .padding(horizontal = 6.dp, vertical = 4.dp),
                            )
                            Text(
                                "▶ 运行", color = FPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .clickable { Toast.makeText(ctx, RoutineRunner.run(ctx, r), Toast.LENGTH_LONG).show(); refresh() }
                                    .padding(horizontal = 6.dp, vertical = 4.dp),
                            )
                            Text(
                                "✕", color = FMuted, fontSize = 14.sp,
                                modifier = Modifier
                                    .clickable { RoutineScheduler.cancel(ctx, r.id); RoutineStore.remove(r.id); refresh() }
                                    .padding(4.dp),
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

/** ⏰ 选时间 → 选「每天 / 仅一次 / 取消定时」→ 写库 + 注册/取消闹钟。 */
private fun openSchedule(ctx: Context, r: RoutineStore.Routine, onChanged: () -> Unit) {
    val now = Calendar.getInstance()
    val h0 = r.scheduleHour ?: now.get(Calendar.HOUR_OF_DAY)
    val m0 = r.scheduleMinute ?: now.get(Calendar.MINUTE)
    TimePickerDialog(ctx, { _, h, m ->
        val t = "%02d:%02d".format(h, m)
        AlertDialog.Builder(ctx)
            .setTitle("$t 定时")
            .setItems(arrayOf("每天重复", "仅一次", "取消定时")) { _, which ->
                when (which) {
                    0 -> {
                        val nr = r.copy(scheduleHour = h, scheduleMinute = m, scheduleDaily = true)
                        RoutineStore.update(nr); RoutineScheduler.schedule(ctx, nr)
                        Toast.makeText(ctx, "已设为每天 $t", Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
                        val nr = r.copy(scheduleHour = h, scheduleMinute = m, scheduleDaily = false)
                        RoutineStore.update(nr); RoutineScheduler.schedule(ctx, nr)
                        Toast.makeText(ctx, "已设为一次 $t（下一个 $t 触发）", Toast.LENGTH_SHORT).show()
                    }
                    2 -> {
                        RoutineStore.update(r.copy(scheduleHour = null, scheduleMinute = null, scheduleDaily = false))
                        RoutineScheduler.cancel(ctx, r.id)
                        Toast.makeText(ctx, "已取消定时", Toast.LENGTH_SHORT).show()
                    }
                }
                onChanged()
            }
            .show()
    }, h0, m0, true).show()
}
