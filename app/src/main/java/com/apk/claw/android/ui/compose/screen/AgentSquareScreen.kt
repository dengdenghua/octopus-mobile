package com.apk.claw.android.ui.compose.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apk.claw.android.ui.compose.theme.OctopusColors
import com.apk.claw.android.ui.compose.theme.OctopusIconSize
import com.apk.claw.android.ui.compose.theme.OctopusShape
import com.apk.claw.android.ui.compose.theme.OctopusSpacing
import com.apk.claw.android.ui.compose.theme.OctopusTints
import com.apk.claw.android.ui.compose.theme.OctopusType

/**
 * Agent 广场 —— 小红书风格的双列瀑布流，展示 Agent 技能、用法、作品卡片。
 */
private data class AgentPost(
    val id: String,
    val title: String,
    val author: String,
    val authorInitial: String,
    val authorColor: Color,
    val likes: String,
    val tag: String,
    val tagColor: Color,
    val coverHeightDp: Int,
    val coverGradient: List<Color>,
)

private val samplePosts = listOf(
    AgentPost(
        id = "1",
        title = "让 AI 每天自动整理手机相册，生成回忆视频",
        author = "影像助手",
        authorInitial = "影",
        authorColor = OctopusTints.Video,
        likes = "1.2k",
        tag = "自动化",
        tagColor = OctopusTints.Routine,
        coverHeightDp = 180,
        coverGradient = listOf(Color(0xFF667EEA), Color(0xFF764BA2)),
    ),
    AgentPost(
        id = "2",
        title = "3 步搭一个会订外卖的 Agent",
        author = "效率玩家",
        authorInitial = "效",
        authorColor = OctopusTints.Skill,
        likes = "856",
        tag = "教程",
        tagColor = OctopusTints.Browser,
        coverHeightDp = 140,
        coverGradient = listOf(Color(0xFF11998E), Color(0xFF38EF7D)),
    ),
    AgentPost(
        id = "3",
        title = "我的 Agent 帮我写了一周周报，老板直呼专业",
        author = "打工侠",
        authorInitial = "打",
        authorColor = OctopusTints.Window,
        likes = "2.3k",
        tag = "职场",
        tagColor = OctopusTints.Memory,
        coverHeightDp = 200,
        coverGradient = listOf(Color(0xFFFC466B), Color(0xFF3F5EFB)),
    ),
    AgentPost(
        id = "4",
        title = "用语音唤醒 Agent，开车时也能回消息",
        author = "车载达人",
        authorInitial = "车",
        authorColor = OctopusTints.Plugin,
        likes = "634",
        tag = "语音",
        tagColor = OctopusTints.Trust,
        coverHeightDp = 160,
        coverGradient = listOf(Color(0xFFF2994A), Color(0xFFF2C94C)),
    ),
    AgentPost(
        id = "5",
        title = "Agent 自动比价，618 我省了 2000+",
        author = "省钱 Bot",
        authorInitial = "省",
        authorColor = OctopusTints.Cloud,
        likes = "3.1k",
        tag = "购物",
        tagColor = OctopusTints.Hot,
        coverHeightDp = 170,
        coverGradient = listOf(Color(0xFF00C6FF), Color(0xFF0072FF)),
    ),
    AgentPost(
        id = "6",
        title = "把 Agent 接入智能家居，一句话控制全屋",
        author = "极客居",
        authorInitial = "极",
        authorColor = OctopusTints.Evolve,
        likes = "1.5k",
        tag = "IoT",
        tagColor = OctopusTints.Plugin,
        coverHeightDp = 150,
        coverGradient = listOf(Color(0xFF8E2DE2), Color(0xFF4A00E0)),
    ),
    AgentPost(
        id = "7",
        title = "Agent 生成的旅行攻略，比小红书还细",
        author = "旅行 AI",
        authorInitial = "旅",
        authorColor = OctopusTints.Browser,
        likes = "987",
        tag = "生活",
        tagColor = OctopusTints.Routine,
        coverHeightDp = 190,
        coverGradient = listOf(Color(0xFFee9ca7), Color(0xFFFFDDE1)),
    ),
    AgentPost(
        id = "8",
        title = "让 Agent 帮你读论文，10 分钟抓重点",
        author = "学术喵",
        authorInitial = "学",
        authorColor = OctopusTints.Memory,
        likes = "742",
        tag = "学习",
        tagColor = OctopusTints.Skill,
        coverHeightDp = 145,
        coverGradient = listOf(Color(0xFF134E5E), Color(0xFF71B280)),
    ),
)

