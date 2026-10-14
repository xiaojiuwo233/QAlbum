package com.xjw.qalbum.service.cleanup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xjw.qalbum.data.db.AppDatabase
import com.xjw.qalbum.data.db.dao.RuleStats
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.data.db.entity.computeDateBucket
import com.xjw.qalbum.qq.QqCachePaths
import com.xjw.qalbum.service.cleanup.rules.CleanupRule
import com.xjw.qalbum.service.cleanup.rules.RuleScope
import com.xjw.qalbum.service.cleanup.rules.RuleSql
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 各条清理规则的 SQL 在真实表结构（含家族触发器）上命中哪些行、怎么算大小 */
@RunWith(AndroidJUnit4::class)
class CleanupRuleQueryTest {

    private val root = "/storage/emulated/0/Android/data/com.tencent.mobileqq/Tencent/MobileQQ/chatpic"
    private val now = 1_760_000_000_000L
    private val day = 24L * 60 * 60 * 1000
    private val mb = 1_048_576L
    private var families = 0
    private lateinit var db: AppDatabase

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .addCallback(AppDatabase.creationCallback)
            .build()
    }

    @After
    fun close() = db.close()

    @Test
    fun thumbnailsOnlyMatchOldUnarchivedThumbs() = runBlocking {
        val (_, _, thumb, hd) = family()
        val old = insert(thumb, "chatthumb", "thumbnail_trash", 20_000, age = 40)
        val recent = insert(hd, "chatthumb", "thumbnail_trash", 30_000, age = 10)
        insert(family()[2], "chatthumb", "thumbnail_trash", 50_000, age = 40, archived = true)

        assertEquals(setOf(old), ids(CleanupRule.Thumbnails, 30))
        assertEquals(setOf(old, recent), ids(CleanupRule.Thumbnails, 0))
        assertEquals(RuleStats(1, 20_000), stats(CleanupRule.Thumbnails, 30))
    }

    @Test
    fun slimOriginalsNeedAPreviewAndSkipSmallAnimatedRecentAndArchived() = runBlocking {
        suspend fun pair(rawSize: Long, age: Int = 100, preview: Boolean = true, animated: Boolean = false, archived: Boolean = false): Long {
            val (img, raw) = family()
            if (preview) insert(img, "chatimg", "active", 200_000, age = age, archived = archived)
            return insert(raw, "chatraw", "active", rawSize, age = age, animated = animated,
                mime = if (animated) "image/gif" else "image/jpeg", archived = archived)
        }
        val hit = pair(2 * mb)
        pair(2 * mb, preview = false)
        pair(mb - 1)
        pair(2 * mb, animated = true)
        pair(2 * mb, age = 30)
        pair(2 * mb, archived = true)

        assertEquals(setOf(hit), ids(CleanupRule.SlimOriginals, 90))
        // 只删原图：大小按原图自身算，不是整组合计
        assertEquals(RuleStats(1, 2 * mb), stats(CleanupRule.SlimOriginals, 90))
    }

    @Test
    fun compressPicksUnprocessedJpegAndPngOriginalsAboveTheThreshold() = runBlocking {
        val large = insert(family()[1], "chatraw", "active", 2 * mb)
        insert(family()[1], "chatraw", "active", 2 * mb, optimized = 1)
        insert(family()[1], "chatraw", "active", 2 * mb, optimized = 2)
        val png = insert(family()[1], "chatraw", "active", 2 * mb, mime = "image/png")
        insert(family()[1], "chatraw", "active", 2 * mb, mime = "image/webp")
        insert(family()[0], "chatimg", "active", 2 * mb)
        val small = insert(family()[1], "chatraw", "active", 1_100_000)
        val tiny = insert(family()[1], "chatraw", "active", 400_000)

        assertEquals(setOf(large, png), ids(CleanupRule.CompressOriginals, 1536))
        assertEquals(setOf(large, png, small), ids(CleanupRule.CompressOriginals, 1024))
        assertEquals(setOf(large, png, small, tiny), ids(CleanupRule.CompressOriginals, 300))
    }

    @Test
    fun stickersCountEachPictureOnceAndSumAllItsVersions() = runBlocking {
        val sticker = insert(family()[0], "chatimg", "active", 50_000, width = 240, height = 240)
        val gif = insert(family()[0], "chatimg", "active", 3 * mb, width = 800, height = 600, animated = true, mime = "image/gif")
        insert(family()[0], "chatimg", "active", 50_000, width = 512, height = 400)
        insert(family()[0], "chatimg", "active", 300_000, width = 300, height = 300)
        insert(family()[0], "chatimg", "active", 50_000, width = 0, height = 0)
        insert(family()[0], "chatimg", "active", 50_000, width = 240, height = 240, archived = true)
        // 同一张图的预览图与原图：只算代表图（原图）一次，大小为两个版本合计
        val (img, raw) = family()
        insert(img, "chatimg", "active", 40_000, width = 200, height = 200)
        val rep = insert(raw, "chatraw", "active", 150_000, width = 400, height = 400)

        assertEquals(setOf(sticker, gif, rep), ids(CleanupRule.Stickers))
        assertEquals(RuleStats(3, 50_000 + 3 * mb + 190_000), stats(CleanupRule.Stickers))
    }

    @Test
    fun oldPicturesAndTimeRangesWorkOnWholePictures() = runBlocking {
        val (img, raw, thumb) = family()
        insert(img, "chatimg", "active", 300_000, age = 3 * 365)
        val oldRep = insert(raw, "chatraw", "active", 2 * mb, age = 3 * 365)
        insert(thumb, "chatthumb", "thumbnail_trash", 20_000, age = 3 * 365)
        val lastYear = insert(family()[0], "chatimg", "active", 100_000, age = 500)
        insert(family()[0], "chatimg", "active", 100_000, age = 300)

        assertEquals(setOf(oldRep), ids(CleanupRule.OldPictures, 2))
        assertEquals(setOf(oldRep, lastYear), ids(CleanupRule.OldPictures, 1))
        // 整张图：预览图被原图代表，大小含预览图与缩略图
        assertEquals(RuleStats(1, 2 * mb + 300_000 + 20_000), stats(CleanupRule.OldPictures, 2))

        val range = CleanupRule.TimeRange(now - 4 * 365 * day, now - 2 * 365 * day, "那段时间")
        assertEquals(setOf(oldRep), ids(range))
        val days = db.cleanupRuleDao().observeDays(RuleSql.days(range.where(0, now), range.scope)).first()
        assertEquals(computeDateBucket(now - 3 * 365 * day), days.single().bucketKey)
        assertEquals(2 * mb + 320_000, days.single().bytes)
    }

    @Test
    fun pagingByIdAndDayRangeSelection() = runBlocking {
        val thumbs = (1..5).map { insert(family()[2], "chatthumb", "thumbnail_trash", 10_000L * it, age = 40 + it) }
        val part = CleanupRule.Thumbnails.where(30, now)
        val dao = db.cleanupRuleDao()
        val first = dao.ids(RuleSql.idsAfter(part, 0, 2))
        val second = dao.ids(RuleSql.idsAfter(part, first.last(), 2))
        val third = dao.ids(RuleSql.idsAfter(part, second.last(), 2))
        assertEquals(thumbs, first + second + third)

        // 时间线顺序：新的在前
        val page = db.query(RuleSql.page(part, RuleScope.FILE)).use { c -> buildList { while (c.moveToNext()) add(c.getLong(c.getColumnIndexOrThrow("id"))) } }
        assertEquals(thumbs, page)
        val dayStart = now - 43 * day
        assertEquals(listOf(thumbs[2]), dao.ids(RuleSql.idsBetween(part, dayStart, dayStart + 1)))
        assertEquals(5, dao.observeBuckets(RuleSql.buckets(part)).first().sumOf { it.count })
    }

    private suspend fun ids(rule: CleanupRule, option: Int = rule.defaultOption): Set<Long> =
        db.cleanupRuleDao().ids(RuleSql.idsAfter(rule.where(option, now), 0, 1_000)).toSet()

    private suspend fun stats(rule: CleanupRule, option: Int = rule.defaultOption): RuleStats =
        db.cleanupRuleDao().stats(RuleSql.stats(rule.where(option, now), rule.scope))

    /** 一张新图的各版本路径：chatimg、chatraw、chatthumb、chatthumb _hd */
    private fun family(): List<String> =
        QqCachePaths.relativePaths("%032x".format(++families)).map { "$root/$it" }

    private suspend fun insert(
        path: String,
        sourceType: String,
        status: String,
        size: Long,
        age: Int = 1,
        width: Int = 1000,
        height: Int = 800,
        animated: Boolean = false,
        mime: String = "image/jpeg",
        archived: Boolean = false,
        optimized: Int = 0,
    ): Long {
        val mtime = now - age * day
        return db.imageDao().insert(
            ImageEntity(
                path = path,
                filename = path.substringAfterLast('/'),
                parentDir = path.substringBeforeLast('/'),
                sourceType = sourceType,
                cacheKey = path.substringAfterLast('/').removePrefix("Cache_"),
                variantGroup = QqCachePaths.familyKey(path),
                size = size,
                mtime = mtime,
                firstSeen = mtime,
                lastSeen = mtime,
                mime = mime,
                width = width,
                height = height,
                isAnimated = animated,
                status = status,
                isArchived = archived,
                optimized = optimized,
            ),
        )
    }
}
