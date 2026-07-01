package com.apk.claw.android.ui.compose.screen

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.apk.claw.android.R
import com.apk.claw.android.octopus_mobile.browser.SearchEngines
import com.apk.claw.android.plugin.MiniAppRegistry
import com.apk.claw.android.plugin.PluginManifest
import com.apk.claw.android.ui.browser.BookmarkItem
import com.apk.claw.android.ui.browser.BookmarkManager
import com.apk.claw.android.ui.browser.BrowserActivity
import com.apk.claw.android.ui.compose.theme.OctopusBackground
import com.apk.claw.android.ui.compose.theme.OctopusColors
import com.apk.claw.android.ui.compose.theme.OctopusIconSize
import com.apk.claw.android.ui.compose.theme.OctopusLayout
import com.apk.claw.android.ui.compose.theme.OctopusShape
import com.apk.claw.android.ui.compose.theme.OctopusSpacing
import com.apk.claw.android.ui.compose.theme.OctopusTints
import com.apk.claw.android.ui.compose.theme.OctopusThemeStyle
import com.apk.claw.android.ui.compose.theme.OctopusType
import com.apk.claw.android.utils.KVUtils
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private val PrimaryColor get() = OctopusColors.Primary
private val SurfaceColor get() = OctopusColors.Surface
private val TextPrimary get() = OctopusColors.TextPrimary
private val TextSecondary get() = OctopusColors.TextSecondary
private val TextMuted get() = OctopusColors.TextMuted
private val BorderColor get() = OctopusColors.Border

/** 浏览器首页在 Glass（暖色渐变背景）和 Standard（纯色背景）下的标题文字色 */
@Composable
private fun BrowserHomeTextColor(): Color =
    if (OctopusThemeStyle.isGlass) Color.White else TextPrimary

/** 浏览器首页在 Glass 和 Standard 下的次级文字色 */
@Composable
private fun BrowserHomeMutedTextColor(): Color =
    if (OctopusThemeStyle.isGlass) Color.White.copy(alpha = 0.78f) else TextSecondary

private enum class SearchMode { Web, Ai, All }

private data class BrowserSearchOption(
    val id: String,
    val label: String,
    val favicon: String?,
    val mode: SearchMode,
)

private data class BrowserShortcut(
    val label: String,
    val categoryRes: Int,
    val url: String,
    val iconUrl: String?,
    val fallbackIcon: ImageVector,
    val tint: Color,
)

private fun openBrowser(context: Context, query: String?) {
    val intent = Intent(context, BrowserActivity::class.java)
    if (!query.isNullOrBlank()) intent.putExtra(BrowserActivity.EXTRA_URL, query)
    runCatching { context.startActivity(intent) }
}

