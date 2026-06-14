package com.apk.claw.android.ui.compose.screen

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apk.claw.android.R
import com.apk.claw.android.octopus_mobile.RoutineStore
import com.apk.claw.android.tool.ToolRegistry
import com.apk.claw.android.ui.compose.theme.OctopusColors
import com.apk.claw.android.ui.featurescreens.BrowserSettingsActivity
import com.apk.claw.android.ui.featurescreens.CloudDriveActivity
import com.apk.claw.android.ui.featurescreens.EvolutionActivity
import com.apk.claw.android.ui.featurescreens.ExtensionsActivity
import com.apk.claw.android.ui.featurescreens.MemoryActivity
import com.apk.claw.android.ui.featurescreens.MultiWindowActivity
import com.apk.claw.android.ui.featurescreens.RoutinesActivity
import com.apk.claw.android.ui.featurescreens.SkillsActivity
import com.apk.claw.android.ui.featurescreens.TrustCenterActivity
import com.apk.claw.android.ui.featurescreens.VideoLibraryActivity

/**
 * 「广场」中心 —— 单页工具箱 + 探索发现入口
 * 工具箱：双列卡片网格（技能/插件/云盘/记忆…），每张卡片带描述
 * 探索发现：从右上角入口进入（暂为占位）
 */
private data class FeatureItem(
    val labelRes: Int,
    val descRes: Int,
    val icon: ImageVector,
    val tint: Color,
    val target: Class<*>,
)

private val SkillTint = Color(0xFF74A7FF)
private val PluginTint = Color(0xFFEAB85C)
private val RoutineTint = Color(0xFFFF9F5A)
private val CloudTint = Color(0xFF42C893)
private val MemoryTint = Color(0xFF9B8CFF)
private val VideoTint = Color(0xFFFF6B6B)
private val WindowTint = Color(0xFF5AA0FF)
private val EvolveTint = Color(0xFF34C7A8)
private val TrustTint = Color(0xFF8FD8B1)
private val BrowserTint = Color(0xFF5DBCD8)

@Composable
fun FeatureHubScreen() {
    val ctx = LocalContext.current
    var showExplore by remember { mutableStateOf(false) }

    val sections = listOf(
        R.string.feat_section_automation to listOf(
            FeatureItem(R.string.feat_skills, R.string.feat_skills_desc, Icons.Filled.Bolt, SkillTint, SkillsActivity::class.java),
            FeatureItem(R.string.feat_extensions, R.string.feat_extensions_desc, Icons.Filled.Extension, PluginTint, ExtensionsActivity::class.java),
            FeatureItem(R.string.feat_routines, R.string.feat_routines_desc, Icons.Filled.Schedule, RoutineTint, RoutinesActivity::class.java),
        ),
        R.string.feat_section_data to listOf(
            FeatureItem(R.string.feat_clouddrive, R.string.feat_clouddrive_desc, Icons.Filled.CloudQueue, CloudTint, CloudDriveActivity::class.java),
            FeatureItem(R.string.feat_memory, R.string.feat_memory_desc, Icons.Filled.Psychology, MemoryTint, MemoryActivity::class.java),
            FeatureItem(R.string.feat_video, R.string.feat_video_desc, Icons.Filled.Movie, VideoTint, VideoLibraryActivity::class.java),
        ),
        R.string.feat_section_advanced to listOf(
            FeatureItem(R.string.feat_browser_settings, R.string.feat_browser_desc, Icons.Filled.Public, BrowserTint, BrowserSettingsActivity::class.java),
            FeatureItem(R.string.feat_multiwindow, R.string.feat_multiwindow_desc, Icons.Filled.GridView, WindowTint, MultiWindowActivity::class.java),
            FeatureItem(R.string.feat_evolution, R.string.feat_evolution_desc, Icons.Filled.TrendingUp, EvolveTint, EvolutionActivity::class.java),
            FeatureItem(R.string.feat_trust, R.string.feat_trust_desc, Icons.Filled.Shield, TrustTint, TrustCenterActivity::class.java),
        ),
    )

    Column(modifier = Modifier.fillMaxSize().background(OctopusColors.Background).statusBarsPadding()) {
        // 顶栏：标题 + 探索入口
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.feat_toolbox_title),
                    color = OctopusColors.TextPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            // 探索发现入口按钮
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = OctopusColors.Primary.copy(alpha = 0.10f),
                border = BorderStroke(1.dp, OctopusColors.Primary.copy(alpha = 0.20f)),
                modifier = Modifier.clickable { showExplore = true },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Explore, contentDescription = null, tint = OctopusColors.Primary, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.feat_explore), color = OctopusColors.Primary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        if (showExplore) {
            ExploreTab(onBack = { showExplore = false })
        } else {
            ToolboxTab(sections) { ctx.open(it.target) }
        }
    }
}

