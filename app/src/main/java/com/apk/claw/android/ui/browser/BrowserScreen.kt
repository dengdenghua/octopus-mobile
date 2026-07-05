package com.apk.claw.android.ui.browser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.apk.claw.android.R
import com.apk.claw.android.octopus_mobile.browser.BrowserEngine
import com.apk.claw.android.octopus_mobile.browser.BrowserEngineFactory
import com.apk.claw.android.octopus_mobile.browser.EngineEvent
import com.apk.claw.android.octopus_mobile.browser.SearchEngines
import com.apk.claw.android.tool.ToolRegistry
import com.apk.claw.android.ui.compose.screen.DiscoverScreen
import com.apk.claw.android.ui.compose.theme.OctopusColors
import com.apk.claw.android.ui.compose.theme.OctopusShape
import com.apk.claw.android.utils.KVUtils
import java.util.Locale

private const val TAG = "BrowserScreen"

@Composable
fun BrowserScreen(
    initialUrl: String? = null,
    onClose: () -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val engine = remember { BrowserEngineFactory.selectBest(context) }

    // 浏览器状态
    var isHomeVisible by rememberSaveable { mutableStateOf(initialUrl.isNullOrEmpty()) }
    var urlText by rememberSaveable { mutableStateOf("") }
    var currentUrl by rememberSaveable { mutableStateOf("") }
    var pageTitle by rememberSaveable { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var aiMode by rememberSaveable { mutableStateOf(true) }

    // Sheet 显示状态
    var showSettings by remember { mutableStateOf(false) }
    var showWindows by remember { mutableStateOf(false) }
    var showAi by remember { mutableStateOf(false) }
    var showBookmark by remember { mutableStateOf(false) }

    // TTS
    val tts = remember { mutableStateOf<TextToSpeech?>(null) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> engine.onPause()
                Lifecycle.Event.ON_RESUME -> engine.onResume()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            engine.destroy()
            tts.value?.stop()
            tts.value?.shutdown()
            tts.value = null
            ToolRegistry.clearBrowserEngine()
        }
    }

    // 注册为当前浏览器引擎
    LaunchedEffect(engine) {
        ToolRegistry.setBrowserEngine(engine)
        BrowserTabsStore.ensureAtLeastOne(context.getString(R.string.browser_new_tab))
    }

    // 引擎事件监听
    LaunchedEffect(engine, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            engine.events().collect { event ->
                when (event) {
                    is EngineEvent.PageStarted -> {
                        isLoading = true
                        progress = 0
                    }
                    is EngineEvent.PageFinished -> {
                        isLoading = false
                        progress = 100
                        currentUrl = event.url
                        pageTitle = event.title
                        urlText = SearchEngines.extractQuery(event.url) ?: event.url
                        BrowserTabsStore.updateCurrent(event.url, event.title.ifBlank { event.url })
                    }
                    is EngineEvent.ProgressChanged -> {
                        progress = event.percent
                    }
                    is EngineEvent.Error -> {
                        isLoading = false
                        Toast.makeText(
                            context,
                            context.getString(R.string.browser_load_error, event.description),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    is EngineEvent.DownloadStart -> {
                        Toast.makeText(
                            context,
                            context.getString(R.string.browser_download_notification, event.suggestedFilename),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    is EngineEvent.ConsoleMessage -> {
                        Log.d(TAG, "[${event.level}] ${event.message}")
                    }
                    is EngineEvent.JsAlert -> {
                        // JS alert 在 Compose 层处理较繁琐，先保持默认由引擎内部处理
                    }
                }
            }
        }
    }

    // 初始 URL
    LaunchedEffect(initialUrl) {
        if (!initialUrl.isNullOrEmpty()) {
            navigateTo(engine, initialUrl) { navUrl ->
                urlText = SearchEngines.extractQuery(navUrl) ?: navUrl
            }
            isHomeVisible = false
        }
    }

    // 导航函数
    val navigate: (String) -> Unit = { input ->
        isHomeVisible = false
        val url = if (input.contains(".") && !input.contains(" ")) {
            if (input.startsWith("http://") || input.startsWith("https://")) input else "https://$input"
        } else {
            SearchEngines.byId(KVUtils.getSearchEngine()).searchUrl(input)
        }
        urlText = SearchEngines.extractQuery(url) ?: url
        currentUrl = url
        engine.navigate(url)
    }

    // 返回手势
    BackHandler {
        if (isHomeVisible) {
            onClose()
        } else {
            engine.evaluateJs("window.history.length") { len ->
                val n = len?.trim()?.trim('"')?.toIntOrNull() ?: 1
                if (n > 1) engine.evaluateJs("window.history.back()") else isHomeVisible = true
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 背景
        BrowserWallpaper()

        // 内容层
        Column(modifier = Modifier.fillMaxSize()) {
            BrowserTopBar(
                urlText = urlText,
                onUrlTextChange = { urlText = it },
                aiMode = aiMode,
                onToggleAiMode = { aiMode = !aiMode },
                isLoading = isLoading,
                onClose = onClose,
                onHomeClick = { isHomeVisible = !isHomeVisible },
                onRefreshClick = {
                    if (isLoading) engine.evaluateJs("window.stop()") else engine.navigate(currentUrl)
                },
                onSubmit = {
                    val t = urlText.trim()
                    if (t.isNotEmpty()) {
                        if (aiMode && isPageCommand(context, t)) {
                            Toast.makeText(context, context.getString(R.string.browser_ai_operate_toast), Toast.LENGTH_SHORT).show()
                            runAgentOnPage(context, t)
                        } else {
                            navigate(t)
                        }
                    }
                },
                modifier = Modifier.statusBarsPadding(),
            )

            BrowserWebViewWithHome(
                engine = engine,
                isHomeVisible = isHomeVisible,
                onOpenUrl = { url -> url?.takeIf { it.isNotBlank() }?.let { navigate(it) } },
                onClose = onClose,
                modifier = Modifier.weight(1f),
            )

            // 进度条
            if (isLoading) {
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0, 100) / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                    color = OctopusColors.Primary,
                    trackColor = Color.Transparent,
                )
            }
        }

        // 底栏
        AnimatedVisibility(
            visible = !isHomeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            BrowserBottomBar(
                windowCount = BrowserTabsStore.count().coerceAtLeast(1),
                onSettingsClick = { showSettings = true },
                onAiClick = { showAi = true },
                onWindowsClick = { showWindows = true },
                modifier = Modifier.navigationBarsPadding(),
            )
        }

        // 各种 Sheet / Dialog
        if (showSettings) {
            BrowserSettingsSheet(
                onDismiss = { showSettings = false },
                onRefresh = { engine.navigate(currentUrl) },
                onBookmarks = { showBookmark = true },
                onCopyLink = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("url", currentUrl))
                    Toast.makeText(context, context.getString(R.string.browser_link_copied), Toast.LENGTH_SHORT).show()
                },
                onOpenInSystem = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl))) }
                },
                onEngineSettings = {
                    runCatching { context.startActivity(Intent(context, com.apk.claw.android.ui.featurescreens.BrowserSettingsActivity::class.java)) }
                },
            )
        }

        if (showWindows) {
            BrowserWindowsSheet(
                onDismiss = { showWindows = false },
                onSwitch = { tab ->
                    if (tab.url.isBlank()) isHomeVisible = true else navigate(tab.url)
                },
                onClose = { tab ->
                    BrowserTabsStore.close(tab.id, context.getString(R.string.browser_new_tab))
                    if (tab.id == BrowserTabsStore.currentId.value) {
                        BrowserTabsStore.current()?.let { if (it.url.isBlank()) isHomeVisible = true else navigate(it.url) }
                    }
                },
                onNewWindow = {
                    BrowserTabsStore.newTab(context.getString(R.string.browser_new_tab))
                    isHomeVisible = true
                },
            )
        }

        if (showAi) {
            BrowserAiSheet(
                onDismiss = { showAi = false },
                onRunAi = { question, onAnswer ->
                    runAi(context, question, onAnswer)
                },
                onRunAgent = { task ->
                    showAi = false
                    runAgentOnPage(context, task)
                },
                onReader = { pageText -> showReader(context, pageText) },
                onSpeak = { pageText, ttsRef -> speakPage(context, pageText, ttsRef) },
                tts = tts,
            )
        }

        if (showBookmark) {
            BrowserBookmarkDialog(
                currentUrl = currentUrl,
                currentTitle = pageTitle.ifBlank { currentUrl },
                onDismiss = { showBookmark = false },
                onNavigate = { url -> navigate(url) },
            )
        }
    }
}

