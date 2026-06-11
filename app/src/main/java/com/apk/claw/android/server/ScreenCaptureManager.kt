package com.apk.claw.android.server

import android.graphics.Bitmap
import android.graphics.Rect
import com.apk.claw.android.service.ClawAccessibilityService
import com.apk.claw.android.utils.XLog
import java.io.ByteArrayOutputStream

/**
 * 截图管理器 —— 通过 ClawAccessibilityService 截取屏幕并编码为 JPEG。
 *
 * 特性：
 *  - 100ms 节流（防止高频截图导致 OOM）
 *  - ByteArrayOutputStream 缓冲区复用（减少 GC）
 *  - 支持缩放以减小传输体积
 */
class ScreenCaptureManager {

    companion object {
        private const val TAG = "ScreenCaptureManager"
        private const val THROTTLE_MS = 100L
    }

    @Volatile
    private var lastCaptureTs: Long = 0L

    /** 复用缓冲区 */
    private val buffer = ByteArrayOutputStream(256 * 1024)

    /**
     * 截取屏幕并返回 JPEG 字节数组。
     *
     * @param quality JPEG 压缩质量 (0-100)
     * @return JPEG 字节数组，失败返回 null
     */
    fun captureJpeg(quality: Int = 60): ByteArray? {
        val now = System.currentTimeMillis()
        if (now - lastCaptureTs < THROTTLE_MS) {
            return null
        }

        val service = ClawAccessibilityService.getInstance() ?: run {
            XLog.w(TAG, "AccessibilityService not running")
            return null
        }

        val bmp = service.takeScreenshot(3000) ?: run {
            XLog.w(TAG, "takeScreenshot returned null")
            return null
        }

        lastCaptureTs = System.currentTimeMillis()

        return try {
            buffer.reset()
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, buffer)
            buffer.toByteArray()
        } catch (e: Exception) {
            XLog.e(TAG, "JPEG compress failed: ${e.message}")
            null
        } finally {
            bmp.recycle()
        }
    }

    /**
     * 截取屏幕并缩放后返回 JPEG 字节数组。
     *
     * @param maxWidth 最大宽度（等比缩放）
     * @param quality JPEG 压缩质量 (0-100)
     * @return JPEG 字节数组，失败返回 null
     */
    fun captureScaledJpeg(maxWidth: Int = 720, quality: Int = 50): ByteArray? {
        val now = System.currentTimeMillis()
        if (now - lastCaptureTs < THROTTLE_MS) {
            return null
        }

        val service = ClawAccessibilityService.getInstance() ?: run {
            XLog.w(TAG, "AccessibilityService not running")
            return null
        }

        val bmp = service.takeScreenshot(3000) ?: run {
            XLog.w(TAG, "takeScreenshot returned null")
            return null
        }

        lastCaptureTs = System.currentTimeMillis()

        return try {
            val scaled = if (bmp.width > maxWidth) {
                val ratio = maxWidth.toFloat() / bmp.width
                val newHeight = (bmp.height * ratio).toInt()
                Bitmap.createScaledBitmap(bmp, maxWidth, newHeight, true)
            } else {
                bmp
            }

            buffer.reset()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, buffer)
            val bytes = buffer.toByteArray()

            if (scaled !== bmp) scaled.recycle()
            bytes
        } catch (e: Exception) {
            XLog.e(TAG, "Scaled JPEG compress failed: ${e.message}")
            null
        } finally {
            bmp.recycle()
        }
    }

    /**
     * 获取屏幕分辨率信息。
     */
    fun getScreenInfo(): Map<String, Any>? {
        val service = ClawAccessibilityService.getInstance() ?: return null
        return try {
            val root = service.getRootInActiveWindow() ?: return null
            val rect = Rect()
            root.getBoundsInScreen(rect)
            mapOf(
                "width" to rect.width(),
                "height" to rect.height(),
                "serviceRunning" to true
            )
        } catch (e: Exception) {
            XLog.e(TAG, "getScreenInfo failed: ${e.message}")
            null
        }
    }
}
