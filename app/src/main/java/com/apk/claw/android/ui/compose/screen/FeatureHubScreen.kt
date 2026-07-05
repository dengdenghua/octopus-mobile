package com.apk.claw.android.ui.compose.screen

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items as staggeredItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PeopleOutline
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Weekend
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apk.claw.android.R
import com.apk.claw.android.octopus_mobile.RoutineStore
import com.apk.claw.android.ui.compose.component.GlassBottomSheet
import com.apk.claw.android.ui.compose.component.GlassCard
import com.apk.claw.android.ui.compose.component.GlassPill
import com.apk.claw.android.ui.compose.component.GlassTextPill
import com.apk.claw.android.ui.compose.theme.OctopusBackground
import com.apk.claw.android.ui.compose.theme.OctopusColors
import com.apk.claw.android.ui.compose.theme.OctopusIconSize
import com.apk.claw.android.ui.compose.theme.OctopusLayout
import com.apk.claw.android.ui.compose.theme.OctopusShape
import com.apk.claw.android.ui.compose.theme.OctopusSpacing
import com.apk.claw.android.ui.compose.theme.OctopusType
import com.apk.claw.android.ui.featurescreens.BrowserSettingsActivity
import com.apk.claw.android.ui.featurescreens.CloudDriveActivity
import com.apk.claw.android.ui.featurescreens.EvolutionActivity
import com.apk.claw.android.ui.featurescreens.MemoryActivity
import com.apk.claw.android.ui.featurescreens.MultiWindowActivity
import com.apk.claw.android.ui.featurescreens.MiniAppListActivity
import com.apk.claw.android.ui.featurescreens.MiniAppMarketplaceActivity
import com.apk.claw.android.ui.featurescreens.RoutinesActivity
import com.apk.claw.android.ui.featurescreens.SkillsActivity
import com.apk.claw.android.ui.featurescreens.TrustCenterActivity
import com.apk.claw.android.ui.featurescreens.VideoLibraryActivity
import java.util.UUID

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

// AgentDiscoveryPost / 灵感发现流数据已迁至 SquareCatalog.kt（服务端 API + 缓存 + 种子回退）。

