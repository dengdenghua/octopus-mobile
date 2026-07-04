package com.apk.claw.android.channel.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TelegramMarkdownUtils] 纯函数测试 —— 标准 Markdown → Telegram MarkdownV2 转换。
 *
 * 覆盖官方语法对照:
 *  - 粗体 `**text**` → `*text*`
 *  - 斜体 `*text*` → `_text_`
 *  - 删除线 `~~text~~` → `~text~`
 *  - 行内代码 `` `code` `` → 内部只转义 `\` 和 `` ` ``
 *  - 代码块 ``` → 内部只转义 `\` 和 `` ` ``
 *  - 链接 `[t](url)` → url 内转义 `)` 和 `\`
 *  - 标题 `# text` → `*text*`(TG 无标题,用粗体代替)
 *  - 普通文本保留字符 `_*[]()~`>#+\-=|{}.!` 必须转义
 */
class TelegramMarkdownUtilsTest {

    // ── containsMarkdown ──

    @Test
    fun `containsMarkdown detects bold`() =
        assertTrue(TelegramMarkdownUtils.containsMarkdown("**bold**"))

    @Test
    fun `containsMarkdown detects heading`() =
        assertTrue(TelegramMarkdownUtils.containsMarkdown("# heading"))

    @Test
    fun `containsMarkdown detects code block`() =
        assertTrue(TelegramMarkdownUtils.containsMarkdown("```\ncode\n```"))

    @Test
    fun `containsMarkdown detects link`() =
        assertTrue(TelegramMarkdownUtils.containsMarkdown("[text](https://example.com)"))

    @Test
    fun `containsMarkdown detects table row`() =
        assertTrue(TelegramMarkdownUtils.containsMarkdown("| a | b |"))

    @Test
    fun `containsMarkdown detects strikethrough`() =
        assertTrue(TelegramMarkdownUtils.containsMarkdown("~~struck~~"))

    @Test
    fun `containsMarkdown detects blockquote`() =
        assertTrue(TelegramMarkdownUtils.containsMarkdown("> quote"))

    @Test
    fun `containsMarkdown detects task list item`() {
        assertTrue(TelegramMarkdownUtils.containsMarkdown("- [x] done"))
        assertTrue(TelegramMarkdownUtils.containsMarkdown("- [ ] todo"))
    }

    @Test
    fun `containsMarkdown returns false for plain text`() =
        assertFalse(TelegramMarkdownUtils.containsMarkdown("just plain text 123"))

    // ── markdownToTelegramV2: 行内元素 ──

    @Test
    fun `bold converts to tg asterisk`() {
        assertEquals("*bold*", TelegramMarkdownUtils.markdownToTelegramV2("**bold**"))
    }

    @Test
    fun `italic converts to tg underscore`() {
        assertEquals("_italic_", TelegramMarkdownUtils.markdownToTelegramV2("*italic*"))
    }

    @Test
    fun `strikethrough converts to tg tilde`() {
        assertEquals("~struck~", TelegramMarkdownUtils.markdownToTelegramV2("~~struck~~"))
    }

    @Test
    fun `inline code preserves content and escapes backslash`() {
        // 内部只转义 \ 和 ` —— 普通字符不转义
        assertEquals("`code`", TelegramMarkdownUtils.markdownToTelegramV2("`code`"))
        // 反斜杠在代码内要转义
        assertEquals("`a\\\\b`", TelegramMarkdownUtils.markdownToTelegramV2("`a\\b`"))
    }

    @Test
    fun `link preserves text and url backslash is escaped`() {
        // url 内 \ 需转义;text 走 escapePlain
        assertEquals("[t](a\\\\b)", TelegramMarkdownUtils.markdownToTelegramV2("[t](a\\b)"))
        // url 内 ( 不需转义(正则 [^)]+ 可含 (;只有结尾 ) 是分隔符)
        assertEquals("[text](a(b)", TelegramMarkdownUtils.markdownToTelegramV2("[text](a(b)"))
    }

    // ── markdownToTelegramV2: 标题 ──

    @Test
    fun `h1 heading converts to bold`() {
        assertEquals("*Title*", TelegramMarkdownUtils.markdownToTelegramV2("# Title"))
    }

    @Test
    fun `h3 heading converts to bold`() {
        assertEquals("*Section*", TelegramMarkdownUtils.markdownToTelegramV2("### Section"))
    }

    @Test
    fun `h6 heading converts to bold`() {
        assertEquals("*Deep*", TelegramMarkdownUtils.markdownToTelegramV2("###### Deep"))
    }

    // ── markdownToTelegramV2: 代码块 ──

    @Test
    fun `code block preserves content escaping backslash and backtick`() {
        val input = "```\nline1\nline2\n```"
        assertEquals("```\nline1\nline2\n```", TelegramMarkdownUtils.markdownToTelegramV2(input))
    }

    @Test
    fun `code block escapes inner backslash`() {
        val input = "```\na\\b\n```"
        assertEquals("```\na\\\\b\n```", TelegramMarkdownUtils.markdownToTelegramV2(input))
    }

