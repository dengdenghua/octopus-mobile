@file:Suppress("PackageNaming")

package com.apk.claw.android.octopus_mobile.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File

/**
 * 浏览器自定义背景壁纸 —— 用户在「浏览器设置」上传的图片,存 filesDir/browser_wallpaper.jpg。
 * 浏览器首页([com.apk.claw.android.ui.compose.screen.DiscoverScreen])与全屏浏览器首页背景读取。
 */
object BrowserWallpaperStore {
    private const val FILE_NAME = "browser_wallpaper.jpg"
    private const val TARGET_MAX_PX = 2048

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun has(context: Context): Boolean = file(context).exists()

    /** 把选中图片复制进 filesDir 作壁纸。成功返回 true。 */
    fun set(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            file(context).outputStream().use { output -> input.copyTo(output) }
        } != null
    }.getOrDefault(false)

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    /** 解码壁纸位图(按屏幕量级下采样,防超大图 OOM);无壁纸返回 null。 */
    fun loadBitmap(context: Context): Bitmap? = runCatching {
        val f = file(context)
        if (f.exists()) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, bounds)
            val sample = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / TARGET_MAX_PX)
            BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        } else {
            null
        }
    }.getOrNull()
}