@Composable
fun DiscoverScreen(onOpenUrl: ((String?) -> Unit)? = null) {
    val context = LocalContext.current
    // 默认：启动独立浏览器 Activity（底部导航 Tab 用）。
    // 被浏览器内嵌为"新标签首页"时，宿主传入 onOpenUrl → 在当前 WebView 内导航，避免嵌套再起一个浏览器。
    val openUrl: (String?) -> Unit = onOpenUrl ?: { url -> openBrowser(context, url) }
    var query by remember { mutableStateOf("") }
    var engineId by remember { mutableStateOf(KVUtils.getSearchEngine()) }
    var engineMenuOpen by remember { mutableStateOf(false) }
    var searchMode by remember { mutableStateOf(SearchMode.Web) }
    val aiSearchLabel = stringResource(R.string.ai_browser_mode_ai)
    val allSearchLabel = stringResource(R.string.ai_browser_mode_all)
    val searchOptions = remember(aiSearchLabel, allSearchLabel) {
        SearchEngines.ALL.map { BrowserSearchOption(it.id, it.label, it.favicon, SearchMode.Web) } + listOf(
            BrowserSearchOption("ai", aiSearchLabel, null, SearchMode.Ai),
            BrowserSearchOption("all", allSearchLabel, null, SearchMode.All),
        )
    }
    val selectedSearchOption = remember(engineId, searchMode, searchOptions) {
        searchOptions.firstOrNull {
            if (searchMode == SearchMode.Web) it.id == engineId else it.mode == searchMode
        } ?: searchOptions.first()
    }
    val submit = {
        val text = query.trim()
        openUrl(text.ifBlank { null })
        query = ""
    }
    // 小程序/书签都可能在别的页面(能力→小程序 / 浏览器设置)被改动，回到这个 tab 时要重读一遍，
    // 跟 SettingsScreen 的 refreshTick 是同一个套路。
    val lifecycleOwner = LocalLifecycleOwner.current
    var refreshTick by remember { mutableStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) refreshTick++ }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val miniApps = remember(refreshTick) { MiniAppRegistry.all() }
    val bookmarks = remember(refreshTick) { BookmarkManager().getAll() }
    val tongyiQianwenLabel = stringResource(R.string.browser_app_tongyi_qianwen)
    val shortcuts = remember(tongyiQianwenLabel) {
        listOf(
            BrowserShortcut("DeepSeek", R.string.browser_cat_ai, "https://chat.deepseek.com", "https://www.deepseek.com/favicon.ico", Icons.Filled.AutoAwesome, OctopusTints.CatAI),
            BrowserShortcut(tongyiQianwenLabel, R.string.browser_cat_ai, "https://tongyi.aliyun.com/qianwen", "https://img.alicdn.com/imgextra/i3/O1CN01X1H2wE1eJxXvX9v7P_!!6000000003854-2-tps-180-180.png", Icons.Filled.AutoAwesome, OctopusTints.CatAI),
            BrowserShortcut("YouTube", R.string.browser_cat_video, "https://www.youtube.com", "https://www.youtube.com/s/desktop/6d0b8f16/img/favicon_32x32.png", Icons.Filled.Movie, OctopusTints.CatVideo),
            BrowserShortcut("GitHub", R.string.browser_cat_dev, "https://github.com", "https://github.githubassets.com/favicons/favicon.png", Icons.Filled.Code, OctopusTints.CatDev),
            BrowserShortcut("Bilibili", R.string.browser_cat_video, "https://www.bilibili.com", "https://www.bilibili.com/favicon.ico", Icons.Filled.Movie, OctopusTints.CatVideo),
            BrowserShortcut("Perplexity", R.string.browser_cat_ai, "https://www.perplexity.ai", "https://www.perplexity.ai/favicon.ico", Icons.Filled.School, OctopusTints.CatKnowledge),
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(OctopusBackground.pageBrush())
            .statusBarsPadding(),
        contentPadding = PaddingValues(
            start = OctopusSpacing.lg,
            end = OctopusSpacing.lg,
            top = OctopusSpacing.sm,
            bottom = OctopusLayout.bottomNavContentPadding,
        ),
        verticalArrangement = Arrangement.spacedBy(OctopusSpacing.md),
    ) {
        item {
            BrowserHomeTopBar()
        }

        item {
            BrowserOmnibox(
                value = query,
                onValueChange = { query = it },
                selectedOption = selectedSearchOption,
                options = searchOptions,
                engineMenuOpen = engineMenuOpen,
                onEngineMenuChange = { engineMenuOpen = it },
                onEngineSelected = { option ->
                    searchMode = option.mode
                    if (option.mode == SearchMode.Web) {
                        engineId = option.id
                        KVUtils.setSearchEngine(option.id)
                    }
                },
                onSubmit = submit,
            )
        }

        item {
            MiniAppsSection(apps = miniApps) { id -> MiniAppRegistry.launch(context, id) }
        }

        item {
            BookmarksSection(bookmarks = bookmarks) { openUrl(it) }
        }

        item {
            CommonSitesSection(shortcuts = shortcuts) { openUrl(it) }
        }
    }
}

@Composable
private fun BrowserHomeTopBar() {
    val calendar = remember { Calendar.getInstance() }
    val monthText = remember {
        SimpleDateFormat("MMM yyyy", Locale.getDefault()).format(calendar.time)
    }
    val dateCardBg = if (OctopusThemeStyle.isGlass) {
        OctopusBackground.cardSurface
    } else {
        PrimaryColor
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassPanel(
            modifier = Modifier.size(width = 76.dp, height = 60.dp),
            shape = RoundedCornerShape(20.dp),
            backgroundColor = dateCardBg,
            contentPadding = OctopusSpacing.xs,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    calendar.get(Calendar.DAY_OF_MONTH).toString(),
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.sp,
                )
                Text(monthText, color = Color.White.copy(alpha = 0.78f), fontSize = OctopusType.tag, maxLines = 1)
            }
        }
        Spacer(Modifier.width(OctopusSpacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.browser_home_title),
                color = BrowserHomeTextColor(),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.sp,
            )
            Text(
                SimpleDateFormat("EEEE", Locale.getDefault()).format(calendar.time),
                color = BrowserHomeMutedTextColor(),
                fontSize = OctopusType.body,
                maxLines = 1,
            )
        }
    }
}

