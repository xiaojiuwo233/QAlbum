package com.xjw.qalbum.data.db

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xjw.qalbum.CHATPIC
import com.xjw.qalbum.TestDatabase
import com.xjw.qalbum.addOrigins
import com.xjw.qalbum.appContext
import com.xjw.qalbum.data.db.entity.QqOriginPathEntity
import com.xjw.qalbum.familyPaths
import com.xjw.qalbum.imageRow
import com.xjw.qalbum.int
import com.xjw.qalbum.long
import com.xjw.qalbum.md5
import com.xjw.qalbum.qq.QqCachePaths
import com.xjw.qalbum.qqOrigin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 精简数据库：v27 以前存下的路径中能由家族键推出的删掉（同一张图其他 MD5 的家族另记、推不出的留下），关联不变；
 * 空出来的页逐步还给系统，文件变小，之后切回自动回收。
 */
@RunWith(AndroidJUnit4::class)
class DatabaseSlimmerTest {
    private val dbName = "slimmer-test.db"
    /** 要看文件大小，建在磁盘上 */
    @get:Rule val database = TestDatabase(dbName)
    private val db get() = database.db

    @Test
    fun legacyPathsAreDroppedWithoutLosingLinksAndTheFileShrinks() = runBlocking {
        val sources = db.qqSourceDao()
        val md5s = (1..3_000).map(::md5)
        val second = "f".repeat(32)
        val odd = "$CHATPIC/chatimg/odd/not_a_cache_name"
        val origins = md5s.mapIndexed { index, md5 -> qqOrigin("o$index", md5, "g${index % 7}", "s${index % 13}", 1_000L + index) }
        origins.chunked(500).forEach { db.addOrigins(*it.toTypedArray()) }
        // 旧版每条消息存的 4 条路径；第一条消息另有第二个 MD5 的原图与一条推不出家族键的路径
        val legacy = origins.flatMap { origin -> familyPaths(origin.md5).map { QqOriginPathEntity(it, origin.id) } } +
            QqOriginPathEntity(familyPaths(second)[1], "o0") + QqOriginPathEntity(odd, "o0")
        legacy.chunked(2_000).forEach { sources.insertPaths(it) }
        md5s.take(200).forEach { md5 -> insert(familyPaths(md5)[1]) }
        insert(odd)
        val links = db.int("SELECT COUNT(*) FROM image_qq_origins")
        assertEquals(201, links)

        val slimmer = DatabaseSlimmer(appContext, db, dbName)
        val before = slimmer.usage()
        assertTrue(before.legacyPaths > 12_000)
        assertTrue(before.legacyBytes > 0)
        val phases = mutableListOf<String>()
        val saved = slimmer.slim { label, _, _ -> if (phases.lastOrNull() != label) phases += label }

        assertEquals(listOf("整理旧的图片路径", "删除不再需要的路径", "释放空间"), phases)
        assertTrue("saved=$saved", saved > 0)
        assertEquals(1, db.int("SELECT COUNT(*) FROM qq_origin_paths"))
        assertEquals(QqCachePaths.md5Family(second), db.long("SELECT family FROM qq_origin_families WHERE origin_id = 'o0'"))
        assertEquals(links, db.int("SELECT COUNT(*) FROM image_qq_origins"))
        assertEquals(0L, db.long("PRAGMA freelist_count"))
        assertEquals(1L, db.long("PRAGMA auto_vacuum"))
        val after = slimmer.usage()
        assertTrue(after.totalBytes < before.totalBytes)
        assertEquals(0L, after.legacyBytes)

        // 之后入库的第二个 MD5 的图按另记的家族关联
        val id = insert(familyPaths(second)[0])
        assertEquals(1, db.int("SELECT COUNT(*) FROM image_qq_origins WHERE image_id = $id AND origin_id = 'o0'"))
        // 没什么可精简时什么也不做
        assertEquals(0L, slimmer.slim { _, _, _ -> })
    }

    private suspend fun insert(path: String): Long = db.imageDao().insert(imageRow(path, "chatraw"))
}
