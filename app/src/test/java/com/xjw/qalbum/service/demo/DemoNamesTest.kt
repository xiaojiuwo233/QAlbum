package com.xjw.qalbum.service.demo

import com.xjw.qalbum.data.db.dao.QqOriginDetails
import com.xjw.qalbum.data.db.dao.QqSourceFacet
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.data.db.entity.QqAutoArchiveEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 演示模式的虚构名称与路径 */
class DemoNamesTest {
    private fun facet(id: Long, name: String, chatType: Int, account: String = "我的QQ") =
        QqSourceFacet(id, "k$id", name, alias = "昵称", count = 1, bytes = 1, thumbCount = 0, thumbBytes = 0, chatType = chatType, latestTime = 0, accountName = account)

    private fun image(id: Long, path: String, sourceType: String) = ImageEntity(
        id = id, path = path, filename = path.substringAfterLast('/'), parentDir = path.substringBeforeLast('/'),
        sourceType = sourceType, cacheKey = "k", size = 1, mtime = 1, firstSeen = 1, lastSeen = 1,
        mime = "image/jpeg", width = 1, height = 1,
    )

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
        val real = image(42, "/storage/emulated/0/Android/data/com.tencent.mobileqq/Tencent/MobileQQ/chatpic/chatthumb/abc/Cache_123abc_hd.jpg", "chatthumb")
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
}
