package com.apk.claw.android.server

import android.graphics.Rect
import com.apk.claw.android.capture.BitmapJpeg
import com.apk.claw.android.capture.ScreenCaptureService
import com.apk.claw.android.service.ClawAccessibilityService
import com.apk.claw.android.utils.XLog

/**
 * 截图管理器 —— 统一的屏幕取帧入口,两条后端:
 *  1. **投屏快路**:[ScreenCaptureService] 在跑时,走 MediaProjection([com.apk.claw.android.capture.ScreenProjectionSource]),
 *     帧随取随有,MJPEG 可到 20-30fps。
 *  2. **无障碍回退**:没开投屏授权时,退回 [ClawAccessibilityService.takeScreenshot](200-500ms/帧,2-5fps)。
 *
 * 上层(MJPEG 流 / 单帧截图)无需感知走哪条,开/关投屏授权即无感切换。
 *
 * 特性:
 *  - 33ms 节流(≈30fps 上限),防止高频取帧压垮 CPU/内存
 *  - 支持缩放以减小传输体积
 */
class ScreenCaptureManager {

    companion object {
        private const val TAG = "ScreenCaptureManager"
        // 节流 33ms ≈ 30fps 上限,匹配客户端请求 fps=20~30 时的实际表现
        // 旧值 100ms 限制了 MJPEG 流到 10fps,结合 AccessibilityService.takeScreenshot 200-500ms 延迟,实际只有 2-5fps
        private const val THROTTLE_MS = 33L
        private const val SHOT_TIMEOUT_MS = 3000L
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
        if (throttled()) return null

        // 投屏快路
        ScreenCaptureService.source?.captureJpeg(quality)?.let {
            lastCaptureTs = System.currentTimeMillis()
            return it
        }

        // 回退:无障碍截图
        val bmp = accessibilityShot() ?: return null
        lastCaptureTs = System.currentTimeMillis()
        return try {
            BitmapJpeg.compress(bmp, quality)
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
        if (throttled()) return null

        // 投屏快路
        ScreenCaptureService.source?.captureScaledJpeg(maxWidth, quality)?.let {
            lastCaptureTs = System.currentTimeMillis()
            return it
        }

        // 回退:无障碍截图
        val bmp = accessibilityShot() ?: return null
        lastCaptureTs = System.currentTimeMillis()
        return try {
            BitmapJpeg.scaleAndCompress(bmp, maxWidth, quality)
        } catch (e: Exception) {
            XLog.e(TAG, "Scaled JPEG compress failed: ${e.message}")
            null
        } finally {
            bmp.recycle()
        }
    }

    private fun throttled(): Boolean =
        System.currentTimeMillis() - lastCaptureTs < THROTTLE_MS

    private fun accessibilityShot(): android.graphics.Bitmap? {
        val service = ClawAccessibilityService.getInstance() ?: run {
            XLog.w(TAG, "AccessibilityService not running")
            return null
        }
        return service.takeScreenshot(SHOT_TIMEOUT_MS) ?: run {
            XLog.w(TAG, "takeScreenshot returned null")
            null
        }
    }

    /**
     * 获取屏幕分辨率信息。投屏激活时用采集分辨率,否则读无障碍窗口边界。
     */
    fun getScreenInfo(): Map<String, Any>? {
        ScreenCaptureService.source?.let {
            val (w, h) = it.screenSize()
            return mapOf("width" to w, "height" to h, "serviceRunning" to true)
        }
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
