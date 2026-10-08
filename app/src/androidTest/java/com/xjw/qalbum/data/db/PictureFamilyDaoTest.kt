package com.xjw.qalbum.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.qq.QqCachePaths
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** 图片家族相关的 DAO 查询：合并显示的时间线计数、家族成员、按家族结算大小、只剩一张可见图的重复组 */
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
            assertEquals(2_320L + 400L, dao.sumStackSizeByIds(listOf(rawId, otherId)))

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
