package com.xjw.qalbum.service.demo

import coil3.size.Dimension
import coil3.size.Scale
import coil3.size.Size
import com.xjw.qalbum.CHATPIC
import com.xjw.qalbum.data.db.dao.QqOriginDetails
import com.xjw.qalbum.data.db.dao.QqSourceFacet
import com.xjw.qalbum.data.db.entity.QqAutoArchiveEntity
import com.xjw.qalbum.imageRow
import com.xjw.qalbum.ui.imageloading.DemoImages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 演示模式：虚构的名称与路径，示意图的大小（比例同原图，格子铺满、看图放进，长图画宽一些） */
class DemoModeTest {
    private fun facet(id: Long, name: String, chatType: Int, account: String = "我的QQ") =
        QqSourceFacet(id, "k$id", name, alias = "昵称", count = 1, bytes = 1, thumbCount = 0, thumbBytes = 0, chatType = chatType, latestTime = 0, accountName = account)

    private fun box(w: Int, h: Int) = Size(Dimension(w), Dimension(h))

    @Test
    fun `群和人换成虚构的名称，同一个真名总是同一个假名`() {
        val group = DemoNames.facet(facet(1, "某某公司内部群", chatType = 2))
        val person = DemoNames.facet(facet(2, "张三", chatType = 0))
        assertNotEquals("某某公司内部群", group.name)
        assertNotEquals("张三", person.name)
        assertEquals("", group.alias)
        assertEquals("演示账号", group.accountName)
        assertEquals(group.name, DemoNames.facet(facet(9, "某某公司内部群", chatType = 2)).name)
        assertEquals(person.name, DemoNames.origin(QqOriginDetails("张三", "张三", "", 1, 0, 1, "")).senderName)
    }

    @Test
    fun `转发的聊天记录和合成的分类不换`() {
        val forwarded = facet(3, "转发的聊天记录", chatType = 3, account = "全部账号")
        assertEquals(forwarded.copy(alias = "昵称"), DemoNames.facet(forwarded))
        val all = facet(0, "全部来源图片", chatType = 0, account = "")
        assertEquals(all, DemoNames.facet(all))
    }

    @Test
    fun `详情里的来源：会话与发送人换掉，群名片不显示，转发记录的会话名照旧`() {
        val origin = DemoNames.origin(QqOriginDetails("转发的聊天记录", "李四", "李四的群名片", 3, 0, 1, "我的QQ"))
        assertEquals("转发的聊天记录", origin.conversationName)
        assertNotEquals("李四", origin.senderName)
        assertEquals("", origin.memberName)
        assertEquals("演示账号", origin.accountName)
    }

    @Test
    fun `列表按假名搜索，按名称排序时转发记录仍在最前`() {
        val list = listOf(facet(1, "乙群", 2), facet(2, "转发的聊天记录", 3), facet(3, "甲群", 2))
        val mapped = DemoNames.facetList(list, query = "", byName = true)
        assertEquals(3, mapped.first().chatType)
        assertEquals(mapped.drop(1).map { it.name }.sorted(), mapped.drop(1).map { it.name })
        val target = mapped.first { it.chatType == 2 }.name
        assertTrue(DemoNames.facetList(list, query = target, byName = false).all { it.name.contains(target) })
        assertEquals(emptyList<QqSourceFacet>(), DemoNames.facetList(list, query = "甲群", byName = false))
    }

    @Test
    fun `路径保留 QQ 缓存的目录结构，文件名按图片 id 生成`() {
        val real = imageRow("$CHATPIC/chatthumb/abc/Cache_123abc_hd.jpg", "chatthumb", id = 42)
        val path = DemoNames.path(real)
        val name = DemoNames.filename(real)
        assertTrue(name, name.startsWith("Cache_") && name.endsWith("_hd.jpg"))
        assertEquals(name, path.substringAfterLast('/'))
        val dir = path.substringBeforeLast('/').substringAfterLast('/')
        assertEquals(name.removeSuffix("_hd.jpg").takeLast(3), dir)
        assertTrue(path, path.contains("/chatpic/chatthumb/"))
        assertNotEquals(path, DemoNames.path(real.copy(id = 43)))
    }

    @Test
    fun `自动归档名单：会话和成员分别换`() {
        val entry = QqAutoArchiveEntity("c", "s", "某某公司内部群 · 张三", 0)
        assertEquals("${DemoNames.group("某某公司内部群")} · ${DemoNames.person("张三")}", DemoNames.autoArchiveLabel(entry))
        val sender = QqAutoArchiveEntity("", "s", "张三", 0)
        assertEquals(DemoNames.person("张三"), DemoNames.autoArchiveLabel(sender))
    }

    @Test
    fun `示意图：格子裁切时铺满，看图时放进屏幕`() {
        assertEquals(400 to 300, DemoImages.pixelSize(4000, 3000, box(300, 300), Scale.FILL))
        assertEquals(1080 to 810, DemoImages.pixelSize(4000, 3000, box(1080, 2400), Scale.FIT))
    }

    @Test
    fun `示意图：长图至少按 1080 宽画，长边不超过 4096`() {
        assertEquals(1080 to 3600, DemoImages.pixelSize(1000, 3333, box(720, 2400), Scale.FIT).let { (w, h) -> w to (h / 100 * 100) })
        assertEquals(410 to 4096, DemoImages.pixelSize(1000, 10000, box(240, 2400), Scale.FIT))
    }

    @Test
    fun `示意图：不知道尺寸时按方图，不限大小时长边 1440`() {
        assertEquals(300 to 300, DemoImages.pixelSize(0, 0, box(300, 300), Scale.FILL))
        assertEquals(1440 to 1080, DemoImages.pixelSize(4000, 3000, Size.ORIGINAL, Scale.FIT))
    }
}
