package com.xjw.qalbum.data.db

import androidx.sqlite.db.SupportSQLiteQuery
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xjw.qalbum.CHATPIC
import com.xjw.qalbum.TestDatabase
import com.xjw.qalbum.addOrigins
import com.xjw.qalbum.data.db.dao.QqContent
import com.xjw.qalbum.data.db.dao.QqScopeIds
import com.xjw.qalbum.data.db.dao.QqScopeOrder
import com.xjw.qalbum.data.db.dao.QqScopeSql
import com.xjw.qalbum.exec
import com.xjw.qalbum.familyPaths
import com.xjw.qalbum.imageRow
import com.xjw.qalbum.int
import com.xjw.qalbum.longs
import com.xjw.qalbum.qq.QqCachePaths
import com.xjw.qalbum.qq.QqSourceStatsBuilder
import com.xjw.qalbum.qqOrigin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 聊天来源的关联与统计：图片按家族键关联消息（带后缀的别名、改家族键、读取后新增的消息也算），
 * 分段重建的结果，状态 / 大小变化与新入库后增量维护的统计始终与整体重算一致，以及浏览范围的查询。
 */
@RunWith(AndroidJUnit4::class)
class QqSourceStatsTest {
    @get:Rule val database = TestDatabase()
    private val db get() = database.db

    private val photo = "11111111111111111111111111111111"
    private val shared = "22222222222222222222222222222222"

    @Test
    fun rebuildInStepsThenTriggersKeepStatsInStep() = runBlocking {
        val sources = db.qqSourceDao()
        db.addOrigins(
            qqOrigin("o1", photo, "g1", "s1", 1_000, conversationName = "摄影群", senderName = "小明", memberName = "名片1"),
            qqOrigin("o2", shared, "g1", "s2", 2_000, conversationName = "摄影群", senderName = "小红"),
            qqOrigin("o3", photo, "p1", "s1", 3_000, chatType = 1, conversationName = "小明", senderName = "小明"),
        )
        val (photoImg, _, photoThumb) = familyPaths(photo)
        val (sharedImg, _, sharedThumb) = familyPaths(shared)
        val image = insert(photoImg, "chatimg", 100)
        val thumb = insert(photoThumb, "chatthumb", 10)
        val other = insert(sharedImg, "chatimg", 200)
        insert("$CHATPIC/temp/unlinked", "temp", 50)
        rebuildInTwoSteps()

        val group = sources.facetId(0, "g1")!!
        val private = sources.facetId(0, "p1")!!
        val alice = sources.facetId(1, "s1")!!
        val bob = sources.facetId(1, "s2")!!
        // 群 g1：两张图（300B）与一张缩略图；私聊 p1 与群共用这张照片和它的缩略图
        assertFacet(group, images = 2, imageBytes = 300, thumbs = 1, thumbBytes = 10)
        assertFacet(private, images = 1, imageBytes = 100, thumbs = 1, thumbBytes = 10)
        assertFacet(alice, images = 1, imageBytes = 100, thumbs = 1, thumbBytes = 10)
        assertFacet(bob, images = 1, imageBytes = 200, thumbs = 0, thumbBytes = 0)
        assertEquals(listOf(2L, 300L, 1L, 10L, 0L), cache())
        assertEquals(1, db.int("SELECT COUNT(*) FROM qq_source_members WHERE conversation_id = $group AND sender_id = $alice AND card = '名片1'"))

        // 移入回收站：先 pending（减掉），再落定（不变）
        db.exec("UPDATE images SET status = 'quarantine_pending' WHERE id = $image")
        db.exec("UPDATE images SET status = 'quarantined', path = 'qalbum-internal://quarantine/$image' WHERE id = $image")
        assertFacet(group, images = 1, imageBytes = 200, thumbs = 1, thumbBytes = 10)
        assertFacet(private, images = 0, imageBytes = 0, thumbs = 1, thumbBytes = 10)
        assertEquals(listOf(1L, 200L, 1L, 10L, 0L), cache())
        assertConsistent()

        // 恢复：占回原路径，再转 active
        db.exec("UPDATE images SET status = 'restore_pending', path = ? WHERE id = $image", photoImg)
        db.exec("UPDATE images SET status = 'active' WHERE id = $image")
        assertFacet(group, images = 2, imageBytes = 300, thumbs = 1, thumbBytes = 10)
        assertConsistent()

        // 文件被 QQ 重写变大、缩略图被 QQ 清掉
        db.exec("UPDATE images SET size = 250 WHERE id = $other")
        db.exec("UPDATE images SET status = 'missing' WHERE id = $thumb")
        assertFacet(group, images = 2, imageBytes = 350, thumbs = 0, thumbBytes = 0)
        assertConsistent()

        // 新扫描入库：另一张图的缩略图，按家族键关联到群 g1 / 发送人 s2
        val newThumb = insert(sharedThumb, "chatthumb", 20)
        assertFacet(group, images = 2, imageBytes = 350, thumbs = 1, thumbBytes = 20)
        assertFacet(bob, images = 1, imageBytes = 250, thumbs = 1, thumbBytes = 20)
        assertEquals(listOf(2L, 350L, 1L, 20L, 0L), cache())
        assertConsistent()

        // 浏览范围：群里的图片按时间倒序、共用统计、可清理 id
        val groupScope = QqScopeIds(conversationId = group)
        assertEquals(listOf(other, image), ids(QqScopeSql.page(groupScope, QqContent.IMAGES, QqScopeOrder.DATE_DESC, stacked = false)))
        assertEquals(listOf(newThumb), ids(QqScopeSql.page(groupScope, QqContent.THUMBNAILS, QqScopeOrder.SIZE_DESC, stacked = false)))
        assertEquals(listOf(image), ids(QqScopeSql.page(QqScopeIds(group, alice), QqContent.IMAGES, QqScopeOrder.DATE_ASC, stacked = false)))
        assertEquals(listOf(other, image), ids(QqScopeSql.page(QqScopeIds(), QqContent.IMAGES, QqScopeOrder.SIZE_DESC, stacked = false)))
        // total, totalBytes, shared, protected, removable, removableBytes
        assertEquals(listOf(2L, 350L, 1L, 0L, 1L, 250L), row(QqScopeSql.counts(groupScope, setOf(QqContent.IMAGES), includeShared = false), 6))
        assertEquals(listOf(other), ids(QqScopeSql.removableIds(groupScope, setOf(QqContent.IMAGES), false, 0, 99, 10)))
        assertEquals(listOf(image, other, newThumb), ids(QqScopeSql.removableIds(groupScope, QqContent.entries.toSet(), true, 0, 99, 10)))
        assertEquals(listOf(1L, 20L), row(QqScopeSql.stats(QqScopeIds(senderId = bob), QqContent.THUMBNAILS), 2))
    }

