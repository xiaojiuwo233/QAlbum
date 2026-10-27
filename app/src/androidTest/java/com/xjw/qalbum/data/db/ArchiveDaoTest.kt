package com.xjw.qalbum.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xjw.qalbum.data.db.dao.QqArchiveCounts
import com.xjw.qalbum.data.db.dao.QqContent
import com.xjw.qalbum.data.db.dao.QqScopeIds
import com.xjw.qalbum.data.db.dao.QqScopeSql
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.domain.model.SourceSort
import com.xjw.qalbum.qq.QqCachePaths
import com.xjw.qalbum.service.cleanup.ArchiveManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 归档：按整张图（家族）归档、隐藏 / 照常显示、归档页每张图一格、后来的版本跟上、扫描标 missing 与复活后重新隐藏、
 * 取消归档，以及按来源范围归档（共用的也算）。
 */
@RunWith(AndroidJUnit4::class)
class ArchiveDaoTest {
    private val root = "/storage/emulated/0/Android/data/com.tencent.mobileqq/Tencent/MobileQQ/chatpic"
    private lateinit var db: AppDatabase
    private val hidden = ArchiveManager.statusesOf(visible = false)
    private val shown = ArchiveManager.statusesOf(visible = true)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .addCallback(AppDatabase.creationCallback)
            .build()
    }

    @After
    fun tearDown() = db.close()

    private fun image(path: String, sourceType: String, size: Long, mtime: Long = 1_000) = ImageEntity(
        path = path, filename = path.substringAfterLast('/'), parentDir = path.substringBeforeLast('/'),
        sourceType = sourceType, cacheKey = path.substringAfterLast('/').removePrefix("Cache_"),
        variantGroup = QqCachePaths.familyKey(path), variantBytes = size, size = size, mtime = mtime,
        firstSeen = 1_000, lastSeen = 1_000, mime = "image/jpeg", width = 1, height = 1,
        status = if (sourceType == "chatthumb") "thumbnail_trash" else "active",
    )

    private val newest = SourceSort.DATE_DESC.ordinal

    private fun status(id: Long): String = db.query("SELECT status FROM images WHERE id = $id", null).use { it.moveToFirst(); it.getString(0) }
    private fun archived(id: Long): Boolean = db.query("SELECT is_protected FROM images WHERE id = $id", null).use { it.moveToFirst(); it.getInt(0) == 1 }

    @Test
    fun archiveWholePicturesHideShowAndFollowNewVersions() = runBlocking {
        val dao = db.imageDao()
        val (img, raw, thumb, hd) = QqCachePaths.relativePaths("a".repeat(32)).map { "$root/$it" }
        val (onlyThumb) = QqCachePaths.relativePaths("b".repeat(32)).drop(2).map { "$root/$it" }
        val (other) = QqCachePaths.relativePaths("c".repeat(32)).map { "$root/$it" }
        val imgId = dao.insert(image(img, "chatimg", 300))
        val rawId = dao.insert(image(raw, "chatraw", 2_000))
        val thumbId = dao.insert(image(thumb, "chatthumb", 20))
        val onlyThumbId = dao.insert(image(onlyThumb, "chatthumb", 30, mtime = 2_000))
        val otherId = dao.insert(image(other, "chatimg", 400))
        assertEquals(2, dao.countActiveTimeline(stacked = true))

        // 归档代表图：同一张图的预览图与缩略图一起归档并隐藏，图库只剩另一张
        dao.archive(listOf(rawId, onlyThumbId), hidden = true)
        listOf(imgId, rawId, thumbId, onlyThumbId).forEach { id ->
            assertTrue(archived(id))
            assertEquals("archived", status(id))
        }
        assertEquals("active", status(otherId))
        assertEquals(1, dao.countActiveTimeline(stacked = true))
        assertEquals(emptyList<ImageEntity>(), dao.getLinkedCleanupEligible(listOf(imgId, rawId, thumbId)))
        // 归档页每张图一格：有原图的取原图，只有缩略图的也能看到
        assertEquals(listOf(onlyThumbId, rawId), dao.archiveTimelineIds(hidden, newest))

        // 照常显示：恢复原状态，仍是归档（受保护），归档页照样列出
        dao.syncArchive(hidden = false)
        assertEquals(listOf("active", "active", "thumbnail_trash"), listOf(imgId, rawId, thumbId).map(::status))
        assertTrue(archived(imgId))
        assertEquals(2, dao.countActiveTimeline(stacked = true))
        assertEquals(listOf(onlyThumbId, rawId), dao.archiveTimelineIds(shown, newest))
        assertEquals(emptyList<ImageEntity>(), dao.getLinkedCleanupEligible(listOf(imgId)))

        // 之后才入库的高清缩略图跟随家族归档；再改回不显示
        val hdId = dao.insert(image(hd, "chatthumb", 50))
        assertEquals(false, archived(hdId))
        dao.syncArchive(hidden = true)
        assertTrue(archived(hdId))
        assertEquals("archived", status(hdId))

        // 文件被 QQ 清掉 → missing；同样的文件回来 → 复活后由同步重新隐藏
        dao.markMissingByIds(listOf(imgId), scanStart = 5_000)
        assertEquals("missing", status(imgId))
        assertEquals(listOf(onlyThumbId, rawId), dao.archiveTimelineIds(hidden, newest))
        dao.reviveByIds(listOf(imgId))
        assertEquals("active", status(imgId))
        dao.syncArchive(hidden = true)
        assertEquals("archived", status(imgId))

        // 扫描到归档文件变化：更新大小，仍保持归档
        dao.updateFromScanByPath(thumb, thumb.substringAfterLast('/'), thumb.substringBeforeLast('/'), "chatthumb",
            thumb.substringAfterLast('/').removePrefix("Cache_"), 25, 1_500, 6_000, "image/jpeg", false, 0, 0)
        assertEquals("archived", status(thumbId))

        // 取消归档：整张图恢复
        dao.unarchive(listOf(imgId))
        listOf(imgId, rawId, thumbId, hdId).forEach { assertEquals(false, archived(it)) }
        assertEquals(listOf("active", "active", "thumbnail_trash", "thumbnail_trash"), listOf(imgId, rawId, thumbId, hdId).map(::status))
        assertEquals(listOf(onlyThumbId), dao.archiveTimelineIds(hidden, newest))
        assertEquals(2, dao.countActiveTimeline(stacked = true))
    }

    @Test
    fun archivePageSortsByDateOrSizeEitherWay() = runBlocking {
        val dao = db.imageDao()
        // 三张图：大小 300 / 2000 / 50，时间 3000 / 1000 / 2000
        val ids = listOf("d" to (300L to 3_000L), "e" to (2_000L to 1_000L), "f" to (50L to 2_000L)).map { (md5, sizeTime) ->
            val (img) = QqCachePaths.relativePaths(md5.repeat(32)).map { "$root/$it" }
            dao.insert(image(img, "chatimg", sizeTime.first, mtime = sizeTime.second))
        }
        dao.archive(ids, hidden = true)
        val (d, e, f) = ids
        assertEquals(listOf(d, f, e), dao.archiveTimelineIds(hidden, SourceSort.DATE_DESC.ordinal))
        assertEquals(listOf(e, f, d), dao.archiveTimelineIds(hidden, SourceSort.DATE_ASC.ordinal))
        assertEquals(listOf(e, d, f), dao.archiveTimelineIds(hidden, SourceSort.SIZE_DESC.ordinal))
        assertEquals(listOf(f, d, e), dao.archiveTimelineIds(hidden, SourceSort.SIZE_ASC.ordinal))
        // 大小分组：300 B 与 50 B 都在「10 KB 以下」，2000 B 也是；点标题按区间全选
        assertEquals(setOf(d, e, f), dao.archiveIdsBySize(hidden, 0, 10_240).toSet())
        assertEquals(emptyList<Long>(), dao.archiveIdsBySize(hidden, 10_240, Long.MAX_VALUE))
    }

    @Test
    fun archiveScopeIncludesSharedImagesAndThumbnails() = runBlocking {
        val dao = db.imageDao()
        val sources = db.qqSourceDao()
        val sql = db.openHelper.writableDatabase
        val (shared) = QqCachePaths.relativePaths("d".repeat(32)).map { "$root/$it" }
        val (own, _, ownThumb) = QqCachePaths.relativePaths("e".repeat(32)).map { "$root/$it" }
        val (outside) = QqCachePaths.relativePaths("f".repeat(32)).map { "$root/$it" }
        val sharedId = dao.insert(image(shared, "chatimg", 100))
        val ownId = dao.insert(image(own, "chatimg", 200))
        val ownThumbId = dao.insert(image(ownThumb, "chatthumb", 10))
        val outsideId = dao.insert(image(outside, "chatimg", 300))
        // 会话 1 里的发送人 2；共用的那张也出现在会话 3
        listOf(sharedId to 1, ownId to 1, ownThumbId to 1, sharedId to 3, outsideId to 3).forEach { (id, conversation) ->
            sql.execSQL("INSERT INTO qq_image_contexts(image_id, conversation_id, sender_id) VALUES($id, $conversation, 2)")
        }
        val scope = QqScopeIds(conversationId = 1, senderId = 2)
        val counts = sources.queryArchiveCounts(QqScopeSql.archiveCounts(scope))
        assertEquals(QqArchiveCounts(images = 2, imageBytes = 300, thumbs = 1, thumbBytes = 10, shared = 1), counts)

        dao.archive(sources.queryIds(QqScopeSql.archiveIds(scope, afterId = 0, limit = 100)), hidden = true)
        listOf(sharedId, ownId, ownThumbId).forEach { assertEquals("archived", status(it)) }
        assertEquals("active", status(outsideId))
        // 归档后从这个范围（以及共用图所在的其他会话）里消失
        assertEquals(emptyList<Long>(), sources.queryIds(QqScopeSql.ids(scope, QqContent.IMAGES, stacked = false)))
        assertEquals(listOf(outsideId), sources.queryIds(QqScopeSql.ids(QqScopeIds(conversationId = 3), QqContent.IMAGES, stacked = false)))
        val after = sources.queryArchiveCounts(QqScopeSql.archiveCounts(scope))
        assertEquals(0, after.images + after.thumbs)
    }
}
