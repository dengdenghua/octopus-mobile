package com.apk.claw.android.ui.compose.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apk.claw.android.R
import com.apk.claw.android.registry.CommunitySquareApi
import com.apk.claw.android.registry.RegistryClient
import com.apk.claw.android.registry.mobileFit
import com.apk.claw.android.ui.compose.component.GlassCard
import com.apk.claw.android.ui.compose.theme.OctopusColors
import com.apk.claw.android.ui.compose.theme.OctopusSpacing
import com.apk.claw.android.ui.compose.theme.OctopusTints
import com.apk.claw.android.ui.compose.theme.OctopusType

/**
 * 市场(广场 → 市场子 tab)—— 三类可安装内容的统一货架:技能 / 插件 / 应用(小程序)。
 *
 * 纯货架:只放可下载安装的东西;社区帖子/灵感在「灵感」子 tab,不在这里重复。
 * 每类:分类筛选 + 排序[排行 | 最新 | 趋势] + 卡片流。分类用 registry 的 category 字段
 * (现成);排行/趋势依赖服务端下载量埋点(见任务清单),数据到位前按最新序占位。
 * 数据复用现有商城的 API,点击卡片跳对应商城完成安装,不在此重写安装管线。
 */

private val PillShape = RoundedCornerShape(50)
private val CardShape = RoundedCornerShape(12.dp)
private const val ACTIVE_ALPHA = 0.16f
private val KindIconSize = 16.dp
private val KindIconGap = 6.dp
private val CardIconBox = 40.dp
private val CardIconSize = 20.dp
private val CategoryFontSize = 12.sp
private val TagFontSize = 11.sp

/** 货架三类。 */
internal enum class MarketKind(val titleRes: Int, val icon: ImageVector, val tint: Color) {
    SKILL(R.string.market_kind_skill, Icons.Filled.Bolt, OctopusTints.Skill),
    PLUGIN(R.string.market_kind_plugin, Icons.Filled.Extension, OctopusTints.Routine),
    APP(R.string.market_kind_app, Icons.Filled.Apps, OctopusTints.Browser),
}

/** 排序维度。RANKING/TRENDING 需服务端下载量数据,未到位前退化为 LATEST 序。 */
internal enum class MarketSort(val titleRes: Int) {
    RANKING(R.string.market_sort_ranking),
    LATEST(R.string.market_sort_latest),
    TRENDING(R.string.market_sort_trending),
}

/** 三类归一的货架条目。 */
internal data class MarketItem(
    val id: String,
    val name: String,
    val description: String,
    val category: String?,
    val version: String,
)

@Composable
fun MarketTab(
    onOpenSkillStore: () -> Unit,
    onOpenPluginStore: () -> Unit,
    onOpenAppStore: () -> Unit,
) {
    var kind by remember { mutableStateOf(MarketKind.SKILL) }
    var category by remember { mutableStateOf<String?>(null) }
    var sort by remember { mutableStateOf(MarketSort.LATEST) }
    var loading by remember { mutableStateOf(true) }
    var items by remember { mutableStateOf<List<MarketItem>>(emptyList()) }

    LaunchedEffect(kind) {
        loading = true
        category = null
        items = loadMarket(kind)
        loading = false
    }

    val categories = remember(items) {
        items.mapNotNull { it.category?.takeIf { c -> c.isNotBlank() } }.distinct().sorted()
    }
    // 排行/趋势的真实排序待服务端下载量数据;当前三档都按加载序(最新在前)。
    val shown = remember(items, category) {
        items.filter { category == null || it.category == category }
    }
    val openStore: () -> Unit = when (kind) {
        MarketKind.SKILL -> onOpenSkillStore
        MarketKind.PLUGIN -> onOpenPluginStore
        MarketKind.APP -> onOpenAppStore
    }

    Column(modifier = Modifier.fillMaxSize()) {
        MarketKindTabs(kind) { kind = it; sort = MarketSort.LATEST }
        MarketSortRow(sort) { sort = it }
        if (categories.isNotEmpty()) {
            MarketCategoryRow(categories, category) { category = it }
        }
        MarketContent(loading, shown, kind, openStore)
    }
}