@Composable
private fun BrowserWallpaper() {
    Box(modifier = Modifier.fillMaxSize().background(OctopusColors.Background))
}

@Composable
@Suppress("LongMethod")
private fun BrowserTopBar(
    urlText: String,
    onUrlTextChange: (String) -> Unit,
    aiMode: Boolean,
    onToggleAiMode: () -> Unit,
    isLoading: Boolean,
    onClose: () -> Unit,
    onHomeClick: () -> Unit,
    onRefreshClick: () -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val textColor = OctopusColors.TextPrimary
    val mutedColor = OctopusColors.TextSecondary

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(OctopusColors.Surface)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 关闭
        BrowserCapsuleButton(
            onClick = onClose,
            modifier = Modifier.size(36.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.advanced_action_close), tint = mutedColor, modifier = Modifier.size(20.dp))
        }

        Spacer(Modifier.width(6.dp))

        // 地址栏
        Row(
            modifier = Modifier
                .weight(1f)
                .height(40.dp)
                .clip(OctopusShape.capsule)
                .background(OctopusColors.SurfaceVariant)
                .border(
                    width = 1.dp,
                    color = OctopusColors.Border,
                    shape = OctopusShape.capsule,
                )
                .padding(start = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable { onToggleAiMode() },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (aiMode) "AI" else "URL",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = OctopusColors.Primary,
                )
            }

            BasicTextField(
                value = urlText,
                onValueChange = onUrlTextChange,
                singleLine = true,
                textStyle = TextStyle(fontSize = 13.sp, color = textColor),
                cursorBrush = SolidColor(OctopusColors.Primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { onSubmit() }),
                decorationBox = { innerTextField ->
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                        if (urlText.isEmpty()) {
                            Text(
                                text = stringResource(if (aiMode) R.string.browser_url_hint else R.string.browser_input_url),
                                fontSize = 13.sp,
                                color = mutedColor,
                            )
                        }
                        innerTextField()
                    }
                },
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            )

            BrowserCapsuleButton(
                onClick = onRefreshClick,
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    imageVector = if (isLoading) Icons.Filled.Close else Icons.Filled.Refresh,
                    contentDescription = stringResource(R.string.browser_refresh_button),
                    tint = mutedColor,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        Spacer(Modifier.width(6.dp))

        // 首页
        BrowserCapsuleButton(onClick = onHomeClick, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.Home, contentDescription = stringResource(R.string.browser_home_button), tint = mutedColor, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun BrowserBottomBar(
    windowCount: Int,
    onSettingsClick: () -> Unit,
    onAiClick: () -> Unit,
    onWindowsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 16.dp)
            .wrapContentSize()
            .clip(OctopusShape.xl)
            .background(OctopusColors.Surface)
            .border(
                width = 1.dp,
                color = OctopusColors.Border,
                shape = OctopusShape.xl,
            )
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrowserCapsuleButton(onClick = onSettingsClick, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Filled.Menu, contentDescription = stringResource(R.string.browser_settings_title), tint = OctopusColors.TextPrimary, modifier = Modifier.size(22.dp))
        }

        BrowserCapsuleButton(
            onClick = onAiClick,
            modifier = Modifier
                .padding(horizontal = 2.dp)
                .height(40.dp)
                .wrapContentWidth(),
            backgroundColor = OctopusColors.Primary,
        ) {
            Text(
                text = stringResource(R.string.browser_ask_ai_button),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = OctopusColors.OnPrimary,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }

        BrowserCapsuleButton(
            onClick = onWindowsClick,
            modifier = Modifier.size(40.dp),
            backgroundColor = OctopusColors.SurfaceVariant,
        ) {
            Text(
                text = windowCount.toString(),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = OctopusColors.Primary,
            )
        }
    }
}

