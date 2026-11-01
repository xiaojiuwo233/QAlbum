package com.xjw.qalbum.ui.onboarding

import com.xjw.qalbum.AppInfo
import com.xjw.qalbum.ui.components.Markdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 用户协议的 Markdown 要按预期分节：六节，条款编号，加粗都闭合，链接只有 Google 隐私政策和项目主页 */
class UserAgreementTest {
    private val blocks = Markdown.parse(USER_AGREEMENT)

    @Test
    fun `六节标题按顺序`() {
        val headings = blocks.filterIsInstance<Markdown.Block.Heading>().map { it.text }
        assertEquals(
            listOf("一、数据安全与风险提示", "二、高级功能特别说明", "三、使用限制", "四、隐私说明", "五、开源与发布渠道", "六、免责声明"),
            headings,
        )
    }

    @Test
    fun `隐私政策与发布渠道的地址都能点开`() {
        val links = blocks.filterIsInstance<Markdown.Block.Item>().flatMap { Markdown.inline(it.text) }.filterIsInstance<Markdown.Span.Link>()
        assertEquals(listOf("https://policies.google.com/privacy", AppInfo.PROJECT_URL), links.map { it.url })
    }

    @Test
    fun `条款都是编号列表，加粗都闭合`() {
        val items = blocks.filterIsInstance<Markdown.Block.Item>()
        assertTrue(items.size >= 15)
        assertTrue(items.all { it.depth == 0 && it.marker.endsWith(".") })
        val texts = blocks.flatMap { block ->
            when (block) {
                is Markdown.Block.Item -> Markdown.inline(block.text)
                is Markdown.Block.Paragraph -> Markdown.inline(block.text)
                else -> emptyList()
            }
        }
        assertTrue(texts.none { it is Markdown.Span.Text && "**" in it.text })
        assertTrue(texts.any { it is Markdown.Span.Text && it.bold && it.text.contains("不会收集或上传您的图片") })
        assertTrue(texts.any { it is Markdown.Span.Text && it.bold && it.text.contains("全部关闭后，本应用仅记录统计活跃用户所必需的打开次数与使用时长") })
    }
}
