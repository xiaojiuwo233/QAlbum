package com.xjw.qalbum.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xjw.qalbum.data.db.dao.QqContent
import com.xjw.qalbum.data.db.dao.QqScopeIds
import com.xjw.qalbum.data.db.dao.QqScopeOrder
import com.xjw.qalbum.data.db.dao.QqScopeSql
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 来源统计：分段重建的结果、增量触发器（删除 / 恢复 / 改大小 / 缩略图消失 / 新扫描入库）
 * 与整体重算保持一致，以及浏览范围 SQL 在设备 SQLite 上的结果。
 */
@RunWith(AndroidJUnit4::class)
class QqSourceStatsTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun rebuildAndIncrementalTriggersStayConsistent() {
        val name = "qq-source-stats"
        helper.createDatabase(name, 16).close()
        helper.runMigrationsAndValidate(name, 17, true, AppDatabase.MIGRATION_16_17).use { db ->
            AppDatabase.createQqSourceTriggersV13(db)
            seed(db)
            rebuild(db)

            val group = facetId(db, 0, "g1")
            val private = facetId(db, 0, "p1")
            val alice = facetId(db, 1, "s1")
            val bob = facetId(db, 1, "s2")
            // 群 g1：图片 1、3（300B），缩略图 2；私聊 p1 与群共用图片 1 与缩略图 2
            assertFacet(db, group, images = 2, imageBytes = 300, thumbs = 1, thumbBytes = 10)
            assertFacet(db, private, images = 1, imageBytes = 100, thumbs = 1, thumbBytes = 10)
            assertFacet(db, alice, images = 1, imageBytes = 100, thumbs = 1, thumbBytes = 10)
            assertFacet(db, bob, images = 1, imageBytes = 200, thumbs = 0, thumbBytes = 0)
            assertEquals(listOf(2L, 300L, 1L, 10L, 0L), cache(db))
            assertEquals("名片1", scalarString(db, "SELECT card FROM qq_source_members WHERE conversation_id=$group AND sender_id=$alice"))

            // 移入回收站：先 pending（减掉），再落定（不变）
            db.execSQL("UPDATE images SET status='quarantine_pending' WHERE id=1")
            db.execSQL("UPDATE images SET status='quarantined', path='qalbum-internal://quarantine/1' WHERE id=1")
            assertFacet(db, group, images = 1, imageBytes = 200, thumbs = 1, thumbBytes = 10)
            assertFacet(db, private, images = 0, imageBytes = 0, thumbs = 1, thumbBytes = 10)
            assertEquals(listOf(1L, 200L, 1L, 10L, 0L), cache(db))
            assertConsistent(db)

            // 恢复：占回原路径，再转 active
            db.execSQL("UPDATE images SET status='restore_pending', path='/c/chatimg/m1' WHERE id=1")
            db.execSQL("UPDATE images SET status='active' WHERE id=1")
            assertFacet(db, group, images = 2, imageBytes = 300, thumbs = 1, thumbBytes = 10)
            assertConsistent(db)

            // 文件被 QQ 重写变大、缩略图被 QQ 清掉
            db.execSQL("UPDATE images SET size=250 WHERE id=3")
            db.execSQL("UPDATE images SET status='missing' WHERE id=2")
            assertFacet(db, group, images = 2, imageBytes = 350, thumbs = 0, thumbBytes = 0)
            assertConsistent(db)

            // 新扫描入库：m2 的缩略图出现，按路径关联到群 g1 / 发送人 s2
            insertImage(db, 5, "/c/chatthumb/m2", "chatthumb", "thumbnail_trash", 20)
            assertFacet(db, group, images = 2, imageBytes = 350, thumbs = 1, thumbBytes = 20)
            assertFacet(db, bob, images = 1, imageBytes = 250, thumbs = 1, thumbBytes = 20)
            assertEquals(listOf(2L, 350L, 1L, 20L, 0L), cache(db))
            assertConsistent(db)

            // 浏览范围：群里的图片按时间倒序、共用统计、可清理 id
            val groupScope = QqScopeIds(conversationId = group)
            assertEquals(listOf(3L, 1L), ids(db, QqScopeSql.page(groupScope, QqContent.IMAGES, QqScopeOrder.DATE_DESC, stacked = false)))
            assertEquals(listOf(5L), ids(db, QqScopeSql.page(groupScope, QqContent.THUMBNAILS, QqScopeOrder.SIZE_DESC, stacked = false)))
            assertEquals(listOf(1L), ids(db, QqScopeSql.page(QqScopeIds(group, alice), QqContent.IMAGES, QqScopeOrder.DATE_ASC, stacked = false)))
            assertEquals(listOf(3L, 1L), ids(db, QqScopeSql.page(QqScopeIds(), QqContent.IMAGES, QqScopeOrder.SIZE_DESC, stacked = false)))
            db.query(QqScopeSql.counts(groupScope, setOf(QqContent.IMAGES), includeShared = false)).use { c ->
                c.moveToFirst()
                // total, totalBytes, shared, protected, removable, removableBytes
                assertEquals(listOf(2L, 350L, 1L, 0L, 1L, 250L), (0 until 6).map { c.getLong(it) })
            }
            assertEquals(listOf(3L), ids(db, QqScopeSql.removableIds(groupScope, setOf(QqContent.IMAGES), false, 0, 99, 10)))
            assertEquals(listOf(1L, 3L, 5L), ids(db, QqScopeSql.removableIds(groupScope, QqContent.entries.toSet(), true, 0, 99, 10)))
            db.query(QqScopeSql.stats(QqScopeIds(senderId = bob), QqContent.THUMBNAILS)).use { c ->
                c.moveToFirst()
                assertEquals(1L, c.getLong(0))
                assertEquals(20L, c.getLong(1))
            }
        }
    }

    private fun seed(db: SupportSQLiteDatabase) {
        db.execSQL("INSERT INTO qq_source_accounts VALUES('a','账号',1,3)")
        db.execSQL("INSERT INTO qq_origins VALUES('o1','a','g1','摄影群',2,'s1','小明','名片1',1000,'m1')")
        db.execSQL("INSERT INTO qq_origins VALUES('o2','a','g1','摄影群',2,'s2','小红','',2000,'m2')")
        db.execSQL("INSERT INTO qq_origins VALUES('o3','a','p1','小明',1,'s1','小明','',3000,'m1')")
        listOf("o1" to "m1", "o2" to "m2", "o3" to "m1").forEach { (origin, md5) ->
            db.execSQL("INSERT INTO qq_origin_paths VALUES('/c/chatimg/$md5','$origin')")
            db.execSQL("INSERT INTO qq_origin_paths VALUES('/c/chatthumb/$md5','$origin')")
        }
        insertImage(db, 1, "/c/chatimg/m1", "chatimg", "active", 100)
        insertImage(db, 2, "/c/chatthumb/m1", "chatthumb", "thumbnail_trash", 10)
        insertImage(db, 3, "/c/chatimg/m2", "chatimg", "active", 200)
        insertImage(db, 4, "/c/temp/unlinked", "temp", "active", 50)
    }

    /** 与仓库的分段重建相同的三步 */
    private fun rebuild(db: SupportSQLiteDatabase) {
        QqSourceStatsSql.rebuildNames.forEach(db::execSQL)
        QqSourceStatsSql.contextsInRange(db, 0, 2)
        QqSourceStatsSql.contextsInRange(db, 2, Long.MAX_VALUE)
        QqSourceStatsSql.recount.forEach(db::execSQL)
    }

    private fun insertImage(db: SupportSQLiteDatabase, id: Long, path: String, sourceType: String, status: String, size: Long) {
        db.execSQL(
            """INSERT INTO images(id, path, filename, parent_dir, source_type, cache_key, is_variant_primary,
                size, mtime, first_seen, last_seen, mime, width, height, is_animated, is_corrupt, is_blank,
                hash_done, ocr_done, ai_done, embedding_done, info_score, delete_score, is_duplicate_best, status, is_protected)
            VALUES($id, '$path', 'f$id', '/c', '$sourceType', 'k$id', 0, $size, 1000, 1000, 1000, 'image/jpeg', 1, 1, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, '$status', 0)""",
        )
        // 导入后的关联（扫描入库时由 link_qq_image_origin 触发器完成）
        db.execSQL("INSERT OR IGNORE INTO image_qq_origins(image_id, origin_id) SELECT $id, origin_id FROM qq_origin_paths WHERE path = '$path'")
    }

    private fun facetId(db: SupportSQLiteDatabase, kind: Int, key: String): Long =
        scalarLong(db, "SELECT id FROM qq_source_facets WHERE kind=$kind AND `key`='$key'")

    private fun assertFacet(db: SupportSQLiteDatabase, id: Long, images: Long, imageBytes: Long, thumbs: Long, thumbBytes: Long) {
        db.query("SELECT image_count, image_bytes, thumb_count, thumb_bytes FROM qq_source_facets WHERE id=$id").use { c ->
            c.moveToFirst()
            assertEquals(listOf(images, imageBytes, thumbs, thumbBytes), (0 until 4).map { c.getLong(it) })
        }
    }

    private fun cache(db: SupportSQLiteDatabase): List<Long> =
        db.query("SELECT image_count, image_bytes, thumb_count, thumb_bytes, dirty FROM qq_source_cache WHERE id=1").use { c ->
            c.moveToFirst()
            (0 until 5).map { c.getLong(it) }
        }

    /** 增量维护后的统计与按当前状态整体重算完全一致 */
    private fun assertConsistent(db: SupportSQLiteDatabase) {
        val stored = db.query("SELECT id, image_count, image_bytes, thumb_count, thumb_bytes FROM qq_source_facets ORDER BY id").use { c ->
            buildList { while (c.moveToNext()) add((0 until 5).map { c.getLong(it) }) }
        }
        val storedCache = cache(db)
        QqSourceStatsSql.recount.forEach(db::execSQL)
        val recounted = db.query("SELECT id, image_count, image_bytes, thumb_count, thumb_bytes FROM qq_source_facets ORDER BY id").use { c ->
            buildList { while (c.moveToNext()) add((0 until 5).map { c.getLong(it) }) }
        }
        assertEquals(recounted, stored)
        assertEquals(cache(db), storedCache)
    }

    private fun ids(db: SupportSQLiteDatabase, query: SupportSQLiteQuery): List<Long> =
        db.query(query).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }

    private fun scalarLong(db: SupportSQLiteDatabase, sql: String): Long =
        db.query(sql).use { c -> c.moveToFirst(); c.getLong(0) }

    private fun scalarString(db: SupportSQLiteDatabase, sql: String): String =
        db.query(sql).use { c -> c.moveToFirst(); c.getString(0) }
}
