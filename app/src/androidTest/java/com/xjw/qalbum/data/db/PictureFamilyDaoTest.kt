package com.xjw.qalbum.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xjw.qalbum.data.db.dao.QqContent
import com.xjw.qalbum.data.db.dao.QqScopeIds
import com.xjw.qalbum.data.db.dao.QqScopeOrder
import com.xjw.qalbum.data.db.dao.QqScopeSql
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.domain.model.TimelineSection
import com.xjw.qalbum.qq.QqCachePaths
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

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
            // 旧版查重把预览图与另一张图分到一组；之后才下载了原图，原图成为代表、预览图被隐藏
            sql.execSQL("UPDATE images SET duplicate_group_id = 5 WHERE id IN ($imgId, $otherId)")
            assertEquals(listOf(5L), dao.duplicateGroupsWithPairs())
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

            // 组 5 只剩另一张图可见，不再算重复
            assertEquals(emptyList<Long>(), dao.duplicateGroupsWithPairs())
            assertEquals(emptyList<Long>(), dao.duplicateGroupsWithPairsAmong(listOf(5L)))
            // 重新查重后原图与另一张图成组
            sql.execSQL("UPDATE images SET duplicate_group_id = 5 WHERE id = $rawId")
            assertEquals(listOf(5L), dao.duplicateGroupsWithPairsAmong(listOf(5L, 6L)))
        } finally {
            db.close()
        }
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
