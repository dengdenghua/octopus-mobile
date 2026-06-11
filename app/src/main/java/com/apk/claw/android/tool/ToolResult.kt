package com.apk.claw.android.tool

class ToolResult private constructor(
    val isSuccess: Boolean,
    val data: String?,
    val error: String?,
    /** 截图的 base64 编码数据（JPEG格式），用于 VLM 视觉理解 */
    val imageBase64: String?
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
    }

    override fun toString(): String = if (isSuccess) {
        if (imageBase64 != null) {
            "ToolResult{success=true, data='$data', imageBase64='${imageBase64.take(30)}...'}"
        } else {
            "ToolResult{success=true, data='$data'}"
        }
    } else {
        "ToolResult{success=false, error='$error'}"
    }
}