private fun featureSections(): List<Pair<Int, List<FeatureItem>>> = listOf(
    R.string.feat_section_automation to listOf(
        FeatureItem(R.string.feat_skills, R.string.feat_skills_desc, Icons.Filled.Bolt, SkillTint, SkillsActivity::class.java),
        FeatureItem(R.string.feat_miniapps, R.string.feat_miniapps_desc, Icons.Filled.Apps, PluginTint, MiniAppListActivity::class.java),
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
    onNavigateToUniverse: () -> Unit = {},
    onNavigateToSkillMarketplace: () -> Unit = {},
    onNavigateToPluginMarketplace: () -> Unit = {},
) {
    val ctx = LocalContext.current
    var tab by remember { mutableStateOf(0) }
    var selectedPost by remember { mutableStateOf<AgentDiscoveryPost?>(null) }

    val sections = remember { featureSections() }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().background(OctopusBackground.pageBrush()).statusBarsPadding()) {
            // 顶部双 Tab
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = OctopusSpacing.xl, end = OctopusSpacing.xl, top = OctopusSpacing.md, bottom = OctopusSpacing.xs),
                verticalAlignment = Alignment.Bottom,
            ) {
                TabLabel(stringResource(R.string.feat_explore_title), selected = tab == 0) { tab = 0 }
                Spacer(Modifier.width(OctopusSpacing.xl))
                TabLabel(stringResource(R.string.feat_toolbox_title), selected = tab == 1) { tab = 1 }
                Spacer(Modifier.width(OctopusSpacing.xl))
                // 「更多」= 旧的功能入口大杂烩(ToolboxTab),过渡用 —— 分流到浏览器/设置后删除。
                TabLabel(stringResource(R.string.feat_more_title), selected = tab == 2) { tab = 2 }
            }

            when (tab) {
                0 -> ExploreTab(
                    onOpenActivity = { ctx.open(it) },
                    onNavigateToAgentSquare = onNavigateToAgentSquare,
                    onNavigateToUniverse = onNavigateToUniverse,
                    onOpenPost = { selectedPost = it },
                )
                1 -> MarketTab(
                    onOpenSkillStore = onNavigateToSkillMarketplace,
                    onOpenPluginStore = onNavigateToPluginMarketplace,
                    onOpenAppStore = { ctx.open(MiniAppMarketplaceActivity::class.java) },
                )
                else -> ToolboxTab(sections, onNavigateToSkillMarketplace, onNavigateToPluginMarketplace) { ctx.open(it.target) }
            }
        }

        selectedPost?.let { post ->
            // Pre-resolve strings outside the non-composable onRun lambda
            val postTitle = post.title
            val postDesc = post.desc
            val reproducePrefix = stringResource(R.string.agent_reproduce_prefix)
            val reproduceTarget = stringResource(R.string.agent_reproduce_target)
            val reproduceSuffix = stringResource(R.string.agent_reproduce_suffix)
            val targetLabel = stringResource(R.string.control_target_local)

            AgentDiscoveryDetail(
                post = post,
                onDismiss = { selectedPost = null },
                onRun = {
                    val routineName = savePostAsRoutine(
                        title = postTitle,
                        desc = postDesc,
                        reproducePrefix = reproducePrefix,
                        reproduceTarget = reproduceTarget,
                        reproduceSuffix = reproduceSuffix,
                        targetLabel = targetLabel,
                    )
                    Toast.makeText(ctx, ctx.getString(R.string.agent_reproduce_toast, routineName), Toast.LENGTH_SHORT).show()
                    selectedPost = null
                    ctx.open(RoutinesActivity::class.java)
                },
            )
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

// ── 探索 Tab：灵感瀑布流 ──────────────────────────

@Composable
private fun ExploreTab(
    onOpenActivity: (Class<*>) -> Unit,
    onNavigateToAgentSquare: () -> Unit,
    onNavigateToUniverse: () -> Unit,
    onOpenPost: (AgentDiscoveryPost) -> Unit,
) {
    var selectedTopic by remember { mutableStateOf("recommend") }
    // 灵感流来自服务端 /square/discovery（后台可改 header/topics/posts），失败回退缓存/种子；null=加载中。
    val feed by produceState<DiscoveryFeed?>(initialValue = null) {
        value = DiscoveryRepository.feed()
    }
    val data = feed
    val all = data?.posts ?: emptyList()
    val filteredPosts = if (selectedTopic == "recommend") all
        else all.filter { it.topicKey == selectedTopic }
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = OctopusSpacing.lg,
            end = OctopusSpacing.lg,
            top = OctopusSpacing.sm,
            bottom = OctopusLayout.bottomNavContentPadding,
        ),
        horizontalArrangement = Arrangement.spacedBy(OctopusSpacing.md),
        verticalItemSpacing = OctopusSpacing.md,
    ) {
        item(span = StaggeredGridItemSpan.FullLine) {
            AgentDiscoveryHeader(
                header = data?.header,
                onSearch = onNavigateToAgentSquare,
                onUniverse = onNavigateToUniverse,
                onCreate = { onOpenActivity(MiniAppListActivity::class.java) },
            )
        }

        item(span = StaggeredGridItemSpan.FullLine) {
            AgentTopicChips(
                topics = data?.topics,
                selectedTopic = selectedTopic,
                onSelect = { selectedTopic = it },
            )
        }

        item(span = StaggeredGridItemSpan.FullLine) {
            val title = if (selectedTopic == "recommend") stringResource(R.string.agent_trending_now)
                else stringResource(R.string.agent_topic_inspiration, topicLabel(selectedTopic))
            SectionHeaderWithAction(title, Icons.Filled.Whatshot, com.apk.claw.android.ui.compose.theme.OctopusTints.Hot)
        }

        // feed==null 表示首次加载中,展示 loading 占位,避免空白闪烁。
        if (data == null) {
            item(span = StaggeredGridItemSpan.FullLine) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = OctopusSpacing.xl),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(OctopusIconSize.medium),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(OctopusSpacing.sm))
                    Text(
                        stringResource(R.string.browser_loading_text),
                        color = OctopusColors.TextSecondary,
                        fontSize = OctopusType.body,
                    )
                }
            }
        } else {
            staggeredItems(filteredPosts, key = { it.id }) { post ->
                AgentDiscoveryCard(post, onClick = { onOpenPost(post) })
            }
        }
    }
}

