package com.apk.claw.android.ui.featurescreens

import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apk.claw.android.media.MediaScanner
import com.apk.claw.android.media.PlayerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class VideoLibraryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { VideoLibraryScreen(onBack = { finish() }) }
        runCatching { window.statusBarColor = android.graphics.Color.BLACK }
    }
}

@Composable
fun VideoLibraryScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var files by remember { mutableStateOf<List<com.apk.claw.android.media.MediaFile>?>(null) }

    LaunchedEffect(Unit) {
        files = withContext(Dispatchers.IO) {
            runCatching {
                val root = Environment.getExternalStorageDirectory().absolutePath
                MediaScanner.scan(root, recursive = true, type = "video")
            }.getOrDefault(emptyList())
        }
    }

    FeatureScaffold(title = "视频", onBack = onBack) {
        val list = files
        when {
            list == null -> FEmpty("扫描本地视频…")
            list.isEmpty() -> FEmpty("未扫描到视频文件。把视频放到手机存储后再来，或在对话页让 Agent 播放在线/网盘视频。")
            else -> LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(vertical = 6.dp)) {
                item {
                    Text("共 ${list.size} 个视频", color = FMuted, fontSize = 11.sp, modifier = Modifier.padding(start = 16.dp, bottom = 4.dp))
                }
                items(list, key = { it.path }) { f ->
                    FCard {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable {
                                val sub = runCatching { MediaScanner.findMatchingSubtitle(f.path) }.getOrNull()
                                runCatching {
                                    ctx.startActivity(PlayerActivity.intent(ctx, f.path, sub, 0, f.name))
                                }
                            },
                        ) {
                            Text("🎬", fontSize = 18.sp)
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(f.name, color = FText, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                                Text(
                                    MediaScanner.formatSize(f.sizeBytes) + " · " + f.extension.uppercase() + (if (f.isBluRay) " · 蓝光" else ""),
                                    color = FMuted, fontSize = 10.sp,
                                )
                            }
                            Text("▶", color = FPrimary, fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    }
}