@Composable
private fun BrowserCapsuleButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    backgroundColor: Color = Color.Transparent,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(OctopusShape.capsule)
            .background(backgroundColor)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun BrowserWebViewContainer(engine: BrowserEngine) {
    AndroidView(
        factory = { ctx -> engine.createView(ctx) },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun BrowserWebViewWithHome(
    engine: BrowserEngine,
    isHomeVisible: Boolean,
    onOpenUrl: (String?) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        BrowserWebViewContainer(engine = engine)

        AnimatedVisibility(
            visible = isHomeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            BrowserHomeLayer(
                onOpenUrl = onOpenUrl,
                onClose = onClose,
            )
        }
    }
}

@Composable
private fun BrowserHomeLayer(
    onOpenUrl: (String?) -> Unit,
    onClose: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        DiscoverScreen(onOpenUrl = onOpenUrl)

        // 右上角关闭
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 40.dp, end = 12.dp)
                .size(36.dp)
                .clip(OctopusShape.large)
                .background(OctopusColors.Surface)
                .clickable { onClose() },
            contentAlignment = Alignment.Center,
        ) {
            Text("×", fontSize = 20.sp, color = OctopusColors.TextPrimary)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowserSettingsSheet(
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onBookmarks: () -> Unit,
    onCopyLink: () -> Unit,
    onOpenInSystem: () -> Unit,
    onEngineSettings: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = OctopusColors.Surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = stringResource(R.string.browser_settings_title),
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = OctopusColors.TextPrimary,
            )
            Spacer(Modifier.height(12.dp))
            SettingsRow(stringResource(R.string.browser_refresh_page)) { onDismiss(); onRefresh() }
            SettingsRow(stringResource(R.string.browser_engine_settings)) { onDismiss(); onEngineSettings() }
            SettingsRow(stringResource(R.string.browser_bookmarks_button)) { onDismiss(); onBookmarks() }
            SettingsRow(stringResource(R.string.browser_copy_link)) { onDismiss(); onCopyLink() }
            SettingsRow(stringResource(R.string.browser_open_in_system)) { onDismiss(); onOpenInSystem() }
        }
    }
}