/** topic key → 本地化标签文案（远端 label 留空时兜底）。 */
@Composable
private fun topicLabel(key: String): String = when (key.trim().lowercase()) {
    "recommend" -> stringResource(R.string.agent_square_tab_recommend)
    "automation" -> stringResource(R.string.agent_topic_automation)
    "efficiency" -> stringResource(R.string.agent_topic_efficiency)
    "life", "lifestyle" -> stringResource(R.string.agent_topic_life)
    "learning" -> stringResource(R.string.agent_topic_learning)
    "device" -> stringResource(R.string.agent_topic_device)
    else -> stringResource(R.string.agent_square_tab_recommend)
}

/** icon key → ImageVector（远端下发 key，App 端映射；未知 key 用 AutoAwesome 兜底）。 */
private fun iconKeyToVector(key: String): ImageVector = when (key.trim().lowercase()) {
    "search" -> Icons.Filled.Search
    "psychology" -> Icons.Filled.Psychology
    "add" -> Icons.Filled.Add
    "autoawesome" -> Icons.Filled.AutoAwesome
    "bolt" -> Icons.Filled.Bolt
    "trendingup" -> Icons.Filled.TrendingUp
    "weekend" -> Icons.Filled.Weekend
    "phoneandroid" -> Icons.Filled.PhoneAndroid
    "whatshot" -> Icons.Filled.Whatshot
    else -> Icons.Filled.AutoAwesome
}

@Composable
private fun AgentDiscoveryHeader(
    header: DiscoveryHeader?,
    onSearch: () -> Unit,
    onUniverse: () -> Unit,
    onCreate: () -> Unit,
) {
    val title = header?.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.agent_inspiration_plaza)
    val icon = iconKeyToVector(header?.icon ?: "AutoAwesome")
    val tint = header?.tint ?: BrowserTint
    val actions = (
        header?.actions?.takeIf { it.isNotEmpty() } ?: listOf(
            DiscoveryAction("Search", "", "search", BrowserTint),
            DiscoveryAction("Psychology", "", "universe", MemoryTint),
            DiscoveryAction("Add", "", "publish", SkillTint),
        )
        ).filter {
        // Universe(My Ghost)未完成先隐藏 —— 服务端下发的同名 action 一并过滤,见 FeatureFlags
        com.apk.claw.android.FeatureFlags.UNIVERSE_ENABLED ||
            !it.action.trim().equals("universe", ignoreCase = true)
    }
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(OctopusSpacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(tint.copy(alpha = 0.18f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(OctopusSpacing.sm))
                Text(
                    title,
                    color = OctopusColors.TextPrimary,
                    fontSize = OctopusType.body,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(OctopusSpacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(OctopusSpacing.sm), modifier = Modifier.fillMaxWidth()) {
                actions.forEach { action ->
                    val label = action.text.takeIf { it.isNotBlank() } ?: actionLabel(action.action)
                    AgentActionPill(
                        icon = iconKeyToVector(action.icon),
                        text = label,
                        tint = action.tint,
                        modifier = Modifier.weight(1f),
                        onClick = { handleAction(action.action, onSearch, onUniverse, onCreate) },
                    )
                }
            }
        }
    }
}

/** action key → 本地化文案兜底。 */
@Composable
private fun actionLabel(action: String): String = when (action.trim().lowercase()) {
    "search" -> stringResource(R.string.agent_action_search)
    "universe" -> stringResource(R.string.agent_action_universe)
    "publish" -> stringResource(R.string.agent_action_publish)
    else -> stringResource(R.string.agent_action_search)
}

private fun handleAction(
    action: String,
    onSearch: () -> Unit,
    onUniverse: () -> Unit,
    onCreate: () -> Unit,
) {
    when (action.trim().lowercase()) {
        "search" -> onSearch()
        // 双保险:入口已在渲染层过滤,这里再拦一道,开关关着就不导航
        "universe" -> if (com.apk.claw.android.FeatureFlags.UNIVERSE_ENABLED) onUniverse()
        "publish" -> onCreate()
    }
}

@Composable
private fun AgentActionPill(
    icon: ImageVector,
    text: String,
    tint: Color,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    GlassPill(icon = icon, text = text, tint = tint, modifier = modifier, onClick = onClick)
}

