package com.apk.claw.android.ui.featurescreens

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apk.claw.android.media.CloudDrive
import com.apk.claw.android.media.CloudDriveManager
import com.apk.claw.android.media.MediaFile
import com.apk.claw.android.media.MediaType
import com.apk.claw.android.media.PlayerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CloudDriveActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CloudDriveScreen(onBack = { finish() }) }
        runCatching { window.statusBarColor = android.graphics.Color.BLACK }
    }
}

@Composable
fun CloudDriveScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(runCatching { CloudDriveManager.isRunning() }.getOrDefault(false)) }
    var drives by remember { mutableStateOf<List<CloudDrive>?>(null) }
    var selected by remember { mutableStateOf<CloudDrive?>(null) }
    var files by remember { mutableStateOf<List<MediaFile>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    var showCfg by remember { mutableStateOf(false) }

    fun loadDrives() {
        scope.launch {
            busy = true
            drives = withContext(Dispatchers.IO) { runCatching { CloudDriveManager.listMountedDrives() }.getOrDefault(emptyList()) }
            busy = false
        }
    }
    LaunchedEffect(running) { if (running) loadDrives() }

    fun openDrive(d: CloudDrive) {
        selected = d
        files = null
        scope.launch {
            files = withContext(Dispatchers.IO) { runCatching { CloudDriveManager.listFiles(d.name, "/") }.getOrDefault(emptyList()) }
        }
    }

    FeatureScaffold(
        title = if (selected == null) "网盘" else selected!!.name,
        onBack = { if (selected != null) { selected = null } else onBack() },
        action = {
            if (selected == null) Text("配置", color = FPrimary, fontSize = 14.sp, modifier = Modifier.clickable { showCfg = true }.padding(8.dp))
        },
    ) {
        if (selected == null) {
            // 状态卡
            FCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (running) "服务运行中" else "服务未启动", color = FText, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (running) FWarning.copy(alpha = 0.2f) else FPrimary.copy(alpha = 0.2f),
                        modifier = Modifier.clickable {
                            scope.launch {
                                busy = true
                                val msg = withContext(Dispatchers.IO) {
                                    runCatching { if (running) CloudDriveManager.stop() else CloudDriveManager.start() }.getOrDefault("操作失败")
                                }
                                running = runCatching { CloudDriveManager.isRunning() }.getOrDefault(false)
                                toast = msg
                                busy = false
                            }
                        },
                    ) {
                        Text(if (running) "停止" else "启动", color = if (running) FWarning else FPrimary,
                            fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(runCatching { CloudDriveManager.getServerUrl() }.getOrDefault("—"), color = FMuted, fontSize = 10.sp)
            }
            toast?.let { Text(it, color = FPrimary, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) }

            when {
                busy && drives == null -> FEmpty("加载中…")
                drives.isNullOrEmpty() -> FEmpty("没有挂载的网盘。点右上角「配置」填入 WebDAV 服务器地址与账号后启动。")
                else -> LazyColumn(modifier = Modifier.weight(1f)) {
                    item { FSectionTitle("已挂载 (${drives!!.size})") }
                    items(drives!!, key = { it.name }) { d ->
                        FCard {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { openDrive(d) }) {
                                Text("☁️", fontSize = 18.sp)
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(d.name, color = FText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    Text(d.url, color = FMuted, fontSize = 10.sp, maxLines = 1)
                                }
                                Text("›", color = FMuted, fontSize = 18.sp)
                            }
                        }
                    }
                }
            }
        } else {
            // 文件列表
            val list = files
            when {
                list == null -> FEmpty("加载文件…")
                list.isEmpty() -> FEmpty("此网盘没有可播放的媒体文件")
                else -> LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(vertical = 6.dp)) {
                    items(list, key = { it.path }) { f ->
                        FCard {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    if (f.type == MediaType.VIDEO || f.type == MediaType.AUDIO) {
                                        val url = runCatching { CloudDriveManager.buildPlayUrl(selected!!.name, f.path) }.getOrNull()
                                        if (url != null) runCatching { ctx.startActivity(PlayerActivity.intent(ctx, url, null, 0, f.name)) }
                                    }
                                },
                            ) {
                                Text(if (f.type == MediaType.VIDEO) "🎬" else if (f.type == MediaType.AUDIO) "🎵" else "📄", fontSize = 16.sp)
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(f.name, color = FText, fontSize = 14.sp, maxLines = 1)
                                    Text(CloudDriveManager.let { runCatching { com.apk.claw.android.media.MediaScanner.formatSize(f.sizeBytes) }.getOrDefault("") }, color = FMuted, fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCfg) {
        CloudConfigDialog(
            initialUrl = runCatching { CloudDriveManager.getServerUrl() }.getOrDefault(""),
            initialUser = runCatching { CloudDriveManager.getUsername() }.getOrDefault(""),
            onDismiss = { showCfg = false },
            onSave = { url, user, pass ->
                runCatching {
                    if (url.isNotBlank()) CloudDriveManager.setServerUrl(url.trim())
                    CloudDriveManager.setCredentials(user.trim(), pass)
                }
                showCfg = false
                toast = "已保存配置，点「启动」连接"
            },
        )
    }
}

@Composable
private fun CloudConfigDialog(
    initialUrl: String,
    initialUser: String,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit,
) {
    var url by remember { mutableStateOf(initialUrl) }
    var user by remember { mutableStateOf(initialUser) }
    var pass by remember { mutableStateOf("") }
    val colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = FPrimary, unfocusedBorderColor = FBorder,
        focusedTextColor = FText, unfocusedTextColor = FText, cursorColor = FPrimary,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = FSurface,
        title = { Text("WebDAV 配置", color = FText, fontSize = 16.sp) },
        text = {
            Column {
                OutlinedTextField(url, { url = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("http://192.168.1.10:5005", color = FMuted) }, label = { Text("服务器地址", color = FMuted) }, colors = colors)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(user, { user = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("用户名", color = FMuted) }, colors = colors)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(pass, { pass = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("密码", color = FMuted) }, colors = colors)
            }
        },
        confirmButton = { Text("保存", color = FPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { onSave(url, user, pass) }.padding(8.dp)) },
        dismissButton = { Text("取消", color = FMuted, modifier = Modifier.clickable(onClick = onDismiss).padding(8.dp)) },
    )
}
