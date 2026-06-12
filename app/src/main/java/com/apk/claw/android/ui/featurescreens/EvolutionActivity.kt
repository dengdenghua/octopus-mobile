package com.apk.claw.android.ui.featurescreens

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apk.claw.android.octopus_mobile.evolution.CanaryManager
import com.apk.claw.android.octopus_mobile.evolution.LessonStore

class EvolutionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { EvolutionScreen(onBack = { finish() }) }
        runCatching { window.statusBarColor = android.graphics.Color.BLACK }
    }
}

@Composable
fun EvolutionScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val lessons = remember { runCatching { LessonStore(ctx).getLessons() }.getOrDefault(emptyList()) }
    val canaries = remember {
        runCatching { CanaryManager(ctx.filesDir).listAll() }.getOrDefault(emptyList())
    }

    FeatureScaffold(title = "自进化", onBack = onBack) {
        if (lessons.isEmpty() && canaries.isEmpty()) {
            FEmpty("还没有进化记录。Agent 会在任务后自我反思，沉淀「教训」并通过灰度逐步生效，结果会显示在这里。")
            return@FeatureScaffold
        }
        LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
            item { FSectionTitle("已沉淀教训 (${lessons.size})") }
            if (lessons.isEmpty()) {
                item { FEmpty("暂无教训") }
            } else {
                items(lessons.size) { i ->
                    val l = lessons[i]
                    FCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (!l.tag.isNullOrBlank()) { FPill(l.tag!!, FPrimary); Spacer(Modifier.width(6.dp)) }
                            FPill(l.source, FMuted)
                            Spacer(Modifier.weight(1f))
                            Text("命中 ${l.hitCount}", color = FMuted, fontSize = 10.sp)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(l.content, color = FText, fontSize = 14.sp, lineHeight = 19.sp)
                        Spacer(Modifier.height(6.dp))
                        EffBar(l.effectiveness)
                    }
                }
            }

            item { FSectionTitle("灰度发布 (${canaries.size})") }
            if (canaries.isEmpty()) {
                item { FEmpty("暂无灰度中的能力") }
            } else {
                items(canaries.size) { i ->
                    val c = canaries[i]
                    FCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(c.skillName, color = FText, fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            FPill(c.phase.name, FWarning)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text("样本 ${c.sampleCount} · 成功 ${c.successCount} · 失败 ${c.failureCount}", color = FMuted, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun EffBar(value: Double) {
    val v = value.coerceIn(0.0, 1.0).toFloat()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier.weight(1f).height(4.dp)
                .background(FSurface2, RoundedCornerShape(2.dp))
        ) {
            Box(
                modifier = Modifier.fillMaxWidth(v).height(4.dp)
                    .background(if (v >= 0.5f) FSuccess else FWarning, RoundedCornerShape(2.dp))
            )
        }
        Spacer(Modifier.width(8.dp))
        Text("有效度 ${(v * 100).toInt()}%", color = FMuted, fontSize = 10.sp)
    }
}
