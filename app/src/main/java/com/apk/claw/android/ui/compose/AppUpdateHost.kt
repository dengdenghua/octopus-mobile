package com.apk.claw.android.ui.compose

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.apk.claw.android.update.AppUpdater
import kotlinx.coroutines.launch

/**
 * 在线更新弹窗宿主 —— 挂在 App 根部(MainActivity)。观察 [AppUpdater.available]:
 * 一旦发现新版本(自动启动检查 或 设置页手动检查),弹出「发现新版本」对话框,
 * 点更新即下载(带进度)并拉起系统安装器。force=true 时不给「稍后」。
 */
@Composable
fun AppUpdateHost() {
    val ctx = LocalContext.current
    val info by AppUpdater.available.collectAsState()
    var progress by remember { mutableStateOf<Float?>(null) }
    val scope = rememberCoroutineScope()

    val u = info ?: return
    AlertDialog(
        onDismissRequest = { if (!u.force && progress == null) AppUpdater.dismiss() },
        title = { Text("发现新版本 ${u.versionName}") },
        text = {
            Column {
                Text(u.notes.ifBlank { "有可用更新,建议升级到最新版本。" })
                progress?.let { p ->
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                    Text("下载中 ${(p * 100).toInt()}%")
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = progress == null,
                onClick = {
                    progress = 0f
                    scope.launch {
                        val r = AppUpdater.downloadAndInstall(ctx, u) { p -> progress = p }
                        if (r.isFailure) {
                            progress = null
                            Toast.makeText(ctx, "下载失败:${r.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                        }
                        // 成功:系统安装器已拉起,弹窗保留(用户装完回来自然消失)。
                    }
                },
            ) { Text(if (progress == null) "立即更新" else "下载中…") }
        },
        dismissButton = {
            if (!u.force && progress == null) {
                TextButton(onClick = { AppUpdater.dismiss() }) { Text("稍后") }
            }
        },
    )
}
