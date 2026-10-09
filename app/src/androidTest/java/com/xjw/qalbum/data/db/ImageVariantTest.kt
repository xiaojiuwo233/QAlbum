package com.xjw.qalbum.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xjw.qalbum.qq.QqCachePaths
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 图片家族：触发器维护代表图 / 版本数 / 总大小，回填后的分段重算，解散旧的「同图不同版本」重复组，
 * 以及图库合并显示时的计数。
 */
@RunWith(AndroidJUnit4::class)
class ImageVariantTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private val root = "/storage/emulated/0/Android/data/com.tencent.mobileqq/Tencent/MobileQQ/chatpic"

    /** 同一 MD5 在 chatimg / chatraw / chatthumb / chatthumb_hd 的真实缓存路径 */
    private fun familyPaths(md5: String) = QqCachePaths.relativePaths(md5).map { "$root/$it" }

    private fun open(name: String): SupportSQLiteDatabase {
        helper.createDatabase(name, 17).close()
        return helper.runMigrationsAndValidate(name, 19, true, AppDatabase.MIGRATION_17_18, AppDatabase.MIGRATION_18_19)
    }

    @Test
    fun triggersKeepTheLargestVisibleVersionAsRepresentative() {
        open("variants-triggers").use { db ->
            val (img, raw, thumb, hd) = familyPaths("0123456789abcdef0123456789abcdef")
            insert(db, 1, img, "chatimg", "active", 300)
            insert(db, 2, raw, "chatraw", "active", 2_000)
            insert(db, 3, thumb, "chatthumb", "thumbnail_trash", 20)
            insert(db, 4, hd, "chatthumb", "thumbnail_trash", 60)
            // 原图最大：代表整组（4 个版本、合计 2380），预览图隐藏，缩略图本就不进图库；其余版本的大小是自身大小
            assertRow(db, 2, hidden = 0, count = 4, bytes = 2_380)
            assertRow(db, 1, hidden = 1, count = 0, bytes = 300)
            assertRow(db, 3, hidden = 0, count = 0, bytes = 20)

            // 原图移入回收站：预览图接替代表，版本数与合计随之变化
            db.execSQL("UPDATE images SET status = 'quarantine_pending' WHERE id = 2")
            assertRow(db, 1, hidden = 0, count = 3, bytes = 380)
            assertRow(db, 2, hidden = 0, count = 0, bytes = 2_000)
            // 恢复：原图重新成为代表
            db.execSQL("UPDATE images SET status = 'active' WHERE id = 2")
            assertRow(db, 2, hidden = 0, count = 4, bytes = 2_380)
            assertRow(db, 1, hidden = 1, count = 0, bytes = 300)
            // 预览图被 QQ 重写变大；缩略图被 QQ 清掉
            db.execSQL("UPDATE images SET size = 500 WHERE id = 1")
            db.execSQL("UPDATE images SET status = 'missing' WHERE id = 3")
            assertRow(db, 2, hidden = 0, count = 3, bytes = 2_560)
            // 删除行
            db.execSQL("DELETE FROM images WHERE id = 2")
            assertRow(db, 1, hidden = 0, count = 2, bytes = 560)

            // 非 QQ 命名的图片自成一张，合并显示时的大小就是自身大小
            insert(db, 9, "$root/temp/x.jpg", "temp", "active", 100)
            assertRow(db, 9, hidden = 0, count = 0, bytes = 100)
            db.execSQL("UPDATE images SET size = 150 WHERE id = 9")
            assertRow(db, 9, hidden = 0, count = 0, bytes = 150)
        }
    }

    @Test
    fun backfillRecomputeAndDuplicateCleanup() {
        open("variants-backfill").use { db ->
            val (img, raw, thumb, _) = familyPaths("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
            val (otherImg) = familyPaths("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
            val (thirdImg, thirdRaw) = familyPaths("cccccccccccccccccccccccccccccccc")
            // 升级前入库：没有家族键
            insert(db, 1, img, "chatimg", "active", 300, key = false)
            insert(db, 2, raw, "chatraw", "active", 2_000, key = false)
            insert(db, 3, thumb, "chatthumb", "thumbnail_trash", 20, key = false)
            insert(db, 4, otherImg, "chatimg", "active", 400, key = false)
            insert(db, 5, thirdImg, "chatimg", "active", 100, key = false)
            insert(db, 6, thirdRaw, "chatraw", "active", 900, key = false)
            // 旧版查重：组 8 = 预览图 + 原图 + 另一张真正相似的图；组 7 只是同一张图的预览图与原图
            db.execSQL("UPDATE images SET duplicate_group_id = 8 WHERE id IN (1, 2, 4)")
            db.execSQL("UPDATE images SET duplicate_group_id = 7 WHERE id IN (5, 6)")

            // 回填：撤掉家族索引与触发器 → 写家族键 → 恢复 → 分段重算 → 解散
            val familySchema = "SELECT COUNT(*) FROM sqlite_master WHERE name = 'index_images_variant_group' OR name LIKE 'image_variants_%'"
            ImageVariantSql.suspendForBackfill(db)
            assertEquals(0L, scalar(db, familySchema))
            listOf(1L to img, 2L to raw, 3L to thumb, 4L to otherImg, 5L to thirdImg, 6L to thirdRaw).forEach { (id, path) ->
                db.execSQL("UPDATE images SET variant_group = ? WHERE id = ?", arrayOf<Any>(QqCachePaths.familyKey(path)!!, id))
            }
            // 写键期间的变化不维护：第三张图的原图被移入回收站
            db.execSQL("UPDATE images SET status = 'quarantine_pending' WHERE id = 6")
            assertRow(db, 2, hidden = 0, count = 0, bytes = 2_000)
            ImageVariantSql.resumeAfterBackfill(db)
            assertEquals(4L, scalar(db, familySchema))
            db.execSQL(ImageVariantSql.recomputeRange, arrayOf<Any>(0, 2))
            db.execSQL(ImageVariantSql.recomputeRange, arrayOf<Any>(2, Long.MAX_VALUE))
            assertRow(db, 2, hidden = 0, count = 3, bytes = 2_320)
            assertRow(db, 1, hidden = 1, count = 0, bytes = 300)
            // 重算按当前状态：第三张图只剩预览图一个版本
            assertRow(db, 5, hidden = 0, count = 0, bytes = 100)
            assertRow(db, 6, hidden = 0, count = 0, bytes = 900)
            // 重复执行不再写任何行
            db.execSQL(ImageVariantSql.recomputeRange, arrayOf<Any>(0, Long.MAX_VALUE))
            assertRow(db, 2, hidden = 0, count = 3, bytes = 2_320)

            ImageVariantSql.dissolveFamilyDuplicates.forEach(db::execSQL)
            // 隐藏的预览图退出重复组；组 8 仍有原图与另一张图两张可见图，保留；组 7 只剩一张可见图，解散
            assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM images WHERE id = 1 AND duplicate_group_id IS NOT NULL"))
            assertEquals(2L, scalar(db, "SELECT COUNT(*) FROM images WHERE duplicate_group_id = 8"))
            assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM images WHERE duplicate_group_id = 7"))

            // 图库计数：合并后同一张图只算一次（缩略图本就不计）
            assertEquals(4L, scalar(db, "SELECT COUNT(*) FROM images WHERE status = 'active' AND source_type <> 'chatthumb'"))
            assertEquals(3L, scalar(db, "SELECT COUNT(*) FROM images WHERE status = 'active' AND source_type <> 'chatthumb' AND variant_hidden = 0"))
        }
    }

    private fun insert(db: SupportSQLiteDatabase, id: Long, path: String, sourceType: String, status: String, size: Long, key: Boolean = true) {
        val group = if (key) QqCachePaths.familyKey(path)?.toString() ?: "NULL" else "NULL"
        db.execSQL(
            """INSERT INTO images(id, path, filename, parent_dir, source_type, cache_key, variant_group, is_variant_primary,
                size, mtime, first_seen, last_seen, mime, width, height, is_animated, is_corrupt, is_blank,
                hash_done, ocr_done, ai_done, embedding_done, info_score, delete_score, is_duplicate_best, status, is_protected)
            VALUES($id, '$path', '${path.substringAfterLast('/')}', '${path.substringBeforeLast('/')}', '$sourceType', 'k$id', $group, 0,
                $size, 1000, 1000, 1000, 'image/jpeg', 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, '$status', 0)""",
        )
    }

    private fun assertRow(db: SupportSQLiteDatabase, id: Long, hidden: Long, count: Long, bytes: Long) {
        db.query("SELECT variant_hidden, variant_count, variant_bytes FROM images WHERE id = $id").use { c ->
            c.moveToFirst()
            assertEquals("image $id", listOf(hidden, count, bytes), (0 until 3).map { c.getLong(it) })
        }
    }

    private fun scalar(db: SupportSQLiteDatabase, sql: String): Long = db.query(sql).use { c -> c.moveToFirst(); c.getLong(0) }
}
