package com.apk.claw.android.ui.compose.screen

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Schedule
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import com.apk.claw.android.ui.browser.CommonSiteItem
import com.apk.claw.android.ui.browser.CommonSiteStore
import com.apk.claw.android.ui.featurescreens.BrowserSettingsActivity
import com.apk.claw.android.ui.featurescreens.CloudDriveActivity
import com.apk.claw.android.ui.featurescreens.MultiWindowActivity
import com.apk.claw.android.ui.featurescreens.RoutinesActivity
import com.apk.claw.android.ui.featurescreens.VideoLibraryActivity
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

private fun urlHost(url: String): String =
    runCatching { android.net.Uri.parse(url).host }.getOrNull().orEmpty()

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
    val bookmarks = remember(refreshTick) { BookmarkManager.getAll() }
    val commonSites = remember(refreshTick) { CommonSiteStore.getAll() }
    var siteToDelete by remember { mutableStateOf<CommonSiteItem?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(browserWallpaperBrush())
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

        // ── 手机桌面主页式 launcher:每个数据类别一个分区标题 + 图标网格,直接铺在壁纸上 ──
        // ── 我的应用(小程序):agent 可发现并操作的原生 app ──
        if (miniApps.isNotEmpty()) {
            item { LauncherSectionHeader(stringResource(R.string.browser_home_miniapps)) }
            item {
                MiniAppsSection(apps = miniApps) { id -> MiniAppRegistry.launch(context, id) }
            }
        }

        // ── 书签 ──
        if (bookmarks.isNotEmpty()) {
            item { LauncherSectionHeader(stringResource(R.string.browser_home_bookmarks)) }
            item { BookmarksSection(bookmarks = bookmarks) { openUrl(it) } }
        }

        // ── 常用网站(长按删除) ──
        if (commonSites.isNotEmpty()) {
            item { LauncherSectionHeader(stringResource(R.string.browser_home_common_sites)) }
            item {
                CommonSitesSection(
                    sites = commonSites,
                    onOpen = { openUrl(it) },
                    onLongPress = { siteToDelete = it },
                )
            }
        }

        // ── 浏览器工具:从「广场 → 更多」分流来的浏览器相关工具(浏览器设置/多窗口/云盘/例程/视频库) ──
        item { LauncherSectionHeader(stringResource(R.string.browser_home_tools)) }
        item { BrowserToolsSection { context.startActivity(Intent(context, it)) } }
    }

    siteToDelete?.let { site ->
        AlertDialog(
            onDismissRequest = { siteToDelete = null },
            title = { Text(stringResource(R.string.browser_home_remove_common_site_title)) },
            text = { Text(site.title.ifBlank { site.url }) },
            confirmButton = {
                TextButton(onClick = {
                    CommonSiteStore.remove(site.url)
                    siteToDelete = null
                    Toast.makeText(context, context.getString(R.string.browser_home_removed_toast), Toast.LENGTH_SHORT).show()
                    refreshTick++
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { siteToDelete = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
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

/** launcher 分区标题(小程序 / 书签 / 常用网站 / 浏览器工具)—— 直接压在壁纸上,像手机桌面的分屏/分组标签。 */
@Composable
private fun LauncherSectionHeader(text: String) {
    Text(
        text,
        color = BrowserHomeTextColor(),
        fontSize = OctopusType.bodyStrong,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = OctopusSpacing.xs, top = OctopusSpacing.xs),
    )
}

/** 每行固定列数的 launcher 图标网格:最后一行落单补等宽 Spacer 保持左对齐(手机桌面观感)。 */
private const val LAUNCHER_COLUMNS = 4

/** 书签在首页最多铺两排图标(4 列 × 2 行),多的在浏览器书签页看全。 */
private const val MAX_BOOKMARK_ICONS = LAUNCHER_COLUMNS * 2

@Composable
private fun <T> LauncherGrid(items: List<T>, cell: @Composable (T, Modifier) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(OctopusSpacing.sm)) {
        items.chunked(LAUNCHER_COLUMNS).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(OctopusSpacing.xs), modifier = Modifier.fillMaxWidth()) {
                row.forEach { cell(it, Modifier.weight(1f)) }
                repeat(LAUNCHER_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** 浏览器桌面的「壁纸」:柔和极光渐变,替代原来的纯白,给主页一点桌面质感(暗色模式用深色版)。 */
private val WallpaperAuroraLight = listOf(Color(0xFFE9F0FF), Color(0xFFF1ECFF), Color(0xFFFFEFF6))
private val WallpaperAuroraDark = listOf(Color(0xFF12131A), Color(0xFF181426), Color(0xFF1B1220))

@Composable
private fun browserWallpaperBrush(): Brush =
    Brush.linearGradient(if (OctopusColors.isLight) WallpaperAuroraLight else WallpaperAuroraDark)

/** Google s2 favicon 服务地址;host 为空时返回 null,走各自的 fallback 图标。 */
private fun faviconUrl(host: String): String? =
    host.takeIf { it.isNotBlank() }?.let { "https://www.google.com/s2/favicons?sz=64&domain=$it" }

/** 小程序图标配色:每个按 name+id 哈希取一组渐变 + 首字母,像真·App 图标一样彩色可区分。 */
private val MINI_APP_GRADS = listOf(
    listOf(Color(0xFF667EEA), Color(0xFF764BA2)),
    listOf(Color(0xFFFF9A9E), Color(0xFFFF6A88)),
    listOf(Color(0xFF43E97B), Color(0xFF38F9D7)),
    listOf(Color(0xFFFFB199), Color(0xFFFF6A5B)),
    listOf(Color(0xFF4FACFE), Color(0xFF00F2FE)),
    listOf(Color(0xFFA18CD1), Color(0xFFFBC2EB)),
    listOf(Color(0xFFF6D365), Color(0xFFFDA085)),
    listOf(Color(0xFF30CFD0), Color(0xFF330867)),
)

/** 彩色小程序图标:渐变底 + 白色首字母,填满 [HomeTile] 的 32dp 图标位。 */
@Composable
private fun MiniAppTileIcon(name: String, id: String) {
    val grad = MINI_APP_GRADS[((name + id).hashCode() and Int.MAX_VALUE) % MINI_APP_GRADS.size]
    val initial = name.trim().take(1).ifBlank { "小" }.uppercase()
    Box(
        modifier = Modifier.fillMaxSize().background(Brush.linearGradient(grad)),
        contentAlignment = Alignment.Center,
    ) {
        Text(initial, color = Color.White, fontSize = OctopusType.bodyStrong, fontWeight = FontWeight.Bold)
    }
}

/** favicon/glyph 类图标的浅色圆角底座:让小尺寸站点图标在壁纸上也有清晰的「App 图标」轮廓。 */
@Composable
private fun LauncherIconSquare(content: @Composable () -> Unit) {
    val bg = if (OctopusThemeStyle.isGlass) Color.White.copy(alpha = 0.85f) else SurfaceColor
    Box(modifier = Modifier.fillMaxSize().background(bg), contentAlignment = Alignment.Center) { content() }
}

/** 我的小程序：[MiniAppRegistry] 里已注册的小程序（含 generate_app 现场生成的），点了直接启动。 */
@Composable
private fun MiniAppsSection(apps: List<PluginManifest>, onLaunch: (String) -> Unit) {
    LauncherGrid(apps) { m, mod ->
        LauncherIcon(label = m.name.ifBlank { m.id }, modifier = mod, onClick = { onLaunch(m.id) }) {
            MiniAppTileIcon(name = m.name.ifBlank { m.id }, id = m.id)
        }
    }
}

/** 书签：[BookmarkManager] 里存的收藏，图标尝试用站点自己的 favicon.ico，加载失败落回书签图标。 */
@Composable
private fun BookmarksSection(bookmarks: List<BookmarkItem>, onOpen: (String) -> Unit) {
    LauncherGrid(bookmarks.take(MAX_BOOKMARK_ICONS)) { b, mod ->
        val host = remember(b.url) { urlHost(b.url) }
        LauncherIcon(label = b.title.ifBlank { host }, modifier = mod, onClick = { onOpen(b.url) }) {
            LauncherIconSquare {
                FaviconIcon(
                    url = faviconUrl(host),
                    tint = OctopusTints.CatKnowledge,
                    fallback = Icons.Filled.Bookmark,
                    contentDescription = b.title,
                )
            }
        }
    }
}

/**
 * 常用网站：[CommonSiteStore] 里存的站点(首次为内置默认值)。长按删除——添加走浏览器内
 * "收藏"弹窗的"添加到主页"，不在这个首页单独做加号入口。
 */
@Composable
private fun CommonSitesSection(
    sites: List<CommonSiteItem>,
    onOpen: (String) -> Unit,
    onLongPress: (CommonSiteItem) -> Unit,
) {
    LauncherGrid(sites) { site, mod ->
        val host = remember(site.url) { urlHost(site.url) }
        LauncherIcon(
            label = site.title.ifBlank { host },
            modifier = mod,
            onClick = { onOpen(site.url) },
            onLongClick = { onLongPress(site) },
        ) {
            LauncherIconSquare {
                FaviconIcon(
                    url = faviconUrl(host),
                    tint = OctopusTints.CatKnowledge,
                    fallback = Icons.Filled.Public,
                    contentDescription = site.title,
                )
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
    // 玻璃模式:更通透的半透明白 —— 原 0.82 太不透明,搜索框像贴了块实心白横条(用户反馈),
    // 与其它玻璃组件(tile 0.22 / icon 0.72)也不协调;边框同步调柔和。
    val omniBg = if (isGlass) Color.White.copy(alpha = 0.55f) else SurfaceColor
    val omniBorder = if (isGlass) Color.White.copy(alpha = 0.35f) else OctopusBackground.glassBorder
    Surface(
        modifier = Modifier.fillMaxWidth().height(54.dp),
        shape = RoundedCornerShape(22.dp),
        color = omniBg,
        border = BorderStroke(0.5.dp, omniBorder),
        // 去掉阴影:玻璃模式下 8dp 阴影让搜索框"浮起",半透明白本体 + 下方阴影 = 视觉两层,
        // 就是用户看到的「内层白横条」。平贴后只剩一层半透明,靠 border 保留轮廓。
        shadowElevation = 0.dp,
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
                    Text(
                        stringResource(R.string.ai_browser_omnibox_hint),
                        color = TextSecondary,
                        fontSize = 15.sp,
                        maxLines = 1,
                    )
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

/** 手机桌面式单个图标格:上方圆角方形图标(56dp)、下方居中标签。onLongClick 非空才支持长按(常用网站删除)。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LauncherIcon(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    icon: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = OctopusSpacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) { icon() }
        Spacer(Modifier.height(OctopusSpacing.xs))
        Text(
            label,
            color = BrowserHomeTextColor(),
            fontSize = OctopusType.tag,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
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

/** 从「广场 → 更多」分流来的浏览器相关工具入口(市场重构第二阶段)。 */
private data class BrowserToolEntry(
    val labelRes: Int,
    val descRes: Int,
    val icon: ImageVector,
    val tint: Color,
    val activity: Class<*>,
)

private val BROWSER_TOOLS = listOf(
    BrowserToolEntry(
        R.string.feat_browser_settings, R.string.feat_browser_desc,
        Icons.Filled.Public, OctopusTints.Browser, BrowserSettingsActivity::class.java,
    ),
    BrowserToolEntry(
        R.string.feat_multiwindow, R.string.feat_multiwindow_desc,
        Icons.Filled.GridView, OctopusTints.Window, MultiWindowActivity::class.java,
    ),
    BrowserToolEntry(
        R.string.feat_clouddrive, R.string.feat_clouddrive_desc,
        Icons.Filled.CloudQueue, OctopusTints.Cloud, CloudDriveActivity::class.java,
    ),
    BrowserToolEntry(
        R.string.feat_routines, R.string.feat_routines_desc,
        Icons.Filled.Schedule, OctopusTints.Routine, RoutinesActivity::class.java,
    ),
    BrowserToolEntry(
        R.string.feat_video, R.string.feat_video_desc,
        Icons.Filled.Movie, OctopusTints.Video, VideoLibraryActivity::class.java,
    ),
)

@Composable
private fun BrowserToolsSection(onOpen: (Class<*>) -> Unit) {
    LauncherGrid(BROWSER_TOOLS) { tool, mod ->
        LauncherIcon(
            label = stringResource(tool.labelRes),
            modifier = mod,
            onClick = { onOpen(tool.activity) },
        ) {
            LauncherIconSquare {
                Icon(
                    tool.icon,
                    contentDescription = null,
                    tint = tool.tint,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}
