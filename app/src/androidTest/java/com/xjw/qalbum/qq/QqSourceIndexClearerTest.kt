package com.xjw.qalbum.qq

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xjw.qalbum.ACCOUNT
import com.xjw.qalbum.TestDatabase
import com.xjw.qalbum.addOrigins
import com.xjw.qalbum.data.db.dao.QqReadBaseline
import com.xjw.qalbum.data.db.entity.QqAutoArchiveEntity
import com.xjw.qalbum.data.db.entity.QqOriginFamilyEntity
import com.xjw.qalbum.data.db.entity.QqOriginPathEntity
import com.xjw.qalbum.data.db.entity.QqReadMarkEntity
import com.xjw.qalbum.exec
import com.xjw.qalbum.familyPaths
import com.xjw.qalbum.imageRow
import com.xjw.qalbum.int
import com.xjw.qalbum.long
import com.xjw.qalbum.md5
import com.xjw.qalbum.qqOrigin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QqSourceIndexClearerTest {
    @get:Rule val database = TestDatabase("source-clear-test.db")
    private val db get() = database.db
    private val photo = md5(1)

    @Test
    fun clearsChildrenInBatchesAndKeepsImagesAndArchiveRules() = runBlocking {
        val image = seed()
        val before = db.imageDao().getById(image)
        // 父表必须等子表清完再删，不能退回整库级联删除。
        db.exec("""CREATE TRIGGER reject_origin_cascade BEFORE DELETE ON qq_origins
            WHEN EXISTS (SELECT 1 FROM qq_origin_paths WHERE origin_id = OLD.id)
              OR EXISTS (SELECT 1 FROM qq_origin_families WHERE origin_id = OLD.id)
              OR EXISTS (SELECT 1 FROM image_qq_origins WHERE origin_id = OLD.id)
            BEGIN SELECT RAISE(ABORT, 'origin children remain'); END""")
        db.exec("""CREATE TRIGGER reject_account_cascade BEFORE DELETE ON qq_source_accounts
            WHEN EXISTS (SELECT 1 FROM qq_origins WHERE account_key = OLD.account_key)
              OR EXISTS (SELECT 1 FROM qq_source_facets WHERE account_key = OLD.account_key)
              OR EXISTS (SELECT 1 FROM qq_read_marks WHERE account_key = OLD.account_key)
            BEGIN SELECT RAISE(ABORT, 'account children remain'); END""")
        val counts = mutableListOf<Pair<Long, Long>>()
        val labels = mutableListOf<String>()
        QqSourceIndexClearer(db).clear { label, done, total ->
            assertFalse("Progress must run between committed batches", db.inTransaction())
            labels += label
            if (label != "释放空间") counts += done to total
        }

        assertTrue(counts.first().second > 30_000)
        assertEquals(0L, counts.first().first)
        assertEquals(counts.last().second, counts.last().first)
        assertTrue(counts.zipWithNext().all { (a, b) -> b.first - a.first in 0L..10_000L })
        // 删完把空间还给系统，之后回到自动回收
        assertEquals("释放空间", labels.last())
        assertEquals(1, db.int("PRAGMA auto_vacuum"))
        assertEquals(0, db.int("PRAGMA freelist_count"))
        assertEmptyIndex()
        assertEquals(before, db.imageDao().getById(image))
        assertEquals(1, db.int("SELECT COUNT(*) FROM qq_auto_archive"))

        // 清空后重新读取的来源能重新关联到已有图片，旧水位不能使它被跳过。
        db.addOrigins(qqOrigin("next", photo))
        db.qqSourceDao().linkAfter(QqReadBaseline.START)
        assertTrue(QqSourceStatsBuilder(db).applyNew(QqReadBaseline.START, emptySet()))
        assertEquals(1, db.int("SELECT COUNT(*) FROM image_qq_origins WHERE image_id = $image"))
        assertEquals(1L, db.long("SELECT image_count FROM qq_source_cache WHERE id = 1"))
    }

    @Test
    fun interruptedClearCanResumeWithoutReusingReadMarks() = runBlocking {
        val image = seed()
        val paths = db.int("SELECT COUNT(*) FROM qq_origin_paths")
        val error = runCatching {
            QqSourceIndexClearer(db).clear { label, _, _ ->
                if (label == "清除来源路径" && db.int("SELECT COUNT(*) FROM qq_origin_paths") < paths) {
                    throw CancellationException("Interrupted between batches")
                }
            }
        }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertEquals(0, db.int("SELECT COUNT(*) FROM qq_read_marks"))
        assertEquals(1L, db.long("SELECT dirty FROM qq_source_cache WHERE id = 1"))
        assertTrue(db.int("SELECT COUNT(*) FROM qq_origins") > 0)
        assertEquals(image, db.imageDao().getById(image)!!.id)
        assertValidForeignKeys()

        QqSourceIndexClearer(db).clear { _, _, _ -> }
        assertEmptyIndex()
    }

    private suspend fun seed(): Long {
        val sources = db.qqSourceDao()
        val origins = (1..6_001).map { qqOrigin("o$it", photo, "g${it % 8}", "s${it % 13}") }
        origins.chunked(500).forEach { db.addOrigins(*it.toTypedArray()) }
        origins.chunked(500).forEach { batch ->
            sources.insertPaths(batch.flatMap { origin -> familyPaths(photo).map { QqOriginPathEntity(it, origin.id) } })
            sources.insertFamilies(batch.map { QqOriginFamilyEntity(123L, it.id) })
        }
        val image = db.imageDao().insert(imageRow(familyPaths(photo)[1], "chatraw", archived = true))
        QqSourceStatsBuilder(db).rebuild { }
        sources.setCompleteReadAt(1_000)
        sources.putReadMarks(listOf(QqReadMarkEntity(ACCOUNT, "group_msg_table", 6_001, 1_000)))
        sources.addAutoArchive(QqAutoArchiveEntity("group", "sender", "Keep rule", 1_000))
        return image
    }

    private fun assertEmptyIndex() {
        listOf("qq_read_marks", "qq_image_contexts", "qq_source_members", "image_qq_origins", "qq_origin_paths",
            "qq_origin_families", "qq_origins", "qq_source_facets", "qq_source_accounts").forEach { table ->
            assertEquals(table, 0, db.int("SELECT COUNT(*) FROM $table"))
        }
        db.query("SELECT dirty, image_count, image_bytes, thumb_count, thumb_bytes, complete_read_at FROM qq_source_cache WHERE id = 1", null).use {
            assertTrue(it.moveToFirst())
            (0 until it.columnCount).forEach { column -> assertEquals(0L, it.getLong(column)) }
        }
        assertValidForeignKeys()
    }

    private fun assertValidForeignKeys() {
        db.query("PRAGMA foreign_key_check", null).use { assertFalse(it.moveToFirst()) }
    }
}