@Composable
private fun SettingsRow(title: String, onClick: () -> Unit) {
    Text(
        text = title,
        fontSize = 15.sp,
        color = OctopusColors.TextPrimary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(OctopusShape.large)
            .background(OctopusColors.SurfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowserWindowsSheet(
    onDismiss: () -> Unit,
    onSwitch: (BrowserTabsStore.Tab) -> Unit,
    onClose: (BrowserTabsStore.Tab) -> Unit,
    onNewWindow: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val tabs by BrowserTabsStore.tabs.collectAsState()
    val curId by BrowserTabsStore.currentId.collectAsState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = OctopusColors.Surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.browser_windows_count, tabs.size),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = OctopusColors.TextPrimary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.browser_new_window),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = OctopusColors.OnPrimary,
                    modifier = Modifier
                        .clip(OctopusShape.large)
                        .background(OctopusColors.Primary)
                        .clickable { onDismiss(); onNewWindow() }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            tabs.forEach { tab ->
                val active = tab.id == curId
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(OctopusShape.large)
                        .background(
                            if (active) OctopusColors.Primary.copy(alpha = 0.15f)
                            else OctopusColors.SurfaceVariant
                        )
                        .border(
                            width = 1.dp,
                            color = if (active) OctopusColors.Primary else OctopusColors.Border,
                            shape = OctopusShape.large,
                        )
                        .clickable { onDismiss(); onSwitch(tab) }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = tab.title.ifBlank { stringResource(R.string.browser_new_tab) },
                            fontSize = 14.sp,
                            color = OctopusColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = tab.url.ifBlank { "—" },
                            fontSize = 11.sp,
                            color = OctopusColors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .clickable { onClose(tab) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "×",
                            fontSize = 18.sp,
                            color = OctopusColors.TextSecondary,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowserAiSheet(
    onDismiss: () -> Unit,
    onRunAi: (String, (String) -> Unit) -> Unit,
    onRunAgent: (String) -> Unit,
    onReader: (String?) -> Unit,
    onSpeak: (String?, MutableState<TextToSpeech?>) -> Unit,
    tts: MutableState<TextToSpeech?>,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    var question by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }
    var thinking by remember { mutableStateOf(false) }

    val pageText = remember {
        com.apk.claw.android.service.ClawAccessibilityService.getInstance()
            ?.let { runCatching { it.screenTree }.getOrNull() }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = OctopusColors.Surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = stringResource(R.string.browser_ai_sheet_title),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = OctopusColors.TextPrimary,
            )
            if (pageText.isNullOrBlank()) {
                Text(
                    text = stringResource(R.string.browser_accessibility_warning),
                    fontSize = 11.sp,
                    color = OctopusColors.Warning,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            // 快捷 chips
            Row(modifier = Modifier.padding(top = 12.dp)) {
                AiChip(stringResource(R.string.browser_chip_summarize)) {
                    thinking = true
                    onRunAi(context.getString(R.string.browser_ai_prompt_summarize)) {
                        answer = it; thinking = false
                    }
                }
                Spacer(Modifier.width(8.dp))
                AiChip(stringResource(R.string.browser_chip_key_points)) {
                    thinking = true
                    onRunAi(context.getString(R.string.browser_ai_prompt_key_points)) {
                        answer = it; thinking = false
                    }
                }
                Spacer(Modifier.width(8.dp))
                AiChip(stringResource(R.string.browser_chip_translate)) {
                    thinking = true
                    onRunAi(context.getString(R.string.browser_ai_prompt_translate)) {
                        answer = it; thinking = false
                    }
                }
            }
            Row(modifier = Modifier.padding(top = 8.dp)) {
                AiChip(stringResource(R.string.browser_chip_reader)) { onDismiss(); onReader(pageText) }
                Spacer(Modifier.width(8.dp))
                AiChip(stringResource(R.string.browser_chip_speak)) { onDismiss(); onSpeak(pageText, tts) }
            }

            // 输入框
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 16.dp),
            ) {
                BasicTextField(
                    value = question,
                    onValueChange = { question = it },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 14.sp, color = OctopusColors.TextPrimary),
                    cursorBrush = SolidColor(OctopusColors.Primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        if (question.isNotBlank()) {
                            thinking = true
                            onRunAi(question) { answer = it; thinking = false }
                        }
                    }),
                    decorationBox = { innerTextField ->
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                            if (question.isEmpty()) {
                                Text(stringResource(R.string.browser_input_ask_or_operate), fontSize = 14.sp, color = OctopusColors.TextSecondary)
                            }
                            innerTextField()
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(OctopusShape.capsule)
                        .background(OctopusColors.SurfaceVariant)
                        .padding(horizontal = 16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.browser_ask_button),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = OctopusColors.OnPrimary,
                    modifier = Modifier
                        .clip(OctopusShape.capsule)
                        .background(OctopusColors.Primary)
                        .clickable {
                            if (question.isNotBlank()) {
                                thinking = true
                                onRunAi(question) { answer = it; thinking = false }
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.browser_execute_button),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = OctopusColors.OnPrimary,
                    modifier = Modifier
                        .clip(OctopusShape.capsule)
                        .background(OctopusColors.Warning)
                        .clickable {
                            if (question.isNotBlank()) {
                                onDismiss(); onRunAgent(question)
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }

            if (thinking) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(top = 16.dp).size(20.dp),
                    color = OctopusColors.Primary,
                )
            } else if (answer.isNotBlank()) {
                Text(
                    text = answer,
                    fontSize = 14.sp,
                    color = OctopusColors.TextPrimary,
                    lineHeight = 20.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 0.dp, max = 220.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(top = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun AiChip(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        fontSize = 12.sp,
        color = OctopusColors.Primary,
        modifier = Modifier
            .clip(OctopusShape.large)
            .background(OctopusColors.Primary.copy(alpha = 0.15f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun BrowserBookmarkDialog(
    currentUrl: String,
    currentTitle: String,
    onDismiss: () -> Unit,
    onNavigate: (String) -> Unit,
) {
    val context = LocalContext.current
    val bookmarks = remember { BookmarkManager.getAll() }
    val isBookmarked = remember(currentUrl) { BookmarkManager.isBookmarked(currentUrl) }
    val isCommonSite = remember(currentUrl) { CommonSiteStore.contains(currentUrl) }

    val items = mutableListOf<String>().apply {
        add(context.getString(if (isBookmarked) R.string.browser_remove_bookmark else R.string.browser_bookmark_current))
        add(context.getString(if (isCommonSite) R.string.browser_remove_common_site else R.string.browser_add_common_site))
        add(context.getString(R.string.browser_saved_bookmarks_header))
        bookmarks.forEach { add("${it.title}\n${it.url}") }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.browser_bookmarks_button)) },
        text = {
            LazyColumn {
                items(items.size, key = { it }) { index ->
                    Text(
                        text = items[index],
                        fontSize = if (index > 2) 14.sp else 15.sp,
                        color = OctopusColors.TextPrimary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                when (index) {
                                    0 -> {
                                        if (isBookmarked) BookmarkManager.remove(currentUrl)
                                        else BookmarkManager.add(currentUrl, currentTitle.ifEmpty { currentUrl })
                                        onDismiss()
                                    }
                                    1 -> {
                                        if (isCommonSite) CommonSiteStore.remove(currentUrl)
                                        else CommonSiteStore.add(currentUrl, currentTitle.ifEmpty { currentUrl })
                                        onDismiss()
                                    }
                                    else -> {
                                        val bookmark = bookmarks[index - 3]
                                        onDismiss(); onNavigate(bookmark.url)
                                    }
                                }
                            }
                            .padding(vertical = 12.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.advanced_action_close)) }
        },
    )
}

// ── 工具函数 ──

private fun navigateTo(engine: BrowserEngine, input: String, onUrl: ((String) -> Unit)? = null) {
    val url = if (input.contains(".") && !input.contains(" ")) {
        if (input.startsWith("http://") || input.startsWith("https://")) input else "https://$input"
    } else {
        SearchEngines.byId(KVUtils.getSearchEngine()).searchUrl(input)
    }
    onUrl?.invoke(url)
    engine.navigate(url)
}

private fun isPageCommand(context: Context, s: String): Boolean {
    if (s.startsWith("http") || (s.contains(".") && !s.contains(" "))) return false
    val t = s.lowercase()
    val markers = context.getString(R.string.browser_page_cmd_markers).split(",").map { it.trim() }
    return markers.any { t.contains(it) }
}

private fun runAgentOnPage(context: Context, task: String) {
    if (!com.apk.claw.android.ui.compose.screen.ChatAgentBridge.isConfigured()) {
        Toast.makeText(context, context.getString(R.string.browser_configure_api_key), Toast.LENGTH_SHORT).show()
        return
    }
    val prompt = context.getString(R.string.browser_agent_prompt, task)
    com.apk.claw.android.ui.compose.screen.ChatAgentBridge.run(prompt, onTool = { _, _, _, _ -> }, onText = {}, onDone = {}, onError = {})
}

private fun runAi(context: Context, question: String, onAnswer: (String) -> Unit) {
    if (!com.apk.claw.android.ui.compose.screen.ChatAgentBridge.isConfigured()) {
        onAnswer(context.getString(R.string.browser_configure_api_key_text))
        return
    }
    val pageText = com.apk.claw.android.service.ClawAccessibilityService.getInstance()
        ?.let { runCatching { it.screenTree }.getOrNull() }
    val ctx = if (pageText.isNullOrBlank()) "" else "\n\n【当前网页内容】\n" + pageText.take(4000)
    val prompt = context.getString(R.string.browser_ai_system_prompt, question, ctx)
    val sb = StringBuilder()
    onAnswer(context.getString(R.string.browser_thinking_status))
    com.apk.claw.android.ui.compose.screen.ChatAgentBridge.run(
        prompt,
        onTool = { _, _, _, _ -> },
        onText = { t -> sb.append(t); onAnswer(sb.toString()) },
        onDone = { d -> onAnswer(if (sb.isNotEmpty()) sb.toString() else d) },
        onError = { e -> onAnswer(context.getString(R.string.browser_error_message, e)) },
    )
}

private fun showReader(context: Context, pageText: String?) {
    val readable = extractReadableText(context, pageText)
    if (readable.isBlank()) {
        Toast.makeText(context, context.getString(R.string.browser_cannot_read_content), Toast.LENGTH_SHORT).show()
        return
    }
    // 阅读模式先用 AlertDialog 简单承载；后续可升级为独立页面
    androidx.appcompat.app.AlertDialog.Builder(context)
        .setMessage(readable)
        .setPositiveButton(android.R.string.ok, null)
        .show()
}

private fun speakPage(context: Context, pageText: String?, ttsRef: MutableState<TextToSpeech?>) {
    val readable = extractReadableText(context, pageText).take(3000)
    if (readable.isBlank()) {
        Toast.makeText(context, context.getString(R.string.browser_no_content_to_speak), Toast.LENGTH_SHORT).show()
        return
    }
    val t = ttsRef.value
    if (t != null && t.isSpeaking) {
        t.stop(); return
    }
    if (ttsRef.value == null) {
        ttsRef.value = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsRef.value?.language = Locale.CHINESE
                ttsRef.value?.speak(readable, TextToSpeech.QUEUE_FLUSH, null, "page")
            }
        }
    } else {
        ttsRef.value?.language = Locale.CHINESE
        ttsRef.value?.speak(readable, TextToSpeech.QUEUE_FLUSH, null, "page")
    }
}

private fun extractReadableText(context: Context, tree: String?): String {
    if (tree.isNullOrBlank()) return ""
    val chrome = context.getString(R.string.browser_chrome_filter).split(",").map { it.trim() }.toSet()
    val seen = LinkedHashSet<String>()
    Regex("(?:text|desc)=\"([^\"]+)\"").findAll(tree).forEach { m ->
        val s = m.groupValues[1].trim()
        if (s.length >= 2 && s !in chrome && !s.startsWith("http")) seen.add(s)
    }
    return seen.joinToString("\n")
}
