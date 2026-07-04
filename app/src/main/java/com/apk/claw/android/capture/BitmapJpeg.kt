package com.apk.claw.android.capture

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

/**
 * Bitmap → JPEG 的公共编码逻辑,给两条采集路径共用:
 *  - 无障碍 [android.accessibilityservice.AccessibilityService.takeScreenshot]
 *  - MediaProjection 投屏采集([ScreenProjectionSource])
 *
 * 抽出来避免缩放/压缩两处各写一遍(原本只在 ScreenCaptureManager 里)。
 */
internal object BitmapJpeg {

    private const val INIT_BUFFER = 256 * 1024

    /** 原尺寸压 JPEG。不回收入参 [bmp](所有权归调用方)。 */
    fun compress(bmp: Bitmap, quality: Int): ByteArray {
        val buffer = ByteArrayOutputStream(INIT_BUFFER)
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, buffer)
        return buffer.toByteArray()
    }

    /**
     * 等比缩放到不超过 [maxWidth] 后压 JPEG。不回收入参 [bmp]。
     * 宽度已不超过 maxWidth 时不缩放,直接压。
     */
    fun scaleAndCompress(bmp: Bitmap, maxWidth: Int, quality: Int): ByteArray {
        val scaled = if (bmp.width > maxWidth) {
            val ratio = maxWidth.toFloat() / bmp.width
            val newHeight = (bmp.height * ratio).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bmp, maxWidth, newHeight, true)
        } else {
            bmp
        }
        return try {
            compress(scaled, quality)
        } finally {
            if (scaled !== bmp) scaled.recycle()
        }
    }
}
