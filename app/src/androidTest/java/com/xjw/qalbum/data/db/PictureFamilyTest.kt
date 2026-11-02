package com.xjw.qalbum.data.db

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xjw.qalbum.CHATPIC
import com.xjw.qalbum.TestDatabase
import com.xjw.qalbum.TestSettings
import com.xjw.qalbum.addOrigins
import com.xjw.qalbum.data.db.dao.QqContent
import com.xjw.qalbum.data.db.dao.QqScopeIds
import com.xjw.qalbum.data.db.dao.QqScopeOrder
import com.xjw.qalbum.data.db.dao.QqScopeSql
import com.xjw.qalbum.domain.model.TimelineSection
import com.xjw.qalbum.exec
import com.xjw.qalbum.familyPaths
import com.xjw.qalbum.imageRow
import com.xjw.qalbum.long
import com.xjw.qalbum.qq.QqCachePaths
import com.xjw.qalbum.qq.QqSourceStatsBuilder
import com.xjw.qalbum.qqOrigin
import com.xjw.qalbum.service.indexer.PictureVariantIndexer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 图片家族（同一张图的原图 / 预览图 / 缩略图）：触发器维护代表图、版本数与合计大小，回填后的分段重算；
 * 合并显示时的时间线计数、按整组合计的排序与分组、点标题全选的范围、删除确认用的家族大小；
 * 带后缀的别名并进家族并保留聊天来源。
 */
@RunWith(AndroidJUnit4::class)
class PictureFamilyTest {
    @get:Rule val database = TestDatabase()
    @get:Rule val settings = TestSettings()
    private val db get() = database.db

    @Test
    fun triggersKeepTheLargestVisibleVersionAsRepresentative() = runBlocking {
        val (img, raw, thumb, hd) = familyPaths("0123456789abcdef0123456789abcdef")
        val imgId = insert(img, "chatimg", 300)
        val rawId = insert(raw, "chatraw", 2_000)
        val thumbId = insert(thumb, "chatthumb", 20)
        insert(hd, "chatthumb", 60)
        // 原图最大：代表整组（4 个版本、合计 2380），预览图隐藏，缩略图本就不进图库；其余版本的大小是自身大小
        assertRow(rawId, hidden = 0, count = 4, bytes = 2_380)
        assertRow(imgId, hidden = 1, count = 0, bytes = 300)
        assertRow(thumbId, hidden = 0, count = 0, bytes = 20)

        // 原图移入回收站：预览图接替代表，版本数与合计随之变化
        db.exec("UPDATE images SET status = 'quarantine_pending' WHERE id = $rawId")
        assertRow(imgId, hidden = 0, count = 3, bytes = 380)
        assertRow(rawId, hidden = 0, count = 0, bytes = 2_000)
        // 恢复：原图重新成为代表
        db.exec("UPDATE images SET status = 'active' WHERE id = $rawId")
        assertRow(rawId, hidden = 0, count = 4, bytes = 2_380)
        assertRow(imgId, hidden = 1, count = 0, bytes = 300)
        // 预览图被 QQ 重写变大；缩略图被 QQ 清掉
        db.exec("UPDATE images SET size = 500 WHERE id = $imgId")
        db.exec("UPDATE images SET status = 'missing' WHERE id = $thumbId")
        assertRow(rawId, hidden = 0, count = 3, bytes = 2_560)
        // 删除行
        db.exec("DELETE FROM images WHERE id = $rawId")
        assertRow(imgId, hidden = 0, count = 2, bytes = 560)

        // 非 QQ 命名的图片自成一张，合并显示时的大小就是自身大小
        val plain = insert("$CHATPIC/temp/x.jpg", "temp", 100)
        assertRow(plain, hidden = 0, count = 0, bytes = 100)
        db.exec("UPDATE images SET size = 150 WHERE id = $plain")
        assertRow(plain, hidden = 0, count = 0, bytes = 150)
    }

