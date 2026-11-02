package com.xjw.qalbum.ui.components

import com.xjw.qalbum.AppInfo
import com.xjw.qalbum.ui.components.Markdown.Block
import com.xjw.qalbum.ui.components.Markdown.Span
import com.xjw.qalbum.ui.onboarding.USER_AGREEMENT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 更新说明与用户协议用的 Markdown：块级与行内格式，以及协议全文能正确解析 */
class MarkdownTest {
    @Test
    fun `块级元素：标题、段落、列表、引用、分隔线、代码块`() {
        val blocks = Markdown.parse(
            """
            ## 0.4.2 更新
            修复了长图放大发糊
            第二行照常换行

            - 长图自动放大到顶部
              - 子项缩进
            1. 第一步
            > 引用一句
            ---
            ```
            code line
            ```
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                Block.Heading(2, "0.4.2 更新"),
                Block.Paragraph("修复了长图放大发糊\n第二行照常换行"),
                Block.Item(0, "•", "长图自动放大到顶部"),
                Block.Item(1, "•", "子项缩进"),
                Block.Item(0, "1.", "第一步"),
                Block.Quote("引用一句"),
                Block.Rule,
                Block.Code("code line"),
            ),
            blocks,
        )
    }

    @Test
    fun `行内样式与链接，转义和单词里的下划线不算格式`() {
        assertEquals(
            listOf(
                Span.Text("看 "),
                Span.Text("粗体", bold = true),
                Span.Text("、"),
                Span.Text("斜体", italic = true),
                Span.Text("、"),
                Span.Text("代码", code = true),
                Span.Text("、"),
                Span.Text("删掉", strike = true),
                Span.Text(" 和 "),
                Span.Link("主页", "https://example.com"),
                Span.Text(" "),
                Span.Link("https://a.b/c", "https://a.b/c"),
            ),
            Markdown.inline("看 **粗体**、*斜体*、`代码`、~~删掉~~ 和 [主页](https://example.com) https://a.b/c"),
        )
        assertEquals(listOf(Span.Text("*不是斜体* snake_case_name")), Markdown.inline("\\*不是斜体\\* snake_case_name"))
        // 粗体里可以有斜体
        assertEquals(
            listOf(Span.Text("粗体里 ", bold = true), Span.Text("斜体", bold = true, italic = true), Span.Text(" 也行", bold = true)),
            Markdown.inline("**粗体里 *斜体* 也行**"),
        )
    }

    @Test
    fun `用户协议能完整解析：条款都编号，加粗都闭合，链接只有隐私政策和项目主页`() {
        val blocks = Markdown.parse(USER_AGREEMENT)
        assertTrue(blocks.any { it is Block.Heading })
        val items = blocks.filterIsInstance<Block.Item>()
        assertTrue(items.isNotEmpty() && items.all { it.depth == 0 && it.marker.endsWith(".") })
        val spans = blocks.flatMap { block ->
            when (block) {
                is Block.Item -> Markdown.inline(block.text)
                is Block.Paragraph -> Markdown.inline(block.text)
                else -> emptyList()
            }
        }
        assertTrue(spans.none { it is Span.Text && "**" in it.text })
        assertTrue(spans.any { it is Span.Text && it.bold })
        assertEquals(listOf("https://policies.google.com/privacy", AppInfo.PROJECT_URL), spans.filterIsInstance<Span.Link>().map { it.url })
    }
}