@Composable
private fun AgentTopicChips(
    topics: List<DiscoveryTopic>?,
    selectedTopic: String,
    onSelect: (String) -> Unit,
) {
    val list = topics?.takeIf { it.isNotEmpty() } ?: listOf(
        DiscoveryTopic("recommend", "", com.apk.claw.android.ui.compose.theme.OctopusTints.Hot),
        DiscoveryTopic("automation", "", RoutineTint),
        DiscoveryTopic("efficiency", "", SkillTint),
        DiscoveryTopic("life", "", CloudTint),
        DiscoveryTopic("learning", "", MemoryTint),
        DiscoveryTopic("device", "", WindowTint),
    )
    // 单行横向滑动:比 2 行网格更矮,省掉一整行高度(胶囊按内容自适应宽度,超出即横滑)。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(OctopusSpacing.sm),
    ) {
        list.forEach { topic ->
            val label = topic.label.takeIf { it.isNotBlank() } ?: topicLabel(topic.key)
            GlassTextPill(
                text = label,
                tint = topic.tint,
                selected = selectedTopic == topic.key,
                onClick = { onSelect(topic.key) },
            )
        }
    }
}

private fun savePostAsRoutine(title: String, desc: String, reproducePrefix: String, reproduceTarget: String, reproduceSuffix: String, targetLabel: String): String {
    val name = title.take(24)
    val prompt = buildString {
        append(reproducePrefix)
        append(title)
        append(reproduceTarget)
        append(desc)
        append(reproduceSuffix)
    }
    RoutineStore.add(
        RoutineStore.Routine(
            id = "inspiration-${UUID.randomUUID()}",
            name = name,
            prompt = prompt,
            targetId = "local",
            targetLabel = targetLabel,
            createdAt = System.currentTimeMillis(),
        )
    )
    return name
}

/** 按主题给灵感卡片不同的封面图标，避免所有卡片都用同一个机器人图标。基于 topic key 匹配。 */
private fun discoveryIconFor(topicKey: String): ImageVector = when (topicKey.trim().lowercase()) {
    "automation" -> Icons.Filled.Bolt
    "efficiency" -> Icons.Filled.TrendingUp
    "life", "lifestyle" -> Icons.Filled.Weekend
    "learning" -> Icons.Filled.Psychology
    "device" -> Icons.Filled.PhoneAndroid
    else -> Icons.Filled.AutoAwesome
}

