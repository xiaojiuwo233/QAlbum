package com.xjw.qalbum.data.db

import androidx.paging.PagingSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xjw.qalbum.TestDatabase
import com.xjw.qalbum.data.db.dao.TrashUnit
import com.xjw.qalbum.data.db.entity.OriginalBackupEntity
import com.xjw.qalbum.data.db.entity.QuarantineEntity
import com.xjw.qalbum.domain.model.SourceSort
import com.xjw.qalbum.domain.model.TimelineSection
import com.xjw.qalbum.familyPaths
import com.xjw.qalbum.imageRow
import com.xjw.qalbum.md5
import com.xjw.qalbum.ui.gallery.dayKey
import com.xjw.qalbum.ui.gallery.daySection
import com.xjw.qalbum.ui.gallery.sizeBucketId
import com.xjw.qalbum.ui.gallery.sizeBucketSection
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 回收站页的查询：同一次删除的各版本合成一格（组里最大的文件代表，张数与大小按整组），按移入时间 / 整组大小排序，
 * 按删除原因筛选，分组计数与「点标题全选」的范围一致；压缩前的原图按压缩时间与原图大小。
 */
@RunWith(AndroidJUnit4::class)
class RecycleBinQueryTest {
    @get:Rule val database = TestDatabase()
    private val db get() = database.db
    private val now = 1_760_000_000_000L
    private val day = 24L * 60 * 60 * 1000
    private var families = 0

    @Test
    fun eachDeletionIsOneTileRepresentedByItsLargestFile() = runBlocking {
        // 旧图片：原图、预览图、缩略图一起删（3 天前）；缩略图规则只删了一张缩略图（今天）；旧版的单条记录
        val old = trashGroup("旧图片", now - 3 * day, "chatimg" to 200_000L, "chatraw" to 2_000_000L, "chatthumb" to 20_000L)
        val thumb = trashGroup("QQ 缩略图", now, "chatthumb" to 30_000L)
        val legacy = trashGroup("用户批量删除", now - day, "chatraw" to 500_000L, legacy = true)
        // 还在移入途中的记录不显示
        trashGroup("旧图片", now, "chatraw" to 9_000_000L, state = "pending")

        val byTime = units(SourceSort.DATE_DESC)
        assertEquals(listOf(thumb.rep, legacy.rep, old.rep), byTime.map { it.image.id })
        val oldUnit = byTime.last()
        assertEquals("chatraw", oldUnit.image.sourceType)
        assertEquals(3, oldUnit.files)
        assertEquals(2_220_000L, oldUnit.bytes)
        assertEquals("旧图片", oldUnit.reason)
        assertEquals(listOf(old.rep, legacy.rep, thumb.rep), units(SourceSort.SIZE_DESC).map { it.image.id })
        assertEquals(listOf(thumb.rep, legacy.rep, old.rep), units(SourceSort.SIZE_ASC).map { it.image.id })

        // 分页源与列表查询给出同样的行（@Embedded 的图片 + 回收站列）
        val page = db.cleanupDao().pagingTrash(RecycleBinSql.trashPage(SourceSort.DATE_DESC, null))
            .load(PagingSource.LoadParams.Refresh(null, 10, false)) as PagingSource.LoadResult.Page
        assertEquals(byTime, page.data)

        // 按原因筛选；原因统计按组
        assertEquals(listOf(old.rep), units(SourceSort.DATE_DESC, listOf("旧图片")).map { it.image.id })
        val reasons = db.cleanupDao().observeTrashReasons(RecycleBinSql.trashReasons()).first().associate { it.reason to (it.count to it.bytes) }
        assertEquals(mapOf("旧图片" to (1 to 2_220_000L), "QQ 缩略图" to (1 to 30_000L), "用户批量删除" to (1 to 500_000L)), reasons)

        // 一组里的各个版本，大的在前
        assertEquals(listOf("chatraw", "chatimg", "chatthumb"), db.cleanupDao().trashUnits(RecycleBinSql.trashMembers(old.group!!)).map { it.image.sourceType })
    }

