package com.apk.claw.android.ui.compose.screen

import android.content.Context
import android.content.Intent
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PeopleOutline
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Weekend
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apk.claw.android.R
import com.apk.claw.android.ui.compose.theme.OctopusColors
import com.apk.claw.android.ui.compose.theme.OctopusIconSize
import com.apk.claw.android.ui.compose.theme.OctopusShape
import com.apk.claw.android.ui.compose.theme.OctopusSpacing
import com.apk.claw.android.ui.compose.theme.OctopusType
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
 * 「广场」中心 —— 双 Tab:
 *   - 探索: 社交圈子/热门/推荐（INS 风格占位，待接内容源）
 *   - 工具箱: 双列卡片网格（技能/插件/云盘/记忆…），每张带描述
 */
private data class FeatureItem(
    val labelRes: Int,
    val descRes: Int,
    val icon: ImageVector,
    val tint: Color,
    val target: Class<*>,
)

private val SkillTint get() = com.apk.claw.android.ui.compose.theme.OctopusTints.Skill
private val PluginTint get() = com.apk.claw.android.ui.compose.theme.OctopusTints.Plugin
private val RoutineTint get() = com.apk.claw.android.ui.compose.theme.OctopusTints.Routine
private val CloudTint get() = com.apk.claw.android.ui.compose.theme.OctopusTints.Cloud
private val MemoryTint get() = com.apk.claw.android.ui.compose.theme.OctopusTints.Memory
private val VideoTint get() = com.apk.claw.android.ui.compose.theme.OctopusTints.Video
private val WindowTint get() = com.apk.claw.android.ui.compose.theme.OctopusTints.Window
private val EvolveTint get() = com.apk.claw.android.ui.compose.theme.OctopusTints.Evolve
private val TrustTint get() = com.apk.claw.android.ui.compose.theme.OctopusTints.Trust
private val BrowserTint get() = com.apk.claw.android.ui.compose.theme.OctopusTints.Browser

// ── 探索页占位数据 ──
private data class CirclePreview(
    val nameRes: Int,
    val icon: ImageVector,
    val tint: Color,
    val members: String,
    val tagRes: Int,
)

private val sampleCircles = listOf(
    CirclePreview(R.string.circle_ai_players, Icons.Filled.SmartToy, SkillTint, "1.2k", R.string.circle_tag_hot),
    CirclePreview(R.string.circle_efficiency, Icons.Filled.Bolt, RoutineTint, "856", R.string.circle_tag_recommended),
    CirclePreview(R.string.circle_digital, Icons.Filled.PhoneAndroid, WindowTint, "2.3k", R.string.circle_tag_hot),
    CirclePreview(R.string.circle_geek, Icons.Filled.Code, MemoryTint, "634", R.string.circle_tag_new),
    CirclePreview(R.string.circle_lazy, Icons.Filled.Weekend, CloudTint, "1.8k", R.string.circle_tag_recommended),
    CirclePreview(R.string.circle_tv_cast, Icons.Filled.Tv, VideoTint, "421", R.string.circle_tag_new),
)

private fun featureSections(): List<Pair<Int, List<FeatureItem>>> = listOf(
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

@Composable
fun FeatureHubScreen(
    onNavigateToAgentSquare: () -> Unit = {},
) {
    val ctx = LocalContext.current
    var tab by remember { mutableStateOf(0) }

    val sections = remember { featureSections() }

    Column(modifier = Modifier.fillMaxSize().background(OctopusColors.Background).statusBarsPadding()) {
        // 顶部双 Tab
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = OctopusSpacing.xl, end = OctopusSpacing.xl, top = OctopusSpacing.md, bottom = OctopusSpacing.xs),
            verticalAlignment = Alignment.Bottom,
        ) {
            TabLabel(stringResource(R.string.feat_explore_title), selected = tab == 0) { tab = 0 }
            Spacer(Modifier.width(OctopusSpacing.xl))
            TabLabel(stringResource(R.string.feat_toolbox_title), selected = tab == 1) { tab = 1 }
        }

        when (tab) {
            0 -> ExploreTab(
                onOpenActivity = { ctx.open(it) },
                onNavigateToAgentSquare = onNavigateToAgentSquare,
            )
            else -> ToolboxTab(sections) { ctx.open(it.target) }
        }
    }
}

/** 顶部 Tab 文本：选中=大号加粗+主题色下划线；未选=灰色常规。 */
@Composable
private fun TabLabel(text: String, selected: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick)) {
        Text(
            text,
            color = if (selected) OctopusColors.TextPrimary else OctopusColors.TextMuted,
            fontSize = if (selected) OctopusType.headline else OctopusType.titleSm,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
        Spacer(Modifier.height(OctopusSpacing.xs))
        Box(
            modifier = Modifier
                .height(3.dp)
                .width(if (selected) 22.dp else 0.dp)
                .background(OctopusColors.Primary, RoundedCornerShape(2.dp)),
        )
    }
}