@Composable
private fun AgentDiscoveryCard(post: AgentDiscoveryPost, onClick: () -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(post.coverHeight.dp)
                    .background(Brush.verticalGradient(post.cover)),
            ) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(OctopusSpacing.sm)
                        .background(Color.Black.copy(alpha = 0.34f), OctopusShape.capsule)
                        .padding(horizontal = OctopusSpacing.sm, vertical = OctopusSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.size(6.dp).background(post.tagColor, CircleShape))
                    Spacer(Modifier.width(OctopusSpacing.xs))
                    Text(post.tag, color = Color.White, fontSize = OctopusType.tag, fontWeight = FontWeight.SemiBold)
                }
                Icon(
                    discoveryIconFor(post.topicKey),
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.78f),
                    modifier = Modifier.align(Alignment.Center).size(34.dp),
                )
            }
            Column(modifier = Modifier.padding(OctopusSpacing.md)) {
                Text(
                    post.title,
                    color = OctopusColors.TextPrimary,
                    fontSize = OctopusType.body,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 18.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(OctopusSpacing.xs))
                Text(
                    post.desc,
                    color = OctopusColors.TextMuted,
                    fontSize = OctopusType.caption,
                    lineHeight = 15.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(OctopusSpacing.sm))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .background(post.tagColor.copy(alpha = 0.18f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(post.authorInitial, color = post.tagColor, fontSize = OctopusType.tag, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(OctopusSpacing.xs))
                    Text(
                        post.author,
                        modifier = Modifier.weight(1f),
                        color = OctopusColors.TextMuted,
                        fontSize = OctopusType.caption,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(
                        Icons.Filled.Favorite,
                        contentDescription = null,
                        tint = OctopusColors.TextMuted.copy(alpha = 0.58f),
                        modifier = Modifier.size(OctopusIconSize.small),
                    )
                    Spacer(Modifier.width(OctopusSpacing.xs))
                    Text(post.likes, color = OctopusColors.TextMuted, fontSize = OctopusType.tag, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun AgentDiscoveryDetail(
    post: AgentDiscoveryPost,
    onDismiss: () -> Unit,
    onRun: () -> Unit,
) {
    val maxSheetHeight = (LocalConfiguration.current.screenHeightDp * 0.64f).dp
    val scrollState = rememberScrollState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(OctopusColors.OverlayScrim)
            .padding(horizontal = OctopusSpacing.lg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(bottom = 96.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        GlassBottomSheet(
            modifier = Modifier
                .fillMaxWidth(),
            maxHeight = maxSheetHeight,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .clip(OctopusShape.large)
                        .background(Brush.verticalGradient(post.cover)),
                ) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(OctopusSpacing.md)
                            .background(Color.Black.copy(alpha = 0.34f), OctopusShape.capsule)
                            .padding(horizontal = OctopusSpacing.sm, vertical = OctopusSpacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(modifier = Modifier.size(6.dp).background(post.tagColor, CircleShape))
                        Spacer(Modifier.width(OctopusSpacing.xs))
                        Text(post.tag, color = Color.White, fontSize = OctopusType.tag, fontWeight = FontWeight.SemiBold)
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(OctopusSpacing.sm)
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.24f))
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = null, tint = Color.White, modifier = Modifier.size(OctopusIconSize.medium))
                    }
                    Icon(
                        Icons.Filled.SmartToy,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.78f),
                        modifier = Modifier.align(Alignment.Center).size(42.dp),
                    )
                }

                Spacer(Modifier.height(OctopusSpacing.md))
                Text(
                    post.title,
                    color = OctopusColors.TextPrimary,
                    fontSize = OctopusType.title,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 22.sp,
                )
                Spacer(Modifier.height(OctopusSpacing.xs))
                Text(
                    post.desc,
                    color = OctopusColors.TextSecondary,
                    fontSize = OctopusType.body,
                    lineHeight = 19.sp,
                )

                Spacer(Modifier.height(OctopusSpacing.md))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(post.tagColor.copy(alpha = 0.18f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(post.authorInitial, color = post.tagColor, fontSize = OctopusType.label, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(OctopusSpacing.sm))
                    Text(post.author, color = OctopusColors.TextSecondary, fontSize = OctopusType.body, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.Filled.Favorite, contentDescription = null, tint = OctopusColors.TextMuted.copy(alpha = 0.65f), modifier = Modifier.size(OctopusIconSize.small))
                    Spacer(Modifier.width(OctopusSpacing.xs))
                    Text(post.likes, color = OctopusColors.TextMuted, fontSize = OctopusType.caption)
                }

                Spacer(Modifier.height(OctopusSpacing.md))
                Row(horizontalArrangement = Arrangement.spacedBy(OctopusSpacing.sm), modifier = Modifier.fillMaxWidth()) {
                    DetailMetric(stringResource(R.string.agent_metric_usage), post.usage, post.tagColor, Modifier.weight(1f))
                    DetailMetric(stringResource(R.string.agent_metric_success_rate), post.successRate, RoutineTint, Modifier.weight(1f))
                    DetailMetric(stringResource(R.string.agent_metric_duration), post.duration, BrowserTint, Modifier.weight(1f))
                }

                Spacer(Modifier.height(OctopusSpacing.md))
                DetailSection(stringResource(R.string.agent_detail_how_to_use), listOf(stringResource(R.string.agent_detail_use_1), stringResource(R.string.agent_detail_use_2), stringResource(R.string.agent_detail_use_3)))

                Spacer(Modifier.height(OctopusSpacing.md))
                DetailSection(stringResource(R.string.agent_detail_prerequisites), listOf(stringResource(R.string.agent_detail_prereq_1), stringResource(R.string.agent_detail_prereq_2), stringResource(R.string.agent_detail_prereq_3)))

                Spacer(Modifier.height(OctopusSpacing.md))
                PermissionChips(stringResource(R.string.agent_detail_permissions), post.permissions, post.tagColor)

                Spacer(Modifier.height(OctopusSpacing.lg))
                Row(horizontalArrangement = Arrangement.spacedBy(OctopusSpacing.sm), modifier = Modifier.fillMaxWidth()) {
                    // 底部操作按钮用不透明 Surface，避免玻璃透明导致看不清
                    Surface(
                        shape = OctopusShape.capsule,
                        color = OctopusColors.Surface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, post.tagColor.copy(alpha = 0.4f)),
                        modifier = Modifier.weight(1f).clickable(onClick = onDismiss),
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = OctopusSpacing.md),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Favorite, contentDescription = null, tint = post.tagColor, modifier = Modifier.size(OctopusIconSize.small))
                            Spacer(Modifier.width(OctopusSpacing.xs))
                            Text(stringResource(R.string.agent_action_favorite), color = OctopusColors.TextPrimary, fontSize = OctopusType.label, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Surface(
                        shape = OctopusShape.capsule,
                        color = OctopusColors.Primary,
                        modifier = Modifier.weight(1f).clickable(onClick = onRun),
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = OctopusSpacing.md),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = OctopusColors.OnPrimary, modifier = Modifier.size(OctopusIconSize.small))
                            Spacer(Modifier.width(OctopusSpacing.xs))
                            Text(stringResource(R.string.agent_action_reproduce), color = OctopusColors.OnPrimary, fontSize = OctopusType.label, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailMetric(label: String, value: String, tint: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(OctopusShape.large)
            .background(tint.copy(alpha = if (OctopusColors.isLight) 0.11f else 0.16f))
            .padding(horizontal = OctopusSpacing.sm, vertical = OctopusSpacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, color = OctopusColors.TextMuted, fontSize = OctopusType.tag, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Text(value, color = tint, fontSize = OctopusType.caption, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun PermissionChips(title: String, permissions: List<String>, tint: Color) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(OctopusShape.large)
            .background(if (OctopusColors.isLight) Color.White.copy(alpha = 0.72f) else Color.White.copy(alpha = 0.08f))
            .padding(OctopusSpacing.md),
    ) {
        Text(title, color = OctopusColors.TextPrimary, fontSize = OctopusType.label, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(OctopusSpacing.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(OctopusSpacing.sm), modifier = Modifier.fillMaxWidth()) {
            permissions.take(3).forEach { permission ->
                GlassTextPill(
                    text = permission,
                    tint = tint,
                    onClick = {},
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun DetailSection(title: String, lines: List<String>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(OctopusShape.large)
            .background(if (OctopusColors.isLight) Color.White.copy(alpha = 0.72f) else Color.White.copy(alpha = 0.08f))
            .padding(OctopusSpacing.md),
    ) {
        Text(title, color = OctopusColors.TextPrimary, fontSize = OctopusType.label, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(OctopusSpacing.sm))
        lines.forEach { line ->
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 2.dp)) {
                Box(
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .size(5.dp)
                        .background(OctopusColors.Primary.copy(alpha = 0.55f), CircleShape),
                )
                Spacer(Modifier.width(OctopusSpacing.sm))
                Text(line, color = OctopusColors.TextSecondary, fontSize = OctopusType.caption, lineHeight = 16.sp)
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

    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(alpha)
            .graphicsLayer { translationY = offsetY },
        onClick = { onOpenActivity(SkillsActivity::class.java) },
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
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
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
private fun ToolboxTab(
    sections: List<Pair<Int, List<FeatureItem>>>,
    onMarket: () -> Unit,
    onPluginMarket: () -> Unit,
    onClick: (FeatureItem) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = OctopusSpacing.lg,
            end = OctopusSpacing.lg,
            top = OctopusSpacing.xs,
            bottom = OctopusLayout.bottomNavContentPadding,
        ),
        horizontalArrangement = Arrangement.spacedBy(OctopusSpacing.md),
        verticalArrangement = Arrangement.spacedBy(OctopusSpacing.md),
    ) {
        // 技能商城入口(整行)
        item(span = { GridItemSpan(maxLineSpan) }) {
            ExploreEntryCard(
                icon = Icons.Filled.Extension,
                tint = SkillTint,
                title = stringResource(R.string.skill_marketplace_title),
                desc = stringResource(R.string.skill_marketplace_entry_desc),
                onClick = onMarket,
            )
        }
        // 插件商城入口(整行)
        item(span = { GridItemSpan(maxLineSpan) }) {
            ExploreEntryCard(
                icon = Icons.Filled.Apps,
                tint = PluginTint,
                title = stringResource(R.string.plugin_marketplace_title),
                desc = stringResource(R.string.plugin_marketplace_entry_desc),
                onClick = onPluginMarket,
            )
        }
        sections.forEach { (headerRes, items) ->
            item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader(stringResource(headerRes)) }
            items(items, key = { it.labelRes }) { item -> ToolCard(item) { onClick(item) } }
        }
    }
}

@Composable
private fun ToolCard(item: FeatureItem, onClick: () -> Unit) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
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