    @Test
    fun linksAddedByFamilyKeepStatsInStepWithARecount() = runBlocking {
        val sources = db.qqSourceDao()
        db.addOrigins(
            qqOrigin("o1", photo, "g1", "s1", 1_000),
            qqOrigin("o2", shared, "g1", "s2", 2_000),
            qqOrigin("o3", photo, "p1", "s1", 3_000, chatType = 1),
        )
        val (photoImg, photoRaw, photoThumb) = familyPaths(photo)
        insert(photoImg, "chatimg", 100)
        insert(photoThumb, "chatthumb", 10)
        // 第一次整体重建之后才增量维护
        QqSourceStatsBuilder(db).rebuild {}
        assertFalse(sources.cacheDirty())
        assertConsistent()

        // 扫描入库：另一张图的预览图、这张图的原图与带后缀的别名
        insert(familyPaths(shared)[0], "chatimg", 200)
        insert(photoRaw, "chatraw", 1_000)
        insert("$photoRaw.png", "chatraw", 900)
        assertEquals(2, links(photoRaw))
        assertConsistent()

        // 发送图片缓存：先按自己内容的家族入库（没有来源），并进原图那一家后关联
        val preview = insert("$CHATPIC/chatimg/abc/Cache_123abc", "chatimg", 50)
        assertEquals(0, links(preview))
        db.exec("UPDATE images SET variant_group = ${QqCachePaths.md5Family(photo)} WHERE id = $preview")
        assertEquals(2, links(preview))
        assertConsistent()

        // 移入回收站再恢复
        db.exec("UPDATE images SET status = 'quarantined', path = 'qalbum-internal://quarantine/$preview' WHERE id = $preview")
        assertConsistent()
        db.exec("UPDATE images SET status = 'active', path = '$CHATPIC/chatimg/abc/Cache_123abc' WHERE id = $preview")
        assertConsistent()

        // 读到新消息：已有的群里来了新发送人（还没有分类，关联时先跳过，读取结束时补上）
        val baseline = sources.readBaseline()
        db.addOrigins(qqOrigin("o4", shared, "g1", "s3", 4_000), qqOrigin("o5", photo, "g2", "s1", 5_000))
        sources.linkAfter(baseline)
        QqSourceStatsBuilder(db).applyNew(baseline, emptySet())
        assertConsistent()
        assertEquals(listOf(3L, 3L), db.longs("SELECT COUNT(*) FROM qq_source_facets WHERE kind = 0 UNION ALL SELECT COUNT(*) FROM qq_source_facets WHERE kind = 1"))
    }

