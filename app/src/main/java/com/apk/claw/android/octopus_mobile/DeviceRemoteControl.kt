package com.apk.claw.android.octopus_mobile

import com.apk.claw.android.utils.XLog
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 远程控制其他 Octopus Mobile 设备。
 *
 * 通过 HTTP 请求目标设备的 ConfigServer API 实现：
 *  - 发送触控/按键/文本输入
 *  - 获取屏幕截图预览
 */
class DeviceRemoteControl {

    companion object {
        private const val TAG = "DeviceRemoteControl"
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    // ── 输入控制 ──

    /**
     * 在目标设备上执行 tap 操作。
     */
    suspend fun tap(device: DeviceInfo, x: Int, y: Int): Boolean {
        return sendInput(device, mapOf("action" to "tap", "x" to x, "y" to y))
    }

    /**
     * 在目标设备上执行 swipe 操作。
     */
    suspend fun swipe(
        device: DeviceInfo,
        x1: Int, y1: Int,
        x2: Int, y2: Int,
        durationMs: Long = 300
    ): Boolean {
        return sendInput(device, mapOf(
            "action" to "swipe",
            "x1" to x1, "y1" to y1,
            "x2" to x2, "y2" to y2,
            "duration" to durationMs
        ))
    }

    /**
     * 在目标设备上发送按键事件。
     */
    suspend fun sendKey(device: DeviceInfo, keyCode: Int): Boolean {
        return sendInput(device, mapOf("action" to "key", "keyCode" to keyCode))
    }

    /**
     * 在目标设备上输入文本。
     */
    suspend fun sendText(device: DeviceInfo, text: String): Boolean {
        return sendInput(device, mapOf("action" to "text", "text" to text))
    }

    /**
     * 在目标设备上按返回键。
     */
    suspend fun pressBack(device: DeviceInfo): Boolean {
        return sendInput(device, mapOf("action" to "back"))
    }

    /**
     * 在目标设备上按 Home 键。
     */
    suspend fun pressHome(device: DeviceInfo): Boolean {
        return sendInput(device, mapOf("action" to "home"))
    }

    // ── 屏幕截图 ──

    /**
     * 获取目标设备的屏幕截图（JPEG 字节数组）。
     *
     * @param device 目标设备
     * @param quality JPEG 质量 (0-100)
     * @param maxWidth 最大宽度（等比缩放）
     * @return JPEG 字节数组，失败返回 null
     */
    suspend fun captureScreenshot(
        device: DeviceInfo,
        quality: Int = 50,
        maxWidth: Int = 720
    ): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val url = "${device.getBaseUrl()}/api/screen/screenshot?quality=$quality&maxWidth=$maxWidth"
            val request = Request.Builder().url(url).get().build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                response.body?.bytes()
            } else {
                XLog.w(TAG, "Screenshot failed: ${response.code}")
                null
            }
        } catch (e: Exception) {
            XLog.e(TAG, "captureScreenshot failed: ${e.message}")
            null
        }
    }

    /**
     * 获取目标设备的屏幕信息（分辨率等）。
     */
    suspend fun getScreenInfo(device: DeviceInfo): Map<String, Any>? = withContext(Dispatchers.IO) {
        try {
            val url = "${device.getBaseUrl()}/api/screen/info"
            val request = Request.Builder().url(url).get().build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return@withContext null
                val map = gson.fromJson(body, Map::class.java) as? Map<*, *>
                map?.get("data") as? Map<String, Any>
            } else null
        } catch (e: Exception) {
            XLog.e(TAG, "getScreenInfo failed: ${e.message}")
            null
        }
    }

    // ── 内部方法 ──

    /**
     * 发送输入控制命令到目标设备。
     */
    private suspend fun sendInput(device: DeviceInfo, params: Map<String, Any>): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val url = "${device.getBaseUrl()}/api/control/input"
                val json = gson.toJson(params)
                val body = json.toRequestBody(JSON_TYPE)
                val request = Request.Builder().url(url).post(body).build()
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val responseBody = response.body?.string() ?: "{}"
                    val result = gson.fromJson(responseBody, Map::class.java)
                    val code = (result["code"] as? Double)?.toInt() ?: -1
                    code == 0
                } else {
                    XLog.w(TAG, "sendInput failed: ${response.code}")
                    false
                }
            } catch (e: Exception) {
                XLog.e(TAG, "sendInput to ${device.deviceId} failed: ${e.message}")
                false
            }
        }
}
