package com.apk.claw.android.tool.impl

import com.apk.claw.android.media.MediaRepository
import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult
import kotlinx.coroutines.runBlocking

/**
 * 生图工具(Agnes 增值)。agent 在对话里按需调用:用户说"画/生成一张…"→出图。
 * 走服务端中转端点([MediaRepository]),会员免费 / 非会员扣积分由服务端处理。
 */
class GenerateImageTool : BaseTool() {

    override fun getName(): String = "generate_image"

    override fun getDisplayName(): String = if (useChineseDescription) "生成图片" else "Generate Image"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter(
            "prompt",
            "string",
            "Text description of the image to generate (Chinese or English). Be specific about subject, style and lighting.",
            true,
        ),
        ToolParameter(
            "size",
            "string",
            "Optional image size, e.g. '1024x1024' (default), '1280x720', '720x1280'.",
            false,
        ),
    )

    // 生成会扣积分、产出新内容,非幂等:失败不自动重试(避免重复计费)。
    override fun isIdempotent(): Boolean = false

    override fun execute(params: Map<String, Any>): ToolResult {
        val prompt = params["prompt"]?.toString()?.trim().orEmpty()
        if (prompt.isEmpty()) return ToolResult.error("缺少 prompt(图片描述)")
        val sizeRaw = params["size"]?.toString()?.trim()
        val size = if (sizeRaw.isNullOrEmpty()) "1024x1024" else sizeRaw
        return runBlocking {
            MediaRepository.generateImage(prompt, size).fold(
                onSuccess = { img ->
                    ToolResult.success("已生成图片。\n![image](${img.url})\n图片链接:${img.url}")
                },
                onFailure = { e -> ToolResult.error(e.message ?: "生图失败") },
            )
        }
    }
}
