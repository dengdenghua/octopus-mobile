package com.apk.claw.android.ui.featurescreens

import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apk.claw.android.R
import com.apk.claw.android.octopus_mobile.ActionCache
import com.apk.claw.android.octopus_mobile.RoutineStore
import com.apk.claw.android.service.RoutineScheduler
import com.apk.claw.android.ui.compose.screen.RoutineRunner
import com.apk.claw.android.ui.compose.theme.OctopusColors
import com.apk.claw.android.ui.compose.theme.OctopusIconSize
import com.apk.claw.android.ui.compose.theme.OctopusShape
import com.apk.claw.android.ui.compose.theme.OctopusSpacing
import com.apk.claw.android.ui.compose.theme.OctopusTints
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
        setFeatureContent { RoutinesScreen(onBack = { finish() }) }
    }
}

@Composable
private fun RoutinesScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var items by remember { mutableStateOf(RoutineStore.all()) }
    fun refresh() { items = RoutineStore.all() }

    FeatureScaffold(title = stringResource(R.string.routines_title), onBack = onBack) {
        if (items.isEmpty()) {
            FEmpty(stringResource(R.string.routines_empty_state))
        } else {
            LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(vertical = 6.dp)) {
                item {
                    RoutinesSummaryCard(items)
                }
                items(items, key = { it.id }) { r ->
                    RoutineCard(
                        routine = r,
                        onForgetFastPath = { forgetFastPath(ctx, r) { refresh() } },
                        onSchedule = { openSchedule(ctx, r) { refresh() } },
                        onRun = { Toast.makeText(ctx, RoutineRunner.run(ctx, r), Toast.LENGTH_LONG).show(); refresh() },
                        onDelete = {
                            RoutineScheduler.cancel(ctx, r.id)
                            ActionCache.remove(r.id)
                            RoutineStore.remove(r.id)
                            refresh()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun RoutinesSummaryCard(items: List<RoutineStore.Routine>) {
    val scheduled = items.count { it.isScheduled }
    val inspiration = items.count { it.id.startsWith("inspiration-") }
    FCard {
        Text("自动化例程", color = FText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(OctopusSpacing.xs))
        Text(
            "把灵感、对话指令和定时任务沉淀成可重复执行的动作。",
            color = FSub,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )
        Spacer(Modifier.height(OctopusSpacing.md))
        Row(horizontalArrangement = Arrangement.spacedBy(OctopusSpacing.sm), modifier = Modifier.fillMaxWidth()) {
            SummaryMetric("总数", "${items.size}", OctopusTints.Routine, Modifier.weight(1f))
            SummaryMetric("已定时", "$scheduled", OctopusTints.Hot, Modifier.weight(1f))
            SummaryMetric("灵感复刻", "$inspiration", OctopusTints.Skill, Modifier.weight(1f))
        }
    }
}

@Composable
private fun SummaryMetric(label: String, value: String, tint: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(tint.copy(alpha = if (OctopusColors.isLight) 0.11f else 0.16f), OctopusShape.medium)
            .padding(vertical = OctopusSpacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, color = FMuted, fontSize = 10.sp, maxLines = 1)
        Text(value, color = tint, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun RoutineCard(
    routine: RoutineStore.Routine,
    onForgetFastPath: () -> Unit,
    onSchedule: () -> Unit,
    onRun: () -> Unit,
    onDelete: () -> Unit,
) {
    val cached = ActionCache.get(routine.id, routine.prompt)
    val sourceTint = if (routine.id.startsWith("inspiration-")) OctopusTints.Skill else OctopusTints.Routine
    FCard {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(sourceTint.copy(alpha = 0.16f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (routine.id.startsWith("inspiration-")) "灵" else "例", color = sourceTint, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(OctopusSpacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        routine.name,
                        color = FText,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    FPill(if (routine.id.startsWith("inspiration-")) "灵感复刻" else "手动保存", sourceTint)
                }
                Spacer(Modifier.height(OctopusSpacing.xs))
                Row(horizontalArrangement = Arrangement.spacedBy(OctopusSpacing.xs)) {
                    FPill("目标 ${routine.targetLabel}", OctopusTints.Window)
                    FPill("运行 ${routine.runCount} 次", OctopusTints.Browser)
                    if (routine.isScheduled) {
                        FPill("%02d:%02d".format(routine.scheduleHour, routine.scheduleMinute), OctopusTints.Hot)
                    }
                    cached?.let { FPill("快路径 ${it.steps.size} 步", FPrimary) }
                }
            }
        }
        if (routine.prompt != routine.name) {
            Spacer(Modifier.height(OctopusSpacing.md))
            Text(routine.prompt, color = FSub, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(OctopusSpacing.md))
        Row(horizontalArrangement = Arrangement.spacedBy(OctopusSpacing.sm), modifier = Modifier.fillMaxWidth()) {
            if (cached != null) {
                RoutineIconAction("忘", FPrimary, onForgetFastPath)
            }
            RoutineIconAction(Icons.Filled.Alarm, FWarning, onSchedule)
            RoutineIconAction(Icons.Filled.PlayArrow, FPrimary, onRun, modifier = Modifier.weight(1f), label = stringResource(R.string.routines_item_run_button))
            RoutineIconAction(Icons.Filled.DeleteOutline, FMuted, onDelete)
        }
    }
}

@Composable
private fun RoutineIconAction(
    text: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .background(tint.copy(alpha = 0.12f), OctopusShape.capsule)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = tint, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun RoutineIconAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    Row(
        modifier = modifier
            .height(38.dp)
            .background(tint.copy(alpha = 0.12f), OctopusShape.capsule)
            .clickable(onClick = onClick)
            .padding(horizontal = OctopusSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(OctopusIconSize.small))
        label?.let {
            Spacer(Modifier.width(OctopusSpacing.xs))
            Text(it, color = tint, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** ⚡ 忘记快路径 —— 下次运行让 Agent 重新看屏规划并重新录制。 */
private fun forgetFastPath(ctx: Context, r: RoutineStore.Routine, onChanged: () -> Unit) {
    val n = ActionCache.get(r.id, r.prompt)?.steps?.size ?: 0
    AlertDialog.Builder(ctx)
        .setTitle(ctx.getString(R.string.routines_forget_fastpath_title))
        .setMessage(ctx.getString(R.string.routines_forget_fastpath_message, r.name, n))
        .setPositiveButton(ctx.getString(R.string.routines_forget_button)) { _, _ ->
            ActionCache.remove(r.id)
            Toast.makeText(ctx, ctx.getString(R.string.routines_forgotten_toast), Toast.LENGTH_SHORT).show()
            onChanged()
        }
        .setNegativeButton(ctx.getString(R.string.common_cancel), null)
        .show()
}

/** ⏰ 选时间 → 选「每天 / 仅一次 / 取消定时」→ 写库 + 注册/取消闹钟。 */
private fun openSchedule(ctx: Context, r: RoutineStore.Routine, onChanged: () -> Unit) {
    val now = Calendar.getInstance()
    val h0 = r.scheduleHour ?: now.get(Calendar.HOUR_OF_DAY)
    val m0 = r.scheduleMinute ?: now.get(Calendar.MINUTE)
    TimePickerDialog(ctx, { _, h, m ->
        val t = "%02d:%02d".format(h, m)
        AlertDialog.Builder(ctx)
            .setTitle(ctx.getString(R.string.routines_schedule_dialog_title, t))
            .setItems(arrayOf(ctx.getString(R.string.routines_schedule_daily_option), ctx.getString(R.string.routines_schedule_once_option), ctx.getString(R.string.routines_schedule_cancel_option))) { _, which ->
                when (which) {
                    0 -> {
                        val nr = r.copy(scheduleHour = h, scheduleMinute = m, scheduleDaily = true)
                        RoutineStore.update(nr); RoutineScheduler.schedule(ctx, nr)
                        Toast.makeText(ctx, ctx.getString(R.string.routines_set_daily_toast, t), Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
                        val nr = r.copy(scheduleHour = h, scheduleMinute = m, scheduleDaily = false)
                        RoutineStore.update(nr); RoutineScheduler.schedule(ctx, nr)
                        Toast.makeText(ctx, ctx.getString(R.string.routines_set_once_toast, t), Toast.LENGTH_SHORT).show()
                    }
                    2 -> {
                        RoutineStore.update(r.copy(scheduleHour = null, scheduleMinute = null, scheduleDaily = false))
                        RoutineScheduler.cancel(ctx, r.id)
                        Toast.makeText(ctx, ctx.getString(R.string.routines_cancel_scheduling_toast), Toast.LENGTH_SHORT).show()
                    }
                }
                onChanged()
            }
            .show()
    }, h0, m0, true).show()
}
