package com.apk.claw.android.ui.featurescreens

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apk.claw.android.media.MediaScanner
import com.apk.claw.android.media.PlayerActivity
import com.apk.claw.android.media.WebDAVEntry
import com.apk.claw.android.media.WebDAVScanner
import com.apk.claw.android.media.WebDavMounts
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

private fun isMedia(name: String): Boolean {
    val ext = name.substringAfterLast('.', "").lowercase()
    return ext in MediaScanner.VIDEO_EXTENSIONS || ext in MediaScanner.AUDIO_EXTENSIONS
}

@Composable
fun CloudDriveScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var mounts by remember { mutableStateOf(WebDavMounts.all()) }
    var selected by remember { mutableStateOf<WebDavMounts.Mount?>(null) }
    var pathStack by remember { mutableStateOf(listOf<String>()) }
    var entries by remember { mutableStateOf<List<WebDAVEntry>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showAdd by remember { mutableStateOf(false) }

    fun load(m: WebDavMounts.Mount, path: String) {
        scope.launch {
            busy = true; error = null; entries = null
            val list = withContext(Dispatchers.IO) {
                runCatching { WebDAVScanner.listDirectory(m.baseUrl.trimEnd('/'), path, m.username, m.password) }
                    .getOrElse { emptyList() }
            }
            entries = list
            if (list.isEmpty()) error = "目录为空，或服务器不可达 / 认证失败"
            busy = false
        }
    }
    fun open(m: WebDavMounts.Mount) {
        selected = m; pathStack = listOf(m.rootPath.ifBlank { "/" }); load(m, pathStack.last())
    }
    fun enter(href: String) {
        pathStack = pathStack + href; load(selected!!, href)
    }
    fun up() {
        if (pathStack.size > 1) { pathStack = pathStack.dropLast(1); load(selected!!, pathStack.last()) }
        else { selected = null; entries = null }
    }

    FeatureScaffold(
        title = selected?.name ?: "网盘 / NAS",
        onBack = { if (selected != null) up() else onBack() },
        action = {
            if (selected == null) Text("＋ 添加", color = FPrimary, fontSize = 14.sp,
                modifier = Modifier.clickable { showAdd = true }.padding(8.dp))
        },
    ) {
        if (selected == null) {
            if (mounts.isEmpty()) {
                FEmpty("还没有挂载点。点右上角「＋ 添加」填入 NAS/网盘的 WebDAV 地址（群晖/Nextcloud/坚果云/AList 等都支持），即可浏览与播放。")
            } else {
                LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(vertical = 6.dp)) {
                    items(mounts, key = { it.id }) { m ->
                        FCard {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { open(m) }) {
                                Text("🗄️", fontSize = 18.sp)
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(m.name, color = FText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                    Text(m.baseUrl, color = FMuted, fontSize = 10.sp, maxLines = 1)
                                }
                                Text("✕", color = FMuted, fontSize = 14.sp,
                                    modifier = Modifier.clickable { WebDavMounts.remove(m.id); mounts = WebDavMounts.all() }.padding(6.dp))
                            }
                        }
                    }
                }
            }
        } else {
            // 路径面包屑
            Text(pathStack.last(), color = FMuted, fontSize = 11.sp, maxLines = 1,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            val list = entries
            when {
                busy || list == null -> FEmpty("加载中…")
                list.isEmpty() -> FEmpty(error ?: "目录为空")
                else -> LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(vertical = 6.dp)) {
                    val folders = list.filter { it.isDirectory }
                    val files = list.filter { !it.isDirectory }
                    items(folders, key = { "d" + it.href }) { e ->
                        FCard {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { enter(e.href) }) {
                                Text("📁", fontSize = 16.sp)
                                Spacer(Modifier.width(10.dp))
                                Text(e.name, color = FText, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1)
                                Text("›", color = FMuted, fontSize = 18.sp)
                            }
                        }
                    }
                    items(files, key = { "f" + it.href }) { e ->
                        val playable = isMedia(e.name)
                        FCard {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().let {
                                    if (playable) it.clickable {
                                        val url = WebDavMounts.playUrl(selected!!, e.href)
                                        runCatching { ctx.startActivity(PlayerActivity.intent(ctx, url, null, 0, e.name)) }
                                    } else it
                                },
                            ) {
                                Text(if (playable) "🎬" else "📄", fontSize = 16.sp)
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(e.name, color = if (playable) FText else FMuted, fontSize = 14.sp, maxLines = 1)
                                    Text(MediaScanner.formatSize(e.size), color = FMuted, fontSize = 10.sp)
                                }
                                if (playable) Text("▶", color = FPrimary, fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddMountDialog(
            onDismiss = { showAdd = false },
            onSave = { name, url, path, user, pass ->
                if (url.isNotBlank()) {
                    WebDavMounts.add(
                        WebDavMounts.Mount(
                            id = "wd_" + System.currentTimeMillis(),
                            name = name.ifBlank { url },
                            baseUrl = url.trim(),
                            rootPath = path.ifBlank { "/" }.trim(),
                            username = user.trim(),
                            password = pass,
                        )
                    )
                    mounts = WebDavMounts.all()
                }
                showAdd = false
            },
        )
    }
}

@Composable
private fun AddMountDialog(
    onDismiss: () -> Unit,
    onSave: (String, String, String, String, String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("/") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    val colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = FPrimary, unfocusedBorderColor = FBorder,
        focusedTextColor = FText, unfocusedTextColor = FText, cursorColor = FPrimary,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = FSurface,
        title = { Text("添加 WebDAV 挂载", color = FText, fontSize = 16.sp) },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("名称", color = FMuted) }, colors = colors)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(url, { url = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("http://192.168.1.10:5005", color = FMuted) }, label = { Text("服务器地址", color = FMuted) }, colors = colors)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(path, { path = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("起始路径（如 / 或 /dav）", color = FMuted) }, colors = colors)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(user, { user = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("用户名（可选）", color = FMuted) }, colors = colors)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(pass, { pass = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("密码（可选）", color = FMuted) }, colors = colors)
            }
        },
        confirmButton = { Text("保存", color = FPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { onSave(name, url, path, user, pass) }.padding(8.dp)) },
        dismissButton = { Text("取消", color = FMuted, modifier = Modifier.clickable(onClick = onDismiss).padding(8.dp)) },
    )
}