/** 三个聚合小节共用的卡片外壳：标题 + 内容；列表为空时整块不占地方。 */
@Composable
private fun HomeSectionCard(title: String, isEmpty: Boolean, content: @Composable ColumnScope.() -> Unit) {
    if (isEmpty) return
    GlassPanel(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        contentPadding = OctopusSpacing.sm,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(OctopusSpacing.sm)) {
            Text(title, color = TextPrimary, fontSize = OctopusType.bodyStrong, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

/** 三个聚合小节共用的两列平铺：最后一行落单时补一个等宽 Spacer 保持对齐。 */
@Composable
private fun <T> TwoColumnTiles(items: List<T>, tile: @Composable (T, Modifier) -> Unit) {
    items.chunked(2).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(OctopusSpacing.sm), modifier = Modifier.fillMaxWidth()) {
            row.forEach { tile(it, Modifier.weight(1f)) }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

/** 我的小程序：[MiniAppRegistry] 里已注册的小程序（含 generate_app 现场生成的），点了直接启动。 */
@Composable
private fun MiniAppsSection(apps: List<PluginManifest>, onLaunch: (String) -> Unit) {
    val miniAppTag = stringResource(R.string.browser_home_miniapp_tag)
    HomeSectionCard(title = stringResource(R.string.browser_home_miniapps), isEmpty = apps.isEmpty()) {
        TwoColumnTiles(apps) { m, mod ->
            HomeTile(label = m.name.ifBlank { m.id }, subtitle = miniAppTag, modifier = mod, onClick = { onLaunch(m.id) }) {
                Icon(Icons.Filled.Apps, contentDescription = null, tint = OctopusTints.CatDev, modifier = Modifier.size(OctopusIconSize.medium))
            }
        }
    }
}

/** 书签：[BookmarkManager] 里存的收藏，图标尝试用站点自己的 favicon.ico，加载失败落回书签图标。 */
@Composable
private fun BookmarksSection(bookmarks: List<BookmarkItem>, onOpen: (String) -> Unit) {
    HomeSectionCard(title = stringResource(R.string.browser_home_bookmarks), isEmpty = bookmarks.isEmpty()) {
        TwoColumnTiles(bookmarks.take(6)) { b, mod ->
            val host = remember(b.url) { runCatching { android.net.Uri.parse(b.url).host }.getOrNull().orEmpty() }
            HomeTile(label = b.title.ifBlank { host }, subtitle = host, modifier = mod, onClick = { onOpen(b.url) }) {
                FaviconIcon(
                    url = host.takeIf { it.isNotBlank() }?.let { "https://$it/favicon.ico" },
                    tint = OctopusTints.CatKnowledge,
                    fallback = Icons.Filled.Bookmark,
                    contentDescription = b.title,
                )
            }
        }
    }
}

/** 常用网站：内置的几个快捷方式，新用户没有小程序/书签时页面不至于空荡荡。 */
@Composable
private fun CommonSitesSection(shortcuts: List<BrowserShortcut>, onOpen: (String) -> Unit) {
    HomeSectionCard(title = stringResource(R.string.browser_home_common_sites), isEmpty = shortcuts.isEmpty()) {
        TwoColumnTiles(shortcuts) { shortcut, mod ->
            HomeTile(label = shortcut.label, subtitle = stringResource(shortcut.categoryRes), modifier = mod, onClick = { onOpen(shortcut.url) }) {
                FaviconIcon(url = shortcut.iconUrl, tint = shortcut.tint, fallback = shortcut.fallbackIcon, contentDescription = shortcut.label)
            }
        }
    }
}

/** favicon 加载中/失败都落回同一个矢量图标兜底，三个小节共用，别各写一份。 */
@Composable
private fun FaviconIcon(url: String?, tint: Color, fallback: ImageVector, contentDescription: String?) {
    if (url == null) {
        Icon(fallback, contentDescription = null, tint = tint, modifier = Modifier.size(OctopusIconSize.medium))
        return
    }
    coil.compose.SubcomposeAsyncImage(
        model = url,
        contentDescription = contentDescription,
        modifier = Modifier.size(20.dp).clip(RoundedCornerShape(6.dp)),
        contentScale = ContentScale.Fit,
        loading = { Icon(fallback, contentDescription = null, tint = tint, modifier = Modifier.size(OctopusIconSize.medium)) },
        error = { Icon(fallback, contentDescription = null, tint = tint, modifier = Modifier.size(OctopusIconSize.medium)) },
    )
}

@Composable
private fun BrowserOmnibox(
    value: String,
    onValueChange: (String) -> Unit,
    selectedOption: BrowserSearchOption,
    options: List<BrowserSearchOption>,
    engineMenuOpen: Boolean,
    onEngineMenuChange: (Boolean) -> Unit,
    onEngineSelected: (BrowserSearchOption) -> Unit,
    onSubmit: () -> Unit,
) {
    val isGlass = OctopusThemeStyle.isGlass
    val omniBg = if (isGlass) Color.White.copy(alpha = 0.82f) else SurfaceColor
    val omniBorder = if (isGlass) Color.White.copy(alpha = 0.58f) else OctopusBackground.glassBorder
    Surface(
        modifier = Modifier.fillMaxWidth().height(54.dp),
        shape = RoundedCornerShape(22.dp),
        color = omniBg,
        border = BorderStroke(0.5.dp, omniBorder),
        shadowElevation = if (isGlass) 8.dp else 0.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(start = OctopusSpacing.md, end = OctopusSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                Row(
                    modifier = Modifier
                        .height(42.dp)
                        .clickable { onEngineMenuChange(true) }
                        .padding(horizontal = OctopusSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SearchOptionIcon(selectedOption, Modifier.size(OctopusIconSize.medium))
                    Spacer(Modifier.width(OctopusSpacing.sm))
                    Text(selectedOption.label, color = TextSecondary, fontSize = OctopusType.body, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = TextMuted, modifier = Modifier.size(OctopusIconSize.small))
                }
                DropdownMenu(
                    expanded = engineMenuOpen,
                    onDismissRequest = { onEngineMenuChange(false) },
                    containerColor = SurfaceColor,
                ) {
                    options.forEach { option ->
                        DropdownMenuItem(
                            leadingIcon = { SearchOptionIcon(option, Modifier.size(OctopusIconSize.medium)) },
                            text = { Text(option.label, color = TextPrimary, fontSize = OctopusType.bodyStrong) },
                            trailingIcon = {
                                if (option.id == selectedOption.id) {
                                    Icon(Icons.Filled.Check, contentDescription = null, tint = PrimaryColor, modifier = Modifier.size(OctopusIconSize.medium))
                                }
                            },
                            onClick = {
                                onEngineSelected(option)
                                onEngineMenuChange(false)
                            },
                        )
                    }
                }
            }

            Box(modifier = Modifier.padding(horizontal = OctopusSpacing.md).width(1.dp).height(24.dp).background(BorderColor))

            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = TextStyle(color = TextPrimary, fontSize = OctopusType.title),
                    cursorBrush = SolidColor(PrimaryColor),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { onSubmit() }),
                )
                if (value.isEmpty()) {
                    Text(stringResource(R.string.ai_browser_omnibox_hint), color = TextMuted, fontSize = 15.sp, maxLines = 1)
                }
            }

            IconButton(modifier = Modifier.size(44.dp), onClick = onSubmit) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = stringResource(R.string.browser_url_hint), tint = PrimaryColor)
            }
        }
    }
}

