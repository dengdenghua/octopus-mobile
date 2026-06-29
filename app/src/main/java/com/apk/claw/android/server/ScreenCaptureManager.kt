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
 *  - 支持缩放以减小传输体积
 *
 * 注：每个调用使用独立的 ByteArrayOutputStream，避免多线程并发时数据错乱。
 */
class ScreenCaptureManager {

    companion object {
        private const val TAG = "ScreenCaptureManager"
        // 节流 33ms ≈ 30fps 上限,匹配客户端请求 fps=20~30 时的实际表现
        // 旧值 100ms 限制了 MJPEG 流到 10fps,结合 AccessibilityService.takeScreenshot 200-500ms 延迟,实际只有 2-5fps
        private const val THROTTLE_MS = 33L
    }

    @Volatile
    private var lastCaptureTs: Long = 0L

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
            val buffer = ByteArrayOutputStream(256 * 1024)
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

            val buffer = ByteArrayOutputStream(256 * 1024)
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
        val root = try {
            service.getRootInActiveWindow() ?: return null
        } catch (e: Exception) {
            XLog.e(TAG, "getScreenInfo failed: ${e.message}")
            return null
        }
        return try {
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
        } finally {
            root.recycle()
        }
    }
}
