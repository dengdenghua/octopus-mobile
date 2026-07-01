package com.apk.claw.android.tool.impl

import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult

/**
 * HTML 预览工具 —— 把 HTML/CSS/JS 推送到 Web 控制台，用 <iframe> 直接渲染。
 *
 * 无需 Android 端截图：控制台本身是浏览器，沙箱 iframe 渲染效果更好、可交互。
 * ToolResult.htmlContent 由 AgentWebBridge 发送 "html" SSE 事件到控制台显示。
 */
class PreviewHtmlTool : BaseTool() {

    companion object {
        private const val MAX_HTML_LEN = 500_000
    }

    override fun getName() = "preview_html"
    override fun getDisplayName() = if (useChineseDescription) "HTML预览" else "HTML Preview"

    override fun getParameters() = listOf(
        ToolParameter("html", "string",
            "Complete HTML/CSS/JS to preview. Rendered directly in the web console as a sandboxed iframe. External CDN scripts work if the user's browser has internet.", true),
        ToolParameter("height", "integer",
            "Preview iframe height in pixels. Default 600.", false),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val html = requireString(params, "html")
        if (html.length > MAX_HTML_LEN) {
            return ToolResult.error("HTML 过大(${html.length} > $MAX_HTML_LEN 字符)")
        }
        val height = optionalInt(params, "height", 600).coerceIn(100, 4096)

        // 把高度信息注入到 html 里，console-app.js 据此设置 iframe 高度
        val payload = "$height\n$html"
        return ToolResult.successWithHtml("HTML 预览已发送到控制台（${html.length} 字符，高度 ${height}px）", payload)
    }

    override fun getDescriptionEN() = """
        Preview HTML/CSS/JavaScript directly in the web console as an interactive iframe.
        No screenshot needed — the console browser renders it live at full quality.
        External CDN scripts (ECharts, Chart.js, D3.js, etc.) work if the browser has internet.
        Use this after generating any visual code: charts, UI layouts, animations, games.
        The preview is interactive — the user can click and interact with it.
    """.trimIndent()

    override fun getDescriptionCN() = """
        把 HTML/CSS/JavaScript 直接推送到 Web 控制台，以沙箱 iframe 实时渲染预览。
        无需截图——控制台浏览器直接渲染，效果完整且可交互。
        外部 CDN（ECharts/Chart.js/D3.js 等）在用户浏览器有网时直接可用。
        适用：图表、UI 布局、动画、游戏、数据可视化——任何生成代码后想立即核对的场景。
        用户可以在预览中点击和交互，然后告诉你哪里需要修改。
    """.trimIndent()
}