@Composable
private fun SearchOptionIcon(option: BrowserSearchOption, modifier: Modifier = Modifier) {
    when {
        option.favicon != null -> coil.compose.AsyncImage(
            model = option.favicon,
            contentDescription = option.label,
            modifier = modifier.clip(RoundedCornerShape(4.dp)),
            contentScale = ContentScale.Fit,
        )
        option.mode == SearchMode.Ai -> Icon(Icons.Filled.Psychology, contentDescription = null, tint = PrimaryColor, modifier = modifier)
        else -> Icon(Icons.Filled.Public, contentDescription = null, tint = PrimaryColor, modifier = modifier)
    }
}

/** 三个聚合小节共用的单个格子：图标 + 主标题 + 副标题。 */
@Composable
private fun HomeTile(
    label: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    val isGlass = OctopusThemeStyle.isGlass
    val tileBg = if (isGlass) Color.White.copy(alpha = 0.22f) else OctopusColors.FillSecondary
    val iconBg = if (isGlass) Color.White.copy(alpha = 0.72f) else SurfaceColor
    Row(
        modifier = modifier
            .height(52.dp)
            .clip(OctopusShape.large)
            .background(tileBg)
            .clickable(onClick = onClick)
            .padding(horizontal = OctopusSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(32.dp),
            shape = RoundedCornerShape(10.dp),
            color = iconBg,
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { icon() }
        }
        Spacer(Modifier.width(OctopusSpacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = TextPrimary, fontSize = OctopusType.caption, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(subtitle, color = TextSecondary, fontSize = OctopusType.tag, maxLines = 1)
        }
    }
}

@Composable
private fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape,
    backgroundColor: Color = OctopusBackground.cardSurface,
    contentPadding: androidx.compose.ui.unit.Dp = OctopusSpacing.md,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(backgroundColor, shape)
            .border(0.5.dp, OctopusBackground.glassBorder, shape)
    ) {
        Box(modifier = Modifier.padding(contentPadding)) {
            content()
        }
    }
}
