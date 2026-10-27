package com.xjw.qalbum.ui.components

import com.xjw.qalbum.ui.components.Markdown.Block
import com.xjw.qalbum.ui.components.Markdown.Span
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownTest {
    @Test
    fun blocks() {
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
    fun inlineStylesAndLinks() {
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
        // 转义与单词里的下划线不当作格式
        assertEquals(listOf(Span.Text("*不是斜体* snake_case_name")), Markdown.inline("\\*不是斜体\\* snake_case_name"))
        // 粗体里可以有斜体
        assertEquals(
            listOf(Span.Text("粗体里 ", bold = true), Span.Text("斜体", bold = true, italic = true), Span.Text(" 也行", bold = true)),
            Markdown.inline("**粗体里 *斜体* 也行**"),
        )
    }
}
