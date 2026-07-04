package com.apk.claw.android.channel.wechat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WeChatMarkdown] 纯函数测试 —— Markdown → 纯文本转换。
 *
 * 严格对应官方 @tencent-weixin/openclaw-weixin@1.0.2 的 markdownToPlainText()。
 * 转换顺序:代码块 → 图片 → 链接 → 表格分隔行 → 表格行 → 内联 stripMarkdown。
 */
class WeChatMarkdownTest {

    // ── 代码块 ──

    @Test
    fun `code block without language strips fences and keeps content`() {
        val input = "```\ncode line\n```"
        assertEquals("code line", WeChatMarkdown.markdownToPlainText(input))
    }

    @Test
    fun `code block with language strips fence and language tag`() {
        val input = "```kotlin\nval x = 1\n```"
        assertEquals("val x = 1", WeChatMarkdown.markdownToPlainText(input))
    }

    @Test
    fun `code block preserves inner newlines and trims edges`() {
        val input = "```\nline1\nline2\nline3\n```"
        assertEquals("line1\nline2\nline3", WeChatMarkdown.markdownToPlainText(input))
    }

    @Test
    fun `code block content is not further stripped of markdown`() {
        // 代码块内的 **bold** 不应被 stripMarkdown 处理(因为代码块先被剥离围栏,
        // 内容作为纯文本保留 —— 但后续 stripMarkdown 仍会处理 *text*)
        val input = "```\n**not bold**\n```"
        // 代码块剥离后为 "**not bold**",再走 stripMarkdown 的 bold 正则 → "not bold"
        assertEquals("not bold", WeChatMarkdown.markdownToPlainText(input))
    }

    // ── 图片 ──

    @Test
    fun `image is removed entirely`() {
        assertEquals("", WeChatMarkdown.markdownToPlainText("![alt text](https://example.com/img.png)"))
    }

    @Test
    fun `image with empty alt is removed`() {
        assertEquals("", WeChatMarkdown.markdownToPlainText("![](url)"))
    }

    @Test
    fun `image surrounded by text keeps surrounding text`() {
        // 图片被移除后,前后空格保留(image replace 为空字符串,不吞空白)
        assertEquals("before  after", WeChatMarkdown.markdownToPlainText("before ![alt](url) after"))
    }

    // ── 链接 ──

    @Test
    fun `link keeps display text only`() {
        assertEquals("click here", WeChatMarkdown.markdownToPlainText("[click here](https://example.com)"))
    }

    @Test
    fun `link with empty display text is not matched by regex`() {
        // 链接正则 \[([^\]]+)] 要求 display text 至少 1 字符;空文本不被处理,原样保留
        assertEquals("[](https://example.com)", WeChatMarkdown.markdownToPlainText("[](https://example.com)"))
    }

    @Test
    fun `multiple links each keep their text`() {
        assertEquals("a and b", WeChatMarkdown.markdownToPlainText("[a](u1) and [b](u2)"))
    }

    // ── 表格 ──

    @Test
    fun `table separator row is removed`() {
        val input = "| Header |\n| --- |\n| cell |"
        val result = WeChatMarkdown.markdownToPlainText(input)
        // 分隔行 | --- | 被移除,其余行去管道
        assertTrue("分隔行应被移除", !result.contains("---"))
        assertTrue("表头保留", result.contains("Header"))
        assertTrue("单元格保留", result.contains("cell"))
    }

    @Test
    fun `table row strips pipes and joins with spaces`() {
        val input = "| a | b | c |"
        assertEquals("a  b  c", WeChatMarkdown.markdownToPlainText(input))
    }

    // ── 内联:粗体 / 斜体 / 删除线 / 行内代码 ──

    @Test
    fun `bold asterisks are stripped`() {
        assertEquals("bold", WeChatMarkdown.markdownToPlainText("**bold**"))
    }

    @Test
    fun `bold underscores are stripped`() {
        assertEquals("bold", WeChatMarkdown.markdownToPlainText("__bold__"))
    }

    @Test
    fun `italic asterisk is stripped`() {
        assertEquals("italic", WeChatMarkdown.markdownToPlainText("*italic*"))
    }

    @Test
    fun `strikethrough is stripped`() {
        assertEquals("struck", WeChatMarkdown.markdownToPlainText("~~struck~~"))
    }

    @Test
    fun `inline code backticks are stripped`() {
        assertEquals("code", WeChatMarkdown.markdownToPlainText("`code`"))
    }

    // ── 标题 / 引用 / 分割线 / 列表 ──

    @Test
    fun `h1 heading marker is stripped`() {
        assertEquals("Title", WeChatMarkdown.markdownToPlainText("# Title"))
    }

    @Test
    fun `h3 heading marker is stripped`() {
        assertEquals("Section", WeChatMarkdown.markdownToPlainText("### Section"))
    }

    @Test
    fun `blockquote marker is stripped`() {
        assertEquals("quote", WeChatMarkdown.markdownToPlainText("> quote"))
    }

    @Test
    fun `horizontal rule is removed`() {
        assertEquals("", WeChatMarkdown.markdownToPlainText("---"))
    }

    @Test
    fun `horizontal rule asterisks is consumed by italic regex first`() {
        // 已知行为:stripMarkdown 里 italic \*(.+?)\* 在 hr ^[-*_]{3,}$ 之前,
        // *** 被 italic 匹配(中间 * 作为内容)→ 单个 *,hr 正则 {3,} 不再匹配
        assertEquals("*", WeChatMarkdown.markdownToPlainText("***"))
    }

    @Test
    fun `unordered list dash marker is stripped`() {
        assertEquals("item", WeChatMarkdown.markdownToPlainText("- item"))
    }

    @Test
    fun `unordered list asterisk marker is stripped`() {
        assertEquals("item", WeChatMarkdown.markdownToPlainText("* item"))
    }

    @Test
    fun `ordered list marker is stripped`() {
        assertEquals("item", WeChatMarkdown.markdownToPlainText("1. item"))
    }

    @Test
    fun `ordered list with large number is stripped`() {
        assertEquals("item", WeChatMarkdown.markdownToPlainText("99. item"))
    }

    // ── 混合 / 多行 ──

    @Test
    fun `multiline with heading and paragraph`() {
        val input = "# Title\n\nThis is a paragraph with **bold** and *italic*."
        // heading marker 被 stripMarkdown 移除,bold/italic 标记被剥离
        assertEquals("Title\n\nThis is a paragraph with bold and italic.", WeChatMarkdown.markdownToPlainText(input))
    }

    @Test
    fun `multiline preserves newlines`() {
        val input = "line1\nline2\nline3"
        assertEquals("line1\nline2\nline3", WeChatMarkdown.markdownToPlainText(input))
    }

    @Test
    fun `mixed code block and paragraph`() {
        val input = "intro\n```\ncode\n```\noutro"
        assertEquals("intro\ncode\noutro", WeChatMarkdown.markdownToPlainText(input))
    }

    // ── 边界 / 健壮性 ──

    @Test
    fun `empty string returns empty`() {
        assertEquals("", WeChatMarkdown.markdownToPlainText(""))
    }

    @Test
    fun `plain text without markdown is unchanged`() {
        assertEquals("just plain text 123", WeChatMarkdown.markdownToPlainText("just plain text 123"))
    }

    @Test
    fun `text with special chars but no markdown is unchanged`() {
        assertEquals("price: $100 (50% off)", WeChatMarkdown.markdownToPlainText("price: $100 (50% off)"))
    }
}