    @Test
    fun `code block escapes inner backtick`() {
        val input = "```\na`b\n```"
        assertEquals("```\na\\`b\n```", TelegramMarkdownUtils.markdownToTelegramV2(input))
    }

    @Test
    fun `code block does not escape markdown special chars`() {
        // 代码块内 * _ ~ 等不转义(只转义 \ 和 `)
        val input = "```\n*bold* _italic_ ~strike~\n```"
        assertEquals("```\n*bold* _italic_ ~strike~\n```", TelegramMarkdownUtils.markdownToTelegramV2(input))
    }

    // ── markdownToTelegramV2: 普通文本转义 ──

    @Test
    fun `plain text special chars are escaped`() {
        // 保留字符 _ * [ ] ( ) ~ ` > # + - = | { } . ! 必须转义
        val input = "a_b*c[d]e(f)g~h`i>j#k+l-m=n|o{p}q.r!s"
        val result = TelegramMarkdownUtils.markdownToTelegramV2(input)
        // 每个特殊字符前都应有 \
        assertTrue("下划线应转义", result.contains("\\_"))
        assertTrue("星号应转义", result.contains("\\*"))
        assertTrue("方括号应转义", result.contains("\\["))
        assertTrue("圆括号应转义", result.contains("\\("))
        assertTrue("波浪号应转义", result.contains("\\~"))
        assertTrue("反引号应转义", result.contains("\\`"))
        assertTrue("大于号应转义", result.contains("\\>"))
        assertTrue("井号应转义", result.contains("\\#"))
        assertTrue("加号应转义", result.contains("\\+"))
        assertTrue("等号应转义", result.contains("\\="))
        assertTrue("花括号应转义", result.contains("\\{"))
        assertTrue("点号应转义", result.contains("\\."))
        assertTrue("感叹号应转义", result.contains("\\!"))
    }

    @Test
    fun `backslash in plain text is escaped first`() {
        assertEquals("a\\\\b", TelegramMarkdownUtils.markdownToTelegramV2("a\\b"))
    }

    // ── markdownToTelegramV2: 混合 / 多行 ──

    @Test
    fun `mixed bold and plain text`() {
        val input = "This is **bold** and normal"
        val result = TelegramMarkdownUtils.markdownToTelegramV2(input)
        assertEquals("This is *bold* and normal", result)
    }

    @Test
    fun `multiline text preserves newlines`() {
        val input = "line1\nline2\nline3"
        assertEquals("line1\nline2\nline3", TelegramMarkdownUtils.markdownToTelegramV2(input))
    }

    @Test
    fun `multiline with code block and plain lines`() {
        val input = "intro\n```\ncode\n```\noutro"
        assertEquals("intro\n```\ncode\n```\noutro", TelegramMarkdownUtils.markdownToTelegramV2(input))
    }

    @Test
    fun `empty string returns empty`() {
        assertEquals("", TelegramMarkdownUtils.markdownToTelegramV2(""))
    }

    // ── escapePlain ──

    @Test
    fun `escapePlain escapes all reserved chars`() {
        val input = "_*[]()~`>#+-=|{}.!"
        val result = TelegramMarkdownUtils.escapePlain(input)
        // 每个特殊字符前都应有 \,输出长度 = 输入长度 + 转义符号数
        assertTrue("转义后应更长", result.length > input.length)
        // 抽样校验几个代表性字符已被转义
        assertTrue(result.contains("\\_"))
        assertTrue(result.contains("\\*"))
        assertTrue(result.contains("\\."))
        assertTrue(result.contains("\\!"))
        assertTrue(result.contains("\\["))
    }

    @Test
    fun `escapePlain escapes backslash first`() {
        assertEquals("\\\\", TelegramMarkdownUtils.escapePlain("\\"))
    }

    @Test
    fun `escapePlain preserves alphanumeric and spaces`() {
        assertEquals("abc 123 XYZ", TelegramMarkdownUtils.escapePlain("abc 123 XYZ"))
    }

    @Test
    fun `escapePlain on empty returns empty`() {
        assertEquals("", TelegramMarkdownUtils.escapePlain(""))
    }

    // ── 边界 / 健壮性 ──

    @Test
    fun `containsMarkdown on empty returns false`() {
        assertFalse(TelegramMarkdownUtils.containsMarkdown(""))
    }

    @Test
    fun `heading at start of line only`() {
        // 行首 # 才是标题;行中 # 不是
        assertTrue(TelegramMarkdownUtils.containsMarkdown("# heading"))
        assertFalse(TelegramMarkdownUtils.containsMarkdown("not a # heading"))
    }

    @Test
    fun `single backtick is not detected as code block fence`() {
        // containsMarkdown 只检测 ``` 三反引号代码块;单个 ` 行内代码不在检测清单内
        assertFalse(TelegramMarkdownUtils.containsMarkdown("`inline`"))
    }
}