private val tabs = listOf("推荐", "关注", "热门")

@Composable
fun AgentSquareScreen(
    onBack: () -> Unit,
    onOpenSearch: () -> Unit = {},
    onCreatePost: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(OctopusColors.Background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        AgentSquareTopBar(onBack, onOpenSearch, onCreatePost)
        CategoryTabs()
        AgentFeed()
    }
}

@Composable
private fun AgentSquareTopBar(
    onBack: () -> Unit,
    onOpenSearch: () -> Unit,
    onCreatePost: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = OctopusSpacing.sm, vertical = OctopusSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.Filled.ArrowBack,
                contentDescription = "Back",
                tint = OctopusColors.TextPrimary,
                modifier = Modifier.size(OctopusIconSize.large),
            )
        }
        Text(
            "Agent 广场",
            modifier = Modifier.weight(1f),
            color = OctopusColors.TextPrimary,
            fontSize = OctopusType.titleLg,
            fontWeight = FontWeight.Bold,
        )
        IconButton(onClick = onOpenSearch) {
            Icon(
                Icons.Filled.Search,
                contentDescription = "Search",
                tint = OctopusColors.TextPrimary,
                modifier = Modifier.size(OctopusIconSize.large),
            )
        }
        IconButton(onClick = onCreatePost) {
            Icon(
                Icons.Filled.Add,
                contentDescription = "Create",
                tint = OctopusColors.Primary,
                modifier = Modifier.size(OctopusIconSize.large),
            )
        }
    }
}

@Composable
private fun CategoryTabs() {
    var selected by remember { mutableIntStateOf(0) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = OctopusSpacing.lg, vertical = OctopusSpacing.sm),
        horizontalArrangement = Arrangement.spacedBy(OctopusSpacing.md),
    ) {
        tabs.forEachIndexed { index, label ->
            val isSelected = index == selected
            Surface(
                shape = OctopusShape.capsule,
                color = if (isSelected) OctopusColors.Primary else OctopusColors.Surface,
                modifier = Modifier.clickable { selected = index },
            ) {
                Text(
                    label,
                    modifier = Modifier.padding(horizontal = OctopusSpacing.lg, vertical = OctopusSpacing.sm),
                    color = if (isSelected) OctopusColors.OnPrimary else OctopusColors.TextSecondary,
                    fontSize = OctopusType.body,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun AgentFeed() {
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(OctopusSpacing.lg),
        horizontalArrangement = Arrangement.spacedBy(OctopusSpacing.md),
        verticalItemSpacing = OctopusSpacing.md,
    ) {
        items(samplePosts, key = { it.id }) { post ->
            AgentPostCard(post)
        }
    }
}

@Composable
private fun AgentPostCard(post: AgentPost) {
    Surface(
        shape = OctopusShape.large,
        color = OctopusColors.Surface,
        shadowElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(OctopusShape.large)
            .clickable { },
    ) {
        Column {
            // 封面
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(post.coverHeightDp.dp)
                    .background(Brush.verticalGradient(post.coverGradient)),
            ) {
                // 深色 scrim 胶囊 + 彩色圆点 + 白字：在深/浅封面上都清晰可读，同时保留分类色彩标识
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(OctopusSpacing.sm)
                        .background(Color.Black.copy(alpha = 0.38f), OctopusShape.capsule)
                        .padding(horizontal = OctopusSpacing.sm, vertical = OctopusSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.size(6.dp).background(post.tagColor, CircleShape))
                    Spacer(Modifier.width(OctopusSpacing.xs))
                    Text(
                        post.tag,
                        color = Color.White,
                        fontSize = OctopusType.tag,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Column(modifier = Modifier.padding(OctopusSpacing.md)) {
                Text(
                    post.title,
                    color = OctopusColors.TextPrimary,
                    fontSize = OctopusType.body,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 18.sp,
                )
                Spacer(Modifier.height(OctopusSpacing.sm))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .background(post.authorColor.copy(alpha = 0.18f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            post.authorInitial,
                            color = post.authorColor,
                            fontSize = OctopusType.tag,
                            fontWeight = FontWeight.Bold,
                        )
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
                        tint = OctopusColors.TextMuted.copy(alpha = 0.6f),
                        modifier = Modifier.size(OctopusIconSize.small),
                    )
                    Spacer(Modifier.width(OctopusSpacing.xs))
                    Text(
                        post.likes,
                        color = OctopusColors.TextMuted,
                        fontSize = OctopusType.tag,
                    )
                }
            }
        }
    }
}
