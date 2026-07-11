package com.apk.claw.android.capture

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import com.apk.claw.android.utils.XLog

/**
 * MediaProjection 屏幕采集引擎 —— 把 [MediaProjection] 镜像到一个 [VirtualDisplay],
 * 输出到 [ImageReader],后台线程持续把最近一帧转成 [Bitmap] 缓存起来。
 *
 * 对比无障碍 `takeScreenshot`(200-500ms/帧、2-5fps),这里帧是**推**过来的、随取随有,
 * MJPEG 拉流可以真正跑到 20-30fps。取帧时锁内仅交换 bitmap 引用,锁外压 JPEG,避免阻塞帧推送。
 *
 * 生命周期由 [ScreenCaptureService] 持有;用户/系统撤销投屏时 [MediaProjection.Callback.onStop]
 * 会回调,这里自动 [release]。
 */
class ScreenProjectionSource(
    private val projection: MediaProjection,
    private val width: Int,
    private val height: Int,
    private val densityDpi: Int,
    private val onStopped: () -> Unit,
) {
    private val thread = HandlerThread("ScreenProjection").apply { start() }
    private val handler = Handler(thread.looper)

    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null

    private val lock = Any()
    private var latest: Bitmap? = null

    @Volatile
    private var released = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            XLog.i(TAG, "MediaProjection 被系统/用户停止")
            release()
            onStopped()
        }
    }

    fun start() {
        // API 34+ 强制:先注册 callback 再建 VirtualDisplay,否则 createVirtualDisplay 抛异常。
        projection.registerCallback(projectionCallback, handler)

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, MAX_IMAGES)
        reader.setOnImageAvailableListener({ r -> onFrame(r) }, handler)
        imageReader = reader

        virtualDisplay = projection.createVirtualDisplay(
            "OctopusCapture",
            width, height, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, handler,
        )
        XLog.i(TAG, "投屏采集已启动 ${width}x$height @${densityDpi}dpi")
    }

    private fun onFrame(reader: ImageReader) {
        val image = try {
            reader.acquireLatestImage()
        } catch (e: IllegalStateException) {
            XLog.w(TAG, "acquireLatestImage 失败: ${e.message}")
            null
        } ?: return
        try {
            val frame = runCatching { imageToBitmap(image) }.getOrElse {
                XLog.w(TAG, "帧转换失败: ${it.message}")
                null
            } ?: return
            synchronized(lock) {
                if (released) {
                    frame.recycle()
                } else {
                    latest?.recycle()
                    latest = frame
                }
            }
        } finally {
            image.close()
        }
    }

    /** 把一帧 [Image] 拷成裁掉 rowStride 右侧 padding 的 [Bitmap]。 */
    private fun imageToBitmap(image: android.media.Image): Bitmap {
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        // rowStride 可能带右侧 padding,先按含 padding 的宽建位图,再裁到真实宽度。
        val rowPadding = rowStride - pixelStride * width
        val paddedWidth = width + rowPadding / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(plane.buffer)
        return if (paddedWidth != width) {
            Bitmap.createBitmap(padded, 0, 0, width, height).also { padded.recycle() }
        } else {
            padded
        }
    }

    /** 最近一帧压 JPEG(原尺寸)。锁内仅取引用,锁外压缩,避免阻塞 onFrame;暂无帧返回 null。 */
    fun captureJpeg(quality: Int): ByteArray? {
        val bmp = synchronized(lock) { latest } ?: return null
        return runCatching { BitmapJpeg.compress(bmp, quality) }.getOrNull()
    }

    /** 最近一帧等比缩放到 [maxWidth] 后压 JPEG。锁内仅取引用,锁外压缩;暂无帧返回 null。 */
    fun captureScaledJpeg(maxWidth: Int, quality: Int): ByteArray? {
        val bmp = synchronized(lock) { latest } ?: return null
        return runCatching { BitmapJpeg.scaleAndCompress(bmp, maxWidth, quality) }.getOrNull()
    }

    /** 当前采集分辨率(真实像素)。 */
    fun screenSize(): Pair<Int, Int> = width to height

    fun release() {
        synchronized(lock) {
            if (released) return
            released = true
            latest?.recycle()
            latest = null
        }
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        runCatching { projection.unregisterCallback(projectionCallback) }
        runCatching { projection.stop() }
        thread.quitSafely()
        XLog.i(TAG, "投屏采集已释放")
    }

    companion object {
        private const val TAG = "ScreenProjectionSource"
        private const val MAX_IMAGES = 2
    }
}