    @Test
    fun groupCountsAndSectionSelectionAgree() = runBlocking {
        val today = trashGroup("QQ 缩略图", now, "chatthumb" to 30_000L)
        val todayToo = trashGroup("QQ 缩略图", now - 1_000, "chatthumb" to 40_000L)
        val earlier = trashGroup("旧图片", now - 3 * day, "chatimg" to 200_000L, "chatraw" to 2_000_000L)

        val days = buckets(byDate = true, reasons = null)
        assertEquals(mapOf(dayKey(now) to 2, dayKey(now - 3 * day) to 1), days)
        assertEquals(setOf(today.rep, todayToo.rep), ids(null, daySection(now)))
        // 按大小分组用整组合计：2.2 MB 那组在「2 MB」
        val sizes = buckets(byDate = false, reasons = null)
        assertEquals(1, sizes[sizeBucketId(2_200_000L)])
        assertEquals(setOf(earlier.rep), ids(null, sizeBucketSection(sizeBucketId(2_200_000L))))
        // 筛选后计数与全选都只算这一类
        assertEquals(mapOf(dayKey(now) to 2), buckets(byDate = true, reasons = listOf("QQ 缩略图")))
        assertEquals(setOf(today.rep, todayToo.rep), ids(listOf("QQ 缩略图"), null))
    }

    @Test
    fun originalsListByCompressionTimeAndOriginalSize() = runBlocking {
        val small = backup(createdAt = now - day, originalSize = 1_000_000L)
        val large = backup(createdAt = now - 2 * day, originalSize = 5_000_000L)
        // 已经删掉备份的不算
        backup(createdAt = now, originalSize = 9_000_000L, kept = false)

        fun page(sort: SourceSort) = runBlocking {
            (db.cleanupDao().pagingBackups(RecycleBinSql.backupPage(sort)).load(PagingSource.LoadParams.Refresh(null, 10, false))
                as PagingSource.LoadResult.Page).data.map { it.image.id }
        }
        assertEquals(listOf(small, large), page(SourceSort.DATE_DESC))
        assertEquals(listOf(large, small), page(SourceSort.SIZE_DESC))
        assertEquals(setOf(large), db.cleanupDao().binIds(RecycleBinSql.backupIds(TimelineSection.Size(4_000_000L, 6_000_000L))).toSet())
        assertEquals(setOf(small, large), db.cleanupDao().binIds(RecycleBinSql.backupIds()).toSet())
    }

    // ---- 数据 ----

    private data class Group(val rep: Long, val group: String?)

    /** 一次删除的一组文件；返回组里最大的那张的图片 id */
    private suspend fun trashGroup(
        reason: String, at: Long, vararg files: Pair<String, Long>, legacy: Boolean = false, state: String = "ready",
    ): Group {
        val paths = familyPaths(md5(++families))
        val token = if (legacy) null else "group-$families"
        var rep = 0L
        var top = -1L
        files.forEach { (type, size) ->
            val path = when (type) {
                "chatimg" -> paths[0]
                "chatraw" -> paths[1]
                else -> paths[2]
            }
            val id = db.imageDao().insert(imageRow(path, type, size, at - 100 * day, "quarantined"))
            db.cleanupDao().insertQuarantine(
                QuarantineEntity(
                    imageId = id, originalPath = path, quarantinePath = "/data/trash/${families}_$id", thumbnailPath = null,
                    reason = reason, size = size, quarantinedAt = at, expiresAt = at + 30 * day, state = state,
                    groupId = token, fileCount = files.size,
                ),
            )
            if (size > top) {
                top = size
                rep = id
            }
        }
        return Group(rep, token)
    }

    private suspend fun backup(createdAt: Long, originalSize: Long, kept: Boolean = true): Long {
        val path = familyPaths(md5(++families))[1]
        val id = db.imageDao().insert(imageRow(path, "chatraw", originalSize / 3, createdAt - 100 * day, optimized = 1))
        db.originalBackupDao().insert(
            OriginalBackupEntity(
                imageId = id, path = path, backupPath = if (kept) "/data/backup/$id" else null, originalSize = originalSize,
                originalMtime = createdAt - 100 * day, originalWidth = 100, originalHeight = 100, compressedSize = originalSize / 3,
                compressedWidth = 100, compressedHeight = 100, createdAt = createdAt, expiresAt = createdAt + 30 * day, state = "ready",
            ),
        )
        return id
    }

    private suspend fun units(sort: SourceSort, reasons: List<String>? = null): List<TrashUnit> =
        db.cleanupDao().trashUnits(RecycleBinSql.trashPage(sort, reasons))

    private suspend fun buckets(byDate: Boolean, reasons: List<String>?): Map<Long, Int> =
        db.cleanupDao().observeBinBuckets(RecycleBinSql.trashBuckets(byDate, reasons)).first().associate { it.bucketKey to it.count }

    private suspend fun ids(reasons: List<String>?, section: TimelineSection?): Set<Long> =
        db.cleanupDao().binIds(RecycleBinSql.trashIds(reasons, section)).toSet()
}