/** 工具箱：双列卡片网格，每张卡片带图标+名称+描述，自适应高度 */
@Composable
private fun ToolboxTab(sections: List<Pair<Int, List<FeatureItem>>>, onClick: (FeatureItem) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 112.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        sections.forEach { (headerRes, items) ->
            item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader(stringResource(headerRes)) }
            items(items) { item -> ToolCard(item) { onClick(item) } }
        }
    }
}

@Composable
private fun ToolCard(item: FeatureItem, onClick: () -> Unit) {
    Surface(
        color = OctopusColors.Surface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, OctopusColors.Border.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Box(
                modifier = Modifier.size(40.dp).background(item.tint.copy(alpha = 0.16f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(item.icon, contentDescription = null, tint = item.tint, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(item.labelRes),
                color = OctopusColors.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                stringResource(item.descRes),
                color = OctopusColors.TextMuted,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                maxLines = 2,
            )
        }
    }
}

/** 探索发现：接真实数据——技能能力入口 + 我的例程卡片(空则引导创建)。 */
@Composable
private fun ExploreTab(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val routines = remember { runCatching { RoutineStore.all() }.getOrDefault(emptyList()) }
    val toolCount = remember { runCatching { ToolRegistry.getInstance().getAllTools().size }.getOrDefault(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        // 返回 + 标题
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.advanced_action_close), tint = OctopusColors.TextPrimary)
            }
            Text(
                stringResource(R.string.feat_explore_title),
                color = OctopusColors.TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 112.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 技能能力(整行入口)
            item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader("技能能力") }
            item(span = { GridItemSpan(maxLineSpan) }) {
                ExploreEntryCard(Icons.Filled.Bolt, SkillTint, "全部技能", "Agent 可调用的 $toolCount 项能力") {
                    ctx.open(SkillsActivity::class.java)
                }
            }
            // 我的例程
            item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader("我的例程") }
            if (routines.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    ExploreEntryCard(Icons.Filled.Schedule, RoutineTint, "创建例程", "把常用指令存成一键例程,在这里复用") {
                        ctx.open(RoutinesActivity::class.java)
                    }
                }
            } else {
                items(routines) { r -> RoutineCard(r) { ctx.open(RoutinesActivity::class.java) } }
            }
        }
    }
}

/** 例程卡片:名称 + 指令摘要 + 运行次数/目标。 */
@Composable
private fun RoutineCard(routine: RoutineStore.Routine, onClick: () -> Unit) {
    Surface(
        color = OctopusColors.Surface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, OctopusColors.Border.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Box(
                modifier = Modifier.size(40.dp).background(RoutineTint.copy(alpha = 0.16f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Schedule, contentDescription = null, tint = RoutineTint, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.height(10.dp))
            Text(routine.name, color = OctopusColors.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Spacer(Modifier.height(3.dp))
            Text(routine.prompt, color = OctopusColors.TextMuted, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 2)
            Spacer(Modifier.height(6.dp))
            Text("运行 ${routine.runCount} 次 · ${routine.targetLabel}", color = OctopusColors.TextMuted, fontSize = 10.sp, maxLines = 1)
        }
    }
}

/** 整行入口卡片(图标 + 标题 + 描述 + 右箭头)。 */
@Composable
private fun ExploreEntryCard(icon: ImageVector, tint: Color, title: String, desc: String, onClick: () -> Unit) {
    Surface(
        color = OctopusColors.Surface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, OctopusColors.Border.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick),
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(40.dp).background(tint.copy(alpha = 0.16f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = OctopusColors.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                Spacer(Modifier.height(2.dp))
                Text(desc, color = OctopusColors.TextMuted, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 2)
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = OctopusColors.TextMuted.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        color = OctopusColors.TextMuted,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 14.dp, bottom = 2.dp),
    )
}

private fun Context.open(target: Class<*>) {
    runCatching { startActivity(Intent(this, target)) }
}
