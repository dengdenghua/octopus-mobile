package com.apk.claw.android.octopus_mobile

import android.graphics.Bitmap
import android.util.Base64
import com.apk.claw.android.utils.KVUtils
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream

/**
 * 视觉分析器 —— 把一张屏幕截图喂给视觉模型，返回文字分析.
 *
 * 这是「视觉感知」切片的核心：主 Agent 跑纯文本模型(deepseek-chat)省钱，
 * 只在无障碍树抓不到时(游戏/canvas/图像核对)由 look_at_screen 工具按需调用本类。
 *
 * 模型配置独立于主对话模型（见 [KVUtils] 视觉配置）：
 *  - 视觉三项填了 → 用视觉模型(如 qwen-vl-max / gpt-4o / glm-4v)
 *  - 视觉三项留空 → 回退复用主模型配置（需主模型支持图片输入，否则会报错）
 *
 * 复用 [LightweightLlmClient] 现成的 OpenAI 多模态请求能力。
 */
object VisionAnalyzer {

    /** base64 图片最大宽度，超过等比缩放（与 DefaultAgentService 一致）。 */
    private const val MAX_WIDTH = 720
    /** JPEG 压缩质量。 */
    private const val JPEG_QUALITY = 50

    /**
     * VLM 结果短 TTL 缓存:同一屏幕 + 同一问题在短时间(10s)内直接返回上次结果,
     * 避免连续 look_at_screen 调用重复花 VLM token.
     * key = (screenHash, question),value = 分析结果.
     */
    private data class CacheEntry(val screenHash: Int, val question: String, val result: String, val ts: Long)
    @Volatile
    private var cache: CacheEntry? = null
    private const val CACHE_TTL_MS = 10_000L

    /**
     * 快速计算 bitmap 的降采样 hash:缩到 16x16 算 pixel 求和.
     * 用于判断屏幕是否变化,不追求精确(10s 内同一问题复用,变化检测只需粗粒度).
     */
    private fun bitmapHash(bitmap: Bitmap): Int {
        val scaled = try {
            Bitmap.createScaledBitmap(bitmap, 16, 16, true)
        } catch (e: Exception) {
            return bitmap.width * 31 + bitmap.height
        }
        return try {
            var hash = 0
            for (y in 0 until 16) {
                for (x in 0 until 16) {
                    hash = hash * 31 + scaled.getPixel(x, y)
                }
            }
            hash
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    /** 是否已配置可用的视觉能力（视觉 key 或主 key 任一非空）。 */
    fun isConfigured(): Boolean =
        KVUtils.getVisionApiKey().isNotBlank() || KVUtils.getLlmApiKey().isNotBlank()

    /**
     * 分析一张截图并返回文字结果（阻塞，供同步工具调用）。
     *
     * @param bitmap   原始截图（坐标系即真实屏幕分辨率）
     * @param question 要问视觉模型的问题
     * @return 视觉模型的文字回答
     * @throws LlmException / IllegalStateException 调用失败时抛出
     */
    fun analyzeBlocking(bitmap: Bitmap, question: String): String = runBlocking {
        analyze(bitmap, question)
    }

    suspend fun analyze(bitmap: Bitmap, question: String): String {
        // 缓存命中:同一屏幕 hash + 同一问题,10s 内直接返回上次结果
        val hash = bitmapHash(bitmap)
        val now = System.currentTimeMillis()
        cache?.let { e ->
            if (e.screenHash == hash && e.question == question && now - e.ts < CACHE_TTL_MS) {
                com.apk.claw.android.agent.AgentMetrics.vlmCacheHit()
                return e.result
            }
        }

        val origW = bitmap.width
        val origH = bitmap.height
        val base64 = encodeScaledJpeg(bitmap)

        val cfg = resolveConfig()
        val client = LightweightLlmClient(cfg)

        val system = ChatMessage.System(content =
            "你是手机界面视觉分析助手。根据用户给的屏幕截图回答问题，描述要简洁。" +
            "凡涉及元素位置，请按【原始屏幕分辨率 ${origW}x${origH}】给出大致像素坐标(x, y)，" +
            "坐标是该元素的中心点，供后续点击使用。"
        )
        val user = ChatMessage.User(
            content = question,
            imageBase64 = base64,
        )
        val resp = client.chat(listOf(system, user), emptyList())
        val result = resp.content?.trim().orEmpty().ifEmpty { "(视觉模型没有返回内容)" }

        // 写入缓存
        cache = CacheEntry(hash, question, result, now)
        return result
    }

    /** 解析视觉模型配置：视觉项优先，留空回退主模型。 */
    private fun resolveConfig(): LlmConfig {
        val apiKey = KVUtils.getVisionApiKey().ifBlank { KVUtils.getLlmApiKey() }.trim()
        check(apiKey.isNotEmpty()) { "未配置视觉模型，请到 设置 → 模型配置 填写视觉模型(或让主模型支持图片)" }

        val base = KVUtils.getVisionBaseUrl().ifBlank { KVUtils.getLlmBaseUrl() }
            .trim().ifEmpty { "https://api.deepseek.com/v1" }
        val model = KVUtils.getVisionModelName().ifBlank { KVUtils.getLlmModelName() }
            .trim().ifEmpty { "deepseek-chat" }

        // base 可能是 ".../v1" 也可能已是完整 ".../chat/completions"
        val apiUrl = if (base.endsWith("/chat/completions")) base
        else base.trimEnd('/') + "/chat/completions"

        return LlmConfig(
            apiUrl = apiUrl,
            apiKey = apiKey,
            model = model,
            temperature = 0.0,
            // 给足输出预算：推理型视觉模型(如小米 mimo-v2.5)会先写 reasoning_content 再写
            // content，预算太小会把额度全耗在思考上、content 返回空。2048 实测足以产出最终答案。
            maxTokens = 2048,
        )
    }

    private fun encodeScaledJpeg(bitmap: Bitmap): String {
        var scaled = bitmap
        if (bitmap.width > MAX_WIDTH) {
            val scale = MAX_WIDTH.toFloat() / bitmap.width
            val newH = Math.round(bitmap.height * scale)
            scaled = Bitmap.createScaledBitmap(bitmap, MAX_WIDTH, newH, true)
        }
        val baos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos)
        if (scaled !== bitmap) scaled.recycle()
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }
}