    /** QQ 按原文件名带后缀另存的缓存（发送本地图片、另存后转发时出现）：读取来源后补关联、扫描新入库时都按家族键关联 */
    @Test
    fun aliasesWithAnyPictureExtensionFollowTheirCacheName() = runBlocking {
        val md5 = "0123456789abcdef0123456789abcdef"
        val raw = familyPaths(md5)[1]
        val sources = db.qqSourceDao()
        // 已经入库的别名：读到这条消息后按家族键补上关联
        listOf("png", "gif", "JPEG", "txt").forEach { insert("$raw.$it", "chatraw", 1_000) }
        val baseline = sources.readBaseline()
        db.addOrigins(qqOrigin("origin", md5))
        sources.linkAfter(baseline)
        assertEquals(1, links("$raw.png"))
        assertEquals(1, links("$raw.gif"))
        assertEquals(1, links("$raw.JPEG"))
        assertEquals(0, links("$raw.txt"))

        // 扫描新入库的别名：来源触发器插入时按家族键关联
        insert("$raw.webp", "chatraw", 1_000)
        assertEquals(1, links("$raw.webp"))
    }

    private suspend fun insert(path: String, type: String, size: Long): Long = db.imageDao().insert(imageRow(path, type, size))

    private fun links(path: String): Int = db.int("SELECT COUNT(*) FROM image_qq_origins l JOIN images i ON i.id = l.image_id WHERE i.path = ?", path)

    private fun links(id: Long): Int = db.int("SELECT COUNT(*) FROM image_qq_origins WHERE image_id = $id")

    /** 与仓库的分段重建相同的三步，第二步分成两段 */
    private fun rebuildInTwoSteps() {
        val sql = db.openHelper.writableDatabase
        QqSourceStatsSql.rebuildNames(sql)
        QqSourceStatsSql.contextsInRange(sql, 0, 2)
        QqSourceStatsSql.contextsInRange(sql, 2, Long.MAX_VALUE)
        QqSourceStatsSql.recount.forEach(sql::execSQL)
    }

    private fun assertFacet(id: Long, images: Long, imageBytes: Long, thumbs: Long, thumbBytes: Long) =
        assertEquals(listOf(images, imageBytes, thumbs, thumbBytes), row("SELECT image_count, image_bytes, thumb_count, thumb_bytes FROM qq_source_facets WHERE id = $id", 4))

    private fun cache(): List<Long> = row("SELECT image_count, image_bytes, thumb_count, thumb_bytes, dirty FROM qq_source_cache WHERE id = 1", 5)

    private fun row(sql: String, columns: Int): List<Long> = db.query(sql, null).use { c -> c.moveToFirst(); (0 until columns).map { c.getLong(it) } }

    private fun row(query: SupportSQLiteQuery, columns: Int): List<Long> = db.query(query).use { c -> c.moveToFirst(); (0 until columns).map { c.getLong(it) } }

    private fun ids(query: SupportSQLiteQuery): List<Long> = db.query(query).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }

    /** 增量维护的统计与按当前状态整体重算完全一致（投影、各分类、汇总） */
    private fun assertConsistent() {
        val sql = db.openHelper.writableDatabase
        fun facets() = db.query("SELECT kind, `key`, image_count, image_bytes, thumb_count, thumb_bytes FROM qq_source_facets ORDER BY kind, `key`", null).use { c ->
            buildList { while (c.moveToNext()) add("${c.getInt(0)}:${c.getString(1)} " + (2..5).map { c.getLong(it) }) }
        }
        fun contexts() = db.query("SELECT image_id, conversation_id, sender_id FROM qq_image_contexts ORDER BY 1, 2, 3", null).use { c ->
            buildList { while (c.moveToNext()) add((0..2).map { c.getLong(it) }) }
        }
        val stored = Triple(facets(), cache(), contexts())
        // 投影按关联重新投一遍、计数按当前状态重算
        sql.execSQL(QqSourceStatsSql.CONTEXTS_IN_RANGE, arrayOf<Any>(0, Long.MAX_VALUE))
        QqSourceStatsSql.recount.forEach(sql::execSQL)
        assertEquals(Triple(facets(), cache(), contexts()), stored)
    }
}