/** 三类加载归一;应用类 Result 失败退空列表(UI 显示空态)。 */
private suspend fun loadMarket(kind: MarketKind): List<MarketItem> = when (kind) {
    MarketKind.SKILL -> RegistryClient.listSkills().filter { it.mobileFit }
        .map { MarketItem(it.id, it.name, it.description, it.category, it.version) }
    MarketKind.PLUGIN -> RegistryClient.listPlugins().filter { it.mobileFit }
        .map { MarketItem(it.id, it.name, it.description, it.category, it.version) }
    MarketKind.APP -> CommunitySquareApi.list().getOrDefault(emptyList())
        .map { MarketItem(it.effectiveSlug, it.name, it.description, it.category, it.version) }
}

@Composable
private fun MarketContent(
    loading: Boolean,
    shown: List<MarketItem>,
    kind: MarketKind,
    onOpenStore: () -> Unit,
) {
    when {
        loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
            CircularProgressIndicator(color = kind.tint)
        }
        shown.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
            Text(stringResource(R.string.market_empty), color = OctopusColors.TextMuted, fontSize = OctopusType.body)
        }
        else -> LazyColumn(
            contentPadding = PaddingValues(OctopusSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(OctopusSpacing.sm),
        ) {
            items(shown, key = { it.id }) { item -> MarketItemCard(item, kind, onOpenStore) }
        }
    }
}

@Composable
private fun MarketKindTabs(selected: MarketKind, onSelect: (MarketKind) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = OctopusSpacing.lg, vertical = OctopusSpacing.sm),
        horizontalArrangement = Arrangement.spacedBy(OctopusSpacing.sm),
    ) {
        MarketKind.entries.forEach { k ->
            val active = k == selected
            Row(
                modifier = Modifier
                    .clip(PillShape)
                    .background(if (active) k.tint.copy(alpha = ACTIVE_ALPHA) else Color.Transparent)
                    .border(1.dp, if (active) k.tint else OctopusColors.Border, PillShape)
                    .clickable { onSelect(k) }
                    .padding(horizontal = OctopusSpacing.md, vertical = OctopusSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    k.icon,
                    contentDescription = null,
                    tint = if (active) k.tint else OctopusColors.TextSecondary,
                    modifier = Modifier.size(KindIconSize),
                )
                Spacer(Modifier.size(KindIconGap))
                Text(
                    stringResource(k.titleRes),
                    color = if (active) k.tint else OctopusColors.TextSecondary,
                    fontSize = OctopusType.body,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun MarketSortRow(selected: MarketSort, onSelect: (MarketSort) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = OctopusSpacing.lg),
        horizontalArrangement = Arrangement.spacedBy(OctopusSpacing.md),
    ) {
        MarketSort.entries.forEach { s ->
            val active = s == selected
            Text(
                text = stringResource(s.titleRes),
                color = if (active) OctopusColors.Primary else OctopusColors.TextMuted,
                fontSize = OctopusType.caption,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.clip(PillShape).clickable { onSelect(s) }
                    .padding(horizontal = OctopusSpacing.sm, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun MarketCategoryRow(categories: List<String>, selected: String?, onSelect: (String?) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(horizontal = OctopusSpacing.lg, vertical = OctopusSpacing.xs),
        horizontalArrangement = Arrangement.spacedBy(OctopusSpacing.xs),
    ) {
        CategoryPill(stringResource(R.string.market_category_all), selected == null) { onSelect(null) }
        categories.forEach { c -> CategoryPill(c, selected == c) { onSelect(c) } }
    }
}

@Composable
private fun CategoryPill(text: String, active: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        color = if (active) OctopusColors.OnPrimary else OctopusColors.TextSecondary,
        fontSize = CategoryFontSize,
        modifier = Modifier.clip(PillShape)
            .background(if (active) OctopusColors.Primary else OctopusColors.SurfaceVariant)
            .clickable { onClick() }.padding(horizontal = OctopusSpacing.sm, vertical = 4.dp),
    )
}

@Composable
private fun MarketItemCard(item: MarketItem, kind: MarketKind, onClick: () -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(OctopusSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(CardIconBox).clip(CardShape).background(kind.tint.copy(alpha = ACTIVE_ALPHA)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(kind.icon, contentDescription = null, tint = kind.tint, modifier = Modifier.size(CardIconSize))
            }
            Spacer(Modifier.size(OctopusSpacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.name.ifBlank { item.id },
                    color = OctopusColors.TextPrimary,
                    fontSize = OctopusType.body,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.description.isNotBlank()) {
                    Text(
                        item.description,
                        color = OctopusColors.TextSecondary,
                        fontSize = OctopusType.caption,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!item.category.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(item.category, color = kind.tint, fontSize = TagFontSize)
                }
            }
        }
    }
}
