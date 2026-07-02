package com.apk.claw.android.tool

class ToolResult private constructor(
    val isSuccess: Boolean,
    val data: String?,
    val error: String?,
    /** 截图的 base64 编码数据（JPEG格式），用于 VLM 视觉理解 */
    val imageBase64: String?,
    /** HTML 预览内容，直接推送到 Web 控制台用 <iframe> 渲染 */
    val htmlContent: String? = null,
    /**
     * 机器可读的错误分类（见 [ToolErr]）。给 Agent 自动修复循环一个稳定的判据:
     * 是参数错了(改参数重试)、目标不存在(先探测)、超时(缩小任务/加时限)还是权限不足(放弃/提示授权)。
     * null 表示未分类——旧的纯文本 error 依旧照常返回，向后兼容。
     */
    val errorCode: String? = null,
    /** 出错源码行号（run_code / 脚本类工具可用），无则 null。 */
    val errorLine: Int? = null,
) {
    companion object {
        @JvmStatic
        fun success(data: String): ToolResult = ToolResult(true, data, null, null)

        @JvmStatic
        fun error(error: String): ToolResult = ToolResult(false, null, error, null)

        /**
         * 带机器可读分类的错误。[code] 取 [ToolErr] 常量，[line] 为源码行号（脚本类工具）。
         * 文本 [error] 依旧是给人看的主消息，[code]/[line] 是给 Agent 决策的附加信号。
         */
        @JvmStatic
        @JvmOverloads
        fun error(error: String, code: String, line: Int? = null): ToolResult =
            ToolResult(false, null, error, null, null, code, line)

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
        isSuccess -> "ToolResult{success=true, data='$data'}"
        else -> "ToolResult{success=false, error='$error'${errorCode?.let { ", code=$it" } ?: ""}}"
    }
}

/**
 * 工具错误的机器可读分类。刻意小而稳:Agent（及自动修复循环）据此决定「怎么重试」，
 * 而不必去正则解析人类可读的 error 文本。工具按需传，不传即 null（旧行为不变）。
 */
object ToolErr {
    /** 参数非法/缺失/越界 —— 改参数再试，别重复原样调用。 */
    const val INVALID_PARAM = "INVALID_PARAM"
    /** 目标（节点/文件/元素/应用）不存在 —— 先探测当前状态再决定。 */
    const val NOT_FOUND = "NOT_FOUND"
    /** 权限/授权不足（无障碍/Shizuku/运行时权限）—— 放弃或提示用户授权。 */
    const val PERMISSION = "PERMISSION"
    /** 超时 —— 缩小任务或提高时限，不要原样重试。 */
    const val TIMEOUT = "TIMEOUT"
    /** 被安全策略拦截（SSRF/路径越界/来源闸门）—— 换合法目标，别绕。 */
    const val BLOCKED = "BLOCKED"
    /** 用户生成代码本身出错（语法/运行时异常），通常配 errorLine —— 改代码再跑。 */
    const val SCRIPT_ERROR = "SCRIPT_ERROR"
    /** 上游/网络失败（LLM 网关、HTTP 请求）—— 可重试，但先看是不是配额/网络。 */
    const val UPSTREAM = "UPSTREAM"
    /** 工具内部意外异常 —— 通常非调用方能修，记录即可。 */
    const val INTERNAL = "INTERNAL"
}
