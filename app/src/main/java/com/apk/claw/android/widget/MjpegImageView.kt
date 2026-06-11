package com.apk.claw.android.widget

import android.content.Context
import android.graphics.BitmapFactory
import android.util.AttributeSet
import android.util.Log
import androidx.appcompat.widget.AppCompatImageView
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.util.concurrent.TimeUnit

/**
 * MJPEG 流预览组件
 *
 * 连接 `/api/screen/stream` 端点，解析 multipart boundary 帧，
 * 实时渲染 JPEG 画面。
 */
class MjpegImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "MjpegImageView"
        private const val DEFAULT_BOUNDARY = "octopus_mjpeg_boundary"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MINUTES) // 流式读取无超时
        .build()

    private var streamJob: Job? = null

    /**
     * 开始播放 MJPEG 流
     *
     * @param url MJPEG 流地址，如 `http://192.168.1.100:8080/api/screen/stream?quality=40&maxWidth=480&fps=5`
     */
    fun start(url: String) {
        stop()
        streamJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val request = Request.Builder().url(url).build()
                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    Log.e(TAG, "MJPEG stream failed: HTTP ${response.code}")
                    return@launch
                }

                val body = response.body ?: return@launch
                val inputStream = BufferedInputStream(body.byteStream())

                // 从 Content-Type 提取 boundary
                val contentType = response.header("Content-Type") ?: ""
                val boundary = extractBoundary(contentType) ?: DEFAULT_BOUNDARY
                val boundaryBytes = "--$boundary".toByteArray()

                Log.i(TAG, "MJPEG stream started, boundary=$boundary")

                val buffer = ByteArray(65536)
                var frameBuffer = ByteArray(0)

                while (isActive) {
                    // 读取到 boundary
                    val headerLine = readLine(inputStream)
                    if (headerLine == null) {
                        Log.d(TAG, "Stream ended")
                        break
                    }

                    // 跳过边界行
                    if (headerLine.startsWith("--") && headerLine.contains(boundary)) {
                        // 读取 headers 直到空行
                        var contentLength = -1
                        while (true) {
                            val header = readLine(inputStream) ?: break
                            if (header.isEmpty()) break
                            if (header.startsWith("Content-Length:", ignoreCase = true)) {
                                contentLength = header.substringAfter(":").trim().toIntOrNull() ?: -1
                            }
                        }

                        // 读取 JPEG 数据
                        val jpegBytes = if (contentLength > 0) {
                            readExact(inputStream, contentLength)
                        } else {
                            // 无 Content-Length，读到下一个 boundary
                            readUntilBoundary(inputStream, boundaryBytes)
                        }

                        if (jpegBytes != null && jpegBytes.isNotEmpty()) {
                            val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                            if (bitmap != null) {
                                withContext(Dispatchers.Main) {
                                    setImageBitmap(bitmap)
                                }
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                // 正常取消
            } catch (e: Exception) {
                Log.e(TAG, "MJPEG stream error", e)
            }
        }
    }

    /**
     * 停止播放
     */
    fun stop() {
        streamJob?.cancel()
        streamJob = null
    }

    /**
     * 是否正在播放
     */
    fun isPlaying(): Boolean = streamJob?.isActive == true

    // ── 内部工具 ─────────────────────────────────────

    private fun extractBoundary(contentType: String): String? {
        val regex = Regex("boundary=(.+)")
        return regex.find(contentType)?.groupValues?.get(1)?.trim()
    }

    private fun readLine(stream: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = stream.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\r'.toInt()) {
                val next = stream.read()
                if (next == '\n'.toInt()) return sb.toString()
                sb.append(b.toChar())
                if (next != -1) sb.append(next.toChar())
            } else if (b == '\n'.toInt()) {
                return sb.toString()
            } else {
                sb.append(b.toChar())
            }
        }
    }

    private fun readExact(stream: BufferedInputStream, length: Int): ByteArray? {
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = stream.read(bytes, offset, length - offset)
            if (read == -1) return null
            offset += read
        }
        // 消耗尾部 \r\n
        stream.mark(2)
        val cr = stream.read()
        if (cr != '\r'.toInt()) {
            stream.reset()
        } else {
            val lf = stream.read()
            if (lf != '\n'.toInt()) {
                stream.reset()
            }
        }
        return bytes
    }

    private fun readUntilBoundary(stream: BufferedInputStream, boundary: ByteArray): ByteArray? {
        val result = java.io.ByteArrayOutputStream()
        var matchIndex = 0
        while (true) {
            val b = stream.read()
            if (b == -1) return result.toByteArray()
            if (b == boundary[matchIndex].toInt() and 0xFF) {
                matchIndex++
                if (matchIndex == boundary.size) {
                    return result.toByteArray()
                }
            } else {
                if (matchIndex > 0) {
                    result.write(boundary, 0, matchIndex)
                    matchIndex = 0
                }
                result.write(b)
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stop()
    }
}