    @Test
    fun backfillAndRecompute() = runBlocking {
        val (img, raw, thumb) = familyPaths("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        val (otherImg) = familyPaths("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
        val (thirdImg, thirdRaw) = familyPaths("cccccccccccccccccccccccccccccccc")
        // 升级前入库：没有家族键
        val imgId = insert(img, "chatimg", 300, family = false)
        val rawId = insert(raw, "chatraw", 2_000, family = false)
        insert(thumb, "chatthumb", 20, family = false)
        insert(otherImg, "chatimg", 400, family = false)
        val thirdImgId = insert(thirdImg, "chatimg", 100, family = false)
        val thirdRawId = insert(thirdRaw, "chatraw", 900, family = false)

        // 回填：撤掉家族索引与触发器 → 写家族键 → 恢复 → 分段重算
        val sql = db.openHelper.writableDatabase
        val familySchema = "SELECT COUNT(*) FROM sqlite_master WHERE name = 'index_images_variant_group' OR name LIKE 'image_variants_%'"
        ImageVariantSql.suspendForBackfill(sql)
        assertEquals(0L, db.long(familySchema))
        db.exec("UPDATE images SET variant_group = ${QqCachePaths.familyKey(img)} WHERE path IN (?, ?, ?)", img, raw, thumb)
        db.exec("UPDATE images SET variant_group = ${QqCachePaths.familyKey(otherImg)} WHERE path = ?", otherImg)
        db.exec("UPDATE images SET variant_group = ${QqCachePaths.familyKey(thirdImg)} WHERE path IN (?, ?)", thirdImg, thirdRaw)
        // 写键期间的变化不维护：第三张图的原图被移入回收站
        db.exec("UPDATE images SET status = 'quarantine_pending' WHERE id = $thirdRawId")
        assertRow(rawId, hidden = 0, count = 0, bytes = 2_000)
        ImageVariantSql.resumeAfterBackfill(sql)
        assertEquals(4L, db.long(familySchema))
        db.exec(ImageVariantSql.recomputeRange, 0, 2)
        db.exec(ImageVariantSql.recomputeRange, 2, Long.MAX_VALUE)
        assertRow(rawId, hidden = 0, count = 3, bytes = 2_320)
        assertRow(imgId, hidden = 1, count = 0, bytes = 300)
        // 重算按当前状态：第三张图只剩预览图一个版本
        assertRow(thirdImgId, hidden = 0, count = 0, bytes = 100)
        assertRow(thirdRawId, hidden = 0, count = 0, bytes = 900)
        // 重复执行结果不变
        db.exec(ImageVariantSql.recomputeRange, 0, Long.MAX_VALUE)
        assertRow(rawId, hidden = 0, count = 3, bytes = 2_320)

        // 图库计数：合并后同一张图只算一次（缩略图本就不计）
        assertEquals(4L, db.long("SELECT COUNT(*) FROM images WHERE status = 'active' AND source_type <> 'chatthumb'"))
        assertEquals(3L, db.long("SELECT COUNT(*) FROM images WHERE status = 'active' AND source_type <> 'chatthumb' AND variant_hidden = 0"))
    }

    @Test
    fun stackedQueriesFollowTheFamilyRepresentative() = runBlocking {
        val dao = db.imageDao()
        val (img, raw, thumb) = familyPaths("dddddddddddddddddddddddddddddddd")
        val (other) = familyPaths("eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee")
        val imgId = insert(img, "chatimg", 300)
        val thumbId = insert(thumb, "chatthumb", 20)
        val otherId = insert(other, "chatimg", 400)
        // 之后才下载了原图：原图成为代表、预览图被隐藏
        val rawId = insert(raw, "chatraw", 2_000)

        assertEquals(2, dao.countActiveTimeline(stacked = true))
        assertEquals(3, dao.countActiveTimeline(stacked = false))
        val family = QqCachePaths.familyKey(img)!!
        assertEquals(listOf(rawId, imgId, thumbId), dao.familyMembers(family).map { it.id })
        // 合并显示按整组合计排序 / 分组；不合并时按文件自身大小
        assertEquals(listOf(rawId, otherId), dao.stackedIdsAfterSizeDesc(Long.MAX_VALUE, Long.MAX_VALUE, 10))
        assertEquals(listOf(rawId), dao.stackedIdsBySize(2_100, 3_000))
        assertEquals(emptyList<Long>(), dao.timelineIdsBySize(2_100, 3_000))
        assertEquals(setOf(rawId, imgId, otherId), dao.timelineIdsBetween(0, 2_000, stacked = false).toSet())
        assertEquals(setOf(rawId, otherId), dao.timelineIdsBetween(0, 2_000, stacked = true).toSet())
        // 删除确认：所选原图的家族共 3 个现存版本，合计 2320
        assertEquals(family, dao.deleteFactRows(listOf(rawId)).single().variantGroup)
        assertEquals(2_320L, dao.familyMemberSizes(listOf(family)).sumOf { it.size })

        // 来源页：同样只留代表图，大小分组按整组合计
        listOf(imgId, rawId, thumbId, otherId).forEach { id ->
            db.exec("INSERT INTO qq_image_contexts(image_id, conversation_id, sender_id) VALUES($id, 1, 2)")
        }
        val sources = db.qqSourceDao()
        val all = QqScopeIds()
        assertEquals(setOf(rawId, otherId), sources.queryIds(QqScopeSql.ids(all, QqContent.IMAGES, stacked = true)).toSet())
        assertEquals(setOf(rawId, imgId, otherId), sources.queryIds(QqScopeSql.ids(all, QqContent.IMAGES, stacked = false)).toSet())
        assertEquals(listOf(rawId, otherId), sources.queryIds(QqScopeSql.page(all, QqContent.IMAGES, QqScopeOrder.SIZE_DESC, stacked = true)))
        assertEquals(listOf(rawId), sources.queryIds(QqScopeSql.sectionIds(all, QqContent.IMAGES, stacked = true, TimelineSection.Size(2_100, 3_000))))
    }

    @Test
    fun upgradingV2RestacksJpegsAndRetainsSources() = runBlocking {
        val (img, raw, thumb) = seedSources()
        val dao = db.imageDao()
        insert(img, "chatimg", 300)
        insert(raw, "chatraw", 2_000)
        insert(thumb, "chatthumb", 20)
        val jpgId = dao.insert(imageRow("$raw.jpg", "chatraw", 3_000, archived = true, variantGroup = null))
        QqSourceStatsBuilder(db).rebuild {}
        assertEquals(2, dao.countActiveTimeline(stacked = true))
        settings.repository.setVariantIndexVersion(2)

        val indexer = PictureVariantIndexer(db, settings.repository)
        indexer.ensureIndexed()
        assertEquals(4, settings.repository.variantIndexVersion.first())
        assertEquals(1, dao.countActiveTimeline(stacked = true))
        val jpg = dao.getAnyByIds(listOf(jpgId)).single()
        assertEquals(QqCachePaths.familyKey(raw), jpg.variantGroup)
        assertEquals(4, jpg.variantCount)
        assertEquals(5_320L, jpg.variantBytes)
        assertTrue(jpg.isArchived)
        QqSourceStatsBuilder(db).rebuild {}
        val sources = db.qqSourceDao()
        val group = sources.facetId(0, "group")!!
        val sender = sources.facetId(1, "sender")!!
        val forwarded = sources.facetId(0, "forwarded")!!
        assertEquals(listOf(jpgId), sources.queryIds(QqScopeSql.ids(QqScopeIds(group, sender), QqContent.IMAGES, stacked = true)))
        assertEquals(listOf(jpgId), sources.queryIds(QqScopeSql.ids(QqScopeIds(conversationId = forwarded), QqContent.IMAGES, stacked = true)))
        // 别名所在的家族出现在两个会话里：删除规划据此判断「共用」
        val range = sources.familyContextRange(listOf(jpg.variantGroup!!)).single()
        assertNotEquals(range.low, range.high)
        assertEquals(setOf("Alice", "Forwarder"), sources.observeImageOrigins(jpgId).first().map { it.senderName }.toSet())
        assertFalse(sources.observeSummary().first()!!.dirty)
        // 同一版本再次启动不回填、不触发来源重建
        indexer.ensureIndexed()
        assertFalse(sources.observeSummary().first()!!.dirty)
    }

    @Test
    fun newJpegIsLinkedAndCountedIncrementally() = runBlocking {
        val raw = seedSources()[1]
        QqSourceStatsBuilder(db).rebuild {}
        val sources = db.qqSourceDao()
        // 别名与同名缓存同一家族：入库时按家族键关联、计入统计
        val jpgId = insert("$raw.jpg", "chatraw", 1_000)
        assertEquals(2, sources.observeImageOrigins(jpgId).first().size)
        val group = sources.facetId(0, "group")!!
        assertEquals(listOf(jpgId), sources.queryIds(QqScopeSql.ids(QqScopeIds(conversationId = group), QqContent.IMAGES, stacked = true)))
        val summary = sources.observeSummary().first()!!
        assertFalse(summary.dirty)
        assertEquals(1, summary.imageCount)
        assertEquals(1_000L, summary.imageBytes)
        sources.reconcileLinks()
        assertEquals(summary, sources.observeSummary().first())
        QqSourceStatsBuilder(db).rebuild {}
        assertEquals(summary, sources.observeSummary().first())
    }

    /** 同一张图在群里和转发记录里各出现一次，返回它的各版本路径 */
    private suspend fun seedSources(): List<String> {
        val md5 = "dddddddddddddddddddddddddddddddd"
        db.addOrigins(
            qqOrigin("direct", md5, "group", "sender", 1_000, senderName = "Alice", conversationName = "Photography"),
            qqOrigin("forward", md5, "forwarded", "forwarder", 2_000, chatType = 3, senderName = "Forwarder", conversationName = "Forwarded records"),
        )
        return familyPaths(md5)
    }

    /** [family] 为 false 时像升级前入库的那样没有家族键 */
    private suspend fun insert(path: String, sourceType: String, size: Long, family: Boolean = true): Long =
        db.imageDao().insert(imageRow(path, sourceType, size, variantGroup = if (family) QqCachePaths.familyKey(path) else null))

    private fun assertRow(id: Long, hidden: Long, count: Long, bytes: Long) {
        db.query("SELECT variant_hidden, variant_count, variant_bytes FROM images WHERE id = $id", null).use { c ->
            c.moveToFirst()
            assertEquals("image $id", listOf(hidden, count, bytes), (0 until 3).map { c.getLong(it) })
        }
    }
}
