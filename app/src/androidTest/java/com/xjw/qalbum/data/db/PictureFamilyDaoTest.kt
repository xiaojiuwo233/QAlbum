package com.xjw.qalbum.data.db

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xjw.qalbum.data.db.dao.QqContent
import com.xjw.qalbum.data.db.dao.QqScopeIds
import com.xjw.qalbum.data.db.dao.QqScopeOrder
import com.xjw.qalbum.data.db.dao.QqScopeSql
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.data.db.entity.QqOriginEntity
import com.xjw.qalbum.data.db.entity.QqSourceAccountEntity
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.domain.model.TimelineSection
import com.xjw.qalbum.qq.QqCachePaths
import com.xjw.qalbum.qq.QqSourceStatsBuilder
import com.xjw.qalbum.service.indexer.PictureVariantIndexer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * 图片家族相关的 DAO 查询：合并显示的时间线计数、家族成员、按整组合计的大小排序与分组、点标题全选的范围、
 * 删除确认用的家族大小，以及只剩一张可见图的重复组。
 */
@RunWith(AndroidJUnit4::class)
class PictureFamilyDaoTest {

    private val root = "/storage/emulated/0/Android/data/com.tencent.mobileqq/Tencent/MobileQQ/chatpic"

    @Test
    fun stackedQueriesFollowTheFamilyRepresentative() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        try {
            val sql = db.openHelper.writableDatabase
            ImageVariantSql.createTriggers(sql)
            val dao = db.imageDao()
            val (img, raw, thumb) = QqCachePaths.relativePaths("dddddddddddddddddddddddddddddddd").map { "$root/$it" }
            val (other) = QqCachePaths.relativePaths("eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee").map { "$root/$it" }
            val imgId = dao.insert(image(img, "chatimg", "active", 300))
            val thumbId = dao.insert(image(thumb, "chatthumb", "thumbnail_trash", 20))
            val otherId = dao.insert(image(other, "chatimg", "active", 400))
            // 之后才下载了原图：原图成为代表、预览图被隐藏
            val rawId = dao.insert(image(raw, "chatraw", "active", 2_000))

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
            val row = dao.deleteFactRows(listOf(rawId)).single()
            assertEquals(family, row.variantGroup)
            assertEquals(2_320L, dao.familyMemberSizes(listOf(family)).sumOf { it.size })

            // 来源页：同样只留代表图，大小分组按整组合计
            listOf(imgId, rawId, thumbId, otherId).forEach { id ->
                sql.execSQL("INSERT INTO qq_image_contexts(image_id, conversation_id, sender_id) VALUES($id, 1, 2)")
            }
            val sources = db.qqSourceDao()
            val all = QqScopeIds()
            assertEquals(setOf(rawId, otherId), sources.queryIds(QqScopeSql.ids(all, QqContent.IMAGES, stacked = true)).toSet())
            assertEquals(setOf(rawId, imgId, otherId), sources.queryIds(QqScopeSql.ids(all, QqContent.IMAGES, stacked = false)).toSet())
            assertEquals(listOf(rawId, otherId), sources.queryIds(QqScopeSql.page(all, QqContent.IMAGES, QqScopeOrder.SIZE_DESC, stacked = true)))
            assertEquals(
                listOf(rawId),
                sources.queryIds(QqScopeSql.sectionIds(all, QqContent.IMAGES, stacked = true, TimelineSection.Size(2_100, 3_000))),
            )

        } finally {
            db.close()
        }
    }

    @Test
    fun upgradingV2RestacksJpegsAndRetainsSources() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val storeFile = File(context.cacheDir, "jpg-variants-${UUID.randomUUID()}.preferences_pb")
        val store = PreferenceDataStoreFactory.create(scope = scope) { storeFile }
        val settings = SettingsRepository(context, scope, store)
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).addCallback(AppDatabase.creationCallback).build()
        try {
            val paths = seedSources(db)
            val (img, raw, thumb) = paths
            val dao = db.imageDao()
            dao.insert(image(img, "chatimg", "active", 300))
            dao.insert(image(raw, "chatraw", "active", 2_000))
            dao.insert(image(thumb, "chatthumb", "thumbnail_trash", 20))
            val jpgId = dao.insert(image("$raw.jpg", "chatraw", "active", 3_000).copy(
                variantGroup = null, isArchived = true,
            ))
            QqSourceStatsBuilder(db).rebuild {}
            assertEquals(2, dao.countActiveTimeline(stacked = true))
            settings.setVariantIndexVersion(2)

            val indexer = PictureVariantIndexer(db, settings)
            indexer.ensureIndexed()
            assertEquals(4, settings.variantIndexVersion.first())
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
            // 同一版本再次启动不回填、不触发来源重建。
            indexer.ensureIndexed()
            assertFalse(sources.observeSummary().first()!!.dirty)
        } finally {
            db.close()
            scope.cancel()
            storeFile.delete()
        }
    }

    @Test
    fun newJpegIsLinkedAndCountedIncrementally() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .addCallback(AppDatabase.creationCallback).build()
        try {
            val raw = seedSources(db)[1]
            QqSourceStatsBuilder(db).rebuild {}
            val sources = db.qqSourceDao()
            // 别名与同名缓存同一家族：入库时按家族键关联、计入统计
            val jpgId = db.imageDao().insert(image("$raw.jpg", "chatraw", "active", 1_000))
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
        } finally {
            db.close()
        }
    }

    private suspend fun seedSources(db: AppDatabase): List<String> {
        val md5 = "dddddddddddddddddddddddddddddddd"
        val paths = QqCachePaths.relativePaths(md5).map { "$root/$it" }
        val sources = db.qqSourceDao()
        sources.putAccount(QqSourceAccountEntity("account", "Account", 1_000, 2))
        val family = QqCachePaths.md5Family(md5)
        sources.insertOrigins(listOf(
            QqOriginEntity("direct", "account", "group", "Photography", 2, "sender", "Alice", "", 1_000, md5, family = family),
            QqOriginEntity("forward", "account", "forwarded", "Forwarded records", 3, "forwarder", "Forwarder", "", 2_000, md5, family = family),
        ))
        return paths
    }

    private fun image(path: String, sourceType: String, status: String, size: Long): ImageEntity = ImageEntity(
        path = path,
        filename = path.substringAfterLast('/'),
        parentDir = path.substringBeforeLast('/'),
        sourceType = sourceType,
        cacheKey = path.substringAfterLast('/').removePrefix("Cache_"),
        variantGroup = QqCachePaths.familyKey(path),
        size = size,
        mtime = 1_000,
        firstSeen = 1_000,
        lastSeen = 1_000,
        mime = "image/jpeg",
        width = 100,
        height = 100,
        status = status,
    )
}