// ── 探索 Tab：INS 风格社交圈子 ──────────────────────────

@Composable
private fun ExploreTab(
    onOpenActivity: (Class<*>) -> Unit,
    onNavigateToAgentSquare: () -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = OctopusSpacing.lg, end = OctopusSpacing.lg, top = OctopusSpacing.xs, bottom = 112.dp),
        horizontalArrangement = Arrangement.spacedBy(OctopusSpacing.md),
        verticalArrangement = Arrangement.spacedBy(OctopusSpacing.md),
    ) {
        // 热门圈子
        item(span = { GridItemSpan(maxLineSpan) }) {
            SectionHeaderWithAction(
                stringResource(R.string.feat_explore_hot_circles),
                Icons.Filled.Whatshot,
                com.apk.claw.android.ui.compose.theme.OctopusTints.Hot,
            )
        }
        itemsIndexed(sampleCircles) { index, circle ->
            CircleCard(circle, index, onOpenActivity)
        }

        // 推荐技能
        item(span = { GridItemSpan(maxLineSpan) }) {
            SectionHeaderWithAction(
                stringResource(R.string.feat_explore_recommended),
                Icons.Filled.Bolt,
                SkillTint,
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            ExploreEntryCard(
                Icons.Filled.Bolt, SkillTint,
                stringResource(R.string.feat_explore_all_skills),
                stringResource(R.string.feat_explore_all_skills_desc),
            ) { onOpenActivity(SkillsActivity::class.java) }
        }

        // Agent 广场
        item(span = { GridItemSpan(maxLineSpan) }) {
            SectionHeaderWithAction(
                "Agent 广场",
                Icons.Filled.SmartToy,
                BrowserTint,
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            ExploreEntryCard(
                Icons.Filled.SmartToy,
                BrowserTint,
                "发现 Agent 玩法",
                "像刷小红书一样浏览 Agent 技能、教程与作品",
                onClick = onNavigateToAgentSquare,
            )
        }

        // 我的圈子（占位）
        item(span = { GridItemSpan(maxLineSpan) }) {
            SectionHeaderWithAction(
                stringResource(R.string.feat_explore_my_circles),
                Icons.Filled.PeopleOutline,
                OctopusColors.Primary,
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            // 空状态引导
            Surface(
                shape = OctopusShape.large,
                color = OctopusColors.Surface,
                border = BorderStroke(1.dp, OctopusColors.Border.copy(alpha = 0.6f)),
                modifier = Modifier.fillMaxWidth().clip(OctopusShape.large).clickable { onOpenActivity(SkillsActivity::class.java) },
            ) {
                Column(
                    modifier = Modifier.padding(vertical = OctopusSpacing.xxl),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Filled.PeopleOutline, contentDescription = null, tint = OctopusColors.TextMuted.copy(alpha = 0.5f), modifier = Modifier.size(32.dp))
                    Spacer(Modifier.height(OctopusSpacing.sm))
                    Text(stringResource(R.string.feat_explore_join_circle), color = OctopusColors.TextSecondary, fontSize = OctopusType.body, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(OctopusSpacing.xs))
                    Text(stringResource(R.string.feat_explore_join_circle_hint), color = OctopusColors.TextMuted, fontSize = OctopusType.caption)
                }
            }
        }
    }
}

/** 圈子卡片：头像 + 名称 + 成员数 + 标签，带进入动画 */
@Composable
private fun CircleCard(
    circle: CirclePreview,
    index: Int,
    onOpenActivity: (Class<*>) -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    val alpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(300, delayMillis = index * 80),
        label = "circle_alpha",
    )
    val offsetY by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (visible) 0f else 30f,
        animationSpec = tween(300, delayMillis = index * 80),
        label = "circle_offset",
    )

    Surface(
        color = OctopusColors.Surface,
        shape = OctopusShape.large,
        border = BorderStroke(1.dp, OctopusColors.Border.copy(alpha = 0.6f)),
        modifier = Modifier
            .fillMaxWidth()
            .clip(OctopusShape.large)
            .clickable { onOpenActivity(SkillsActivity::class.java) }
            .alpha(alpha)
            .graphicsLayer { translationY = offsetY },
    ) {
        Column(
            modifier = Modifier.padding(OctopusSpacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 头像
            val avatarDesc = stringResource(R.string.feat_explore_circle_avatar, stringResource(circle.nameRes))
            Box(
                modifier = Modifier.size(48.dp).background(circle.tint.copy(alpha = 0.16f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    circle.icon,
                    contentDescription = avatarDesc,
                    tint = circle.tint,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.height(OctopusSpacing.sm))
            // 名称
            Text(
                stringResource(circle.nameRes),
                color = OctopusColors.TextPrimary,
                fontSize = OctopusType.bodyStrong,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(OctopusSpacing.xs))
            // 成员数
            Text(
                "${circle.members} ${stringResource(R.string.feat_explore_members)}",
                color = OctopusColors.TextMuted,
                fontSize = OctopusType.caption,
            )
            Spacer(Modifier.height(OctopusSpacing.sm))
            // 标签
            val tagText = stringResource(circle.tagRes)
            Surface(
                shape = OctopusShape.small,
                color = when (circle.tagRes) {
                    R.string.circle_tag_hot -> com.apk.claw.android.ui.compose.theme.OctopusTints.Hot.copy(alpha = 0.12f)
                    else -> OctopusColors.Primary.copy(alpha = 0.10f)
                },
            ) {
                Text(
                    tagText,
                    modifier = Modifier.padding(horizontal = OctopusSpacing.sm, vertical = OctopusSpacing.xs),
                    color = when (circle.tagRes) {
                        R.string.circle_tag_hot -> com.apk.claw.android.ui.compose.theme.OctopusTints.Hot
                        else -> OctopusColors.Primary
                    },
                    fontSize = OctopusType.tag,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/** 分区标题 + 图标 */
@Composable
private fun SectionHeaderWithAction(text: String, icon: ImageVector, tint: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = OctopusSpacing.xs, end = OctopusSpacing.xs, top = OctopusSpacing.md, bottom = OctopusSpacing.xs),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(OctopusIconSize.small))
        Spacer(Modifier.width(OctopusSpacing.sm))
        Text(text, color = OctopusColors.TextSecondary, fontSize = OctopusType.body, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
    }
}

/** 整行入口卡片（图标 + 标题 + 描述 + 右箭头） */
@Composable
private fun ExploreEntryCard(icon: ImageVector, tint: Color, title: String, desc: String, onClick: () -> Unit) {
    Surface(
        color = OctopusColors.Surface,
        shape = OctopusShape.large,
        border = BorderStroke(1.dp, OctopusColors.Border.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth().clip(OctopusShape.large).clickable(onClick = onClick),
    ) {
        Row(modifier = Modifier.padding(OctopusSpacing.md), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(40.dp).background(tint.copy(alpha = 0.16f), OctopusShape.medium),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(OctopusSpacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = OctopusColors.TextPrimary, fontSize = OctopusType.bodyStrong, fontWeight = FontWeight.Medium, maxLines = 1)
                Spacer(Modifier.height(OctopusSpacing.xs))
                Text(desc, color = OctopusColors.TextMuted, fontSize = OctopusType.caption, lineHeight = 14.sp, maxLines = 2)
            }
        }
    }
}

// ── 工具箱 Tab ──────────────────────────────────────────

@Composable
private fun ToolboxTab(sections: List<Pair<Int, List<FeatureItem>>>, onClick: (FeatureItem) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = OctopusSpacing.lg, end = OctopusSpacing.lg, top = OctopusSpacing.xs, bottom = 112.dp),
        horizontalArrangement = Arrangement.spacedBy(OctopusSpacing.md),
        verticalArrangement = Arrangement.spacedBy(OctopusSpacing.md),
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
        shape = OctopusShape.large,
        border = BorderStroke(1.dp, OctopusColors.Border.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth().clip(OctopusShape.large).clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(OctopusSpacing.md)) {
            Box(
                modifier = Modifier.size(40.dp).background(item.tint.copy(alpha = 0.16f), OctopusShape.medium),
                contentAlignment = Alignment.Center,
            ) {
                Icon(item.icon, contentDescription = null, tint = item.tint, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.height(OctopusSpacing.md))
            Text(
                stringResource(item.labelRes),
                color = OctopusColors.TextPrimary,
                fontSize = OctopusType.bodyStrong,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            Spacer(Modifier.height(OctopusSpacing.xs))
            Text(
                stringResource(item.descRes),
                color = OctopusColors.TextMuted,
                fontSize = OctopusType.caption,
                lineHeight = 14.sp,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        color = OctopusColors.TextMuted,
        fontSize = OctopusType.body,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = OctopusSpacing.xs, end = OctopusSpacing.xs, top = OctopusSpacing.md, bottom = OctopusSpacing.xs),
    )
}

private fun Context.open(target: Class<*>) {
    runCatching { startActivity(Intent(this, target)) }
}
