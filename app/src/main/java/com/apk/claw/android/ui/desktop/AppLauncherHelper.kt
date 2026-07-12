package com.apk.claw.android.ui.desktop

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class InstalledApp(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val isSystem: Boolean,
)

/**
 * 异步加载已安装应用列表(在 IO 线程)。
 */
@Composable
fun loadInstalledApps(context: Context, limit: Int = 20): List<InstalledApp> {
    val result by produceState(initialValue = emptyList()) {
        value = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
            val apps = pm.queryIntentActivities(intent, 0)
            apps.map { ri ->
                InstalledApp(
                    packageName = ri.activityInfo.packageName,
                    label = ri.loadLabel(pm).toString(),
                    icon = ri.loadIcon(pm),
                    isSystem = ri.activityInfo.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0
                )
            }.sortedBy { it.label.lowercase() }.take(limit)
        }
    }
    return result
}

/**
 * 启动第三方应用。
 */
fun launchApp(context: Context, packageName: String): Boolean {
    val pm = context.packageManager
    val intent = pm.getLaunchIntentForPackage(packageName) ?: return false
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }
}

/**
 * Drawable 转 ImageBitmap(供 Compose 渲染)。
 */
fun Drawable.toImageBitmap(): ImageBitmap? {
    return try {
        val bitmap = Bitmap.createBitmap(
            intrinsicWidth.coerceAtLeast(1),
            intrinsicHeight.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        setBounds(0, 0, canvas.width, canvas.height)
        draw(canvas)
        bitmap.asImageBitmap()
    } catch (e: Exception) {
        null
    }
}
