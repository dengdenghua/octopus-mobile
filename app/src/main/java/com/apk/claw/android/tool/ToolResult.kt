package com.apk.claw.android.tool

class ToolResult private constructor(
    val isSuccess: Boolean,
    val data: String?,
    val error: String?,
    /** 截图的 base64 编码数据（JPEG格式），用于 VLM 视觉理解 */
    val imageBase64: String?,
    /** HTML 预览内容，直接推送到 Web 控制台用 <iframe> 渲染 */
    val htmlContent: String? = null,
) {
    companion object {
        @JvmStatic
        fun success(data: String): ToolResult = ToolResult(true, data, null, null)

        @JvmStatic
        fun error(error: String): ToolResult = ToolResult(false, null, error, null)

        /** 返回成功结果，同时携带截图的 base64 编码数据 */
        @JvmStatic
        fun successWithImage(data: String, imageBase64: String): ToolResult =
            ToolResult(true, data, null, imageBase64)

        /** 返回成功结果，同时携带 HTML 内容供控制台直接渲染 */
        @JvmStatic
        fun successWithHtml(data: String, htmlContent: String): ToolResult =
            ToolResult(true, data, null, null, htmlContent)
    }

    override fun toString(): String = when {
        imageBase64 != null -> "ToolResult{success=$isSuccess, data='$data', imageBase64='${imageBase64.take(30)}...'}"
        htmlContent != null -> "ToolResult{success=$isSuccess, data='$data', htmlContent=${htmlContent.length}chars}"
        else -> if (isSuccess) "ToolResult{success=true, data='$data'}" else "ToolResult{success=false, error='$error'}"
    }
}
