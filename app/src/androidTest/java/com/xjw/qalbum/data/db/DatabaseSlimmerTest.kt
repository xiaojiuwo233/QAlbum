package com.xjw.qalbum.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.data.db.entity.QqOriginEntity
import com.xjw.qalbum.data.db.entity.QqOriginPathEntity
import com.xjw.qalbum.data.db.entity.QqSourceAccountEntity
import com.xjw.qalbum.qq.QqCachePaths
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 精简数据库：v27 以前存下的路径中能由家族键推出的删掉（同一张图其他 MD5 的家族另记、推不出的留下），关联不变；
 * 空出来的页逐步还给系统，文件变小，之后切回自动回收。
 */
@RunWith(AndroidJUnit4::class)
class DatabaseSlimmerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val root = "/storage/emulated/0/Android/data/com.tencent.mobileqq/Tencent/MobileQQ/chatpic"
    private val dbName = "slimmer-test.db"
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        context.deleteDatabase(dbName)
        db = Room.databaseBuilder(context, AppDatabase::class.java, dbName).addCallback(AppDatabase.creationCallback).build()
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun legacyPathsAreDroppedWithoutLosingLinksAndTheFileShrinks() = runBlocking {
        val sources = db.qqSourceDao()
        sources.putAccount(QqSourceAccountEntity("a", "账号", 1, 0))
        val md5s = (1..3_000).map { "%032x".format(it) }
        val second = "f".repeat(32)
        val odd = "$root/chatimg/odd/not_a_cache_name"
        val origins = md5s.mapIndexed { index, md5 ->
            QqOriginEntity("o$index", "a", "g${index % 7}", "群", 2, "s${index % 13}", "人", "", 1_000L + index, md5, family = QqCachePaths.md5Family(md5))
        }
        origins.chunked(500).forEach { sources.insertOrigins(it) }
        // 旧版每条消息存的 4 条路径；第一条消息另有第二个 MD5 的原图与一条推不出家族键的路径
        val legacy = origins.flatMap { origin -> QqCachePaths.relativePaths(origin.md5).map { QqOriginPathEntity("$root/$it", origin.id) } } +
            QqOriginPathEntity("$root/" + QqCachePaths.relativePaths(second)[1], "o0") + QqOriginPathEntity(odd, "o0")
        legacy.chunked(2_000).forEach { sources.insertPaths(it) }
        md5s.take(200).forEach { md5 -> insert("$root/" + QqCachePaths.relativePaths(md5)[1]) }
        insert(odd)
        val links = count("SELECT COUNT(*) FROM image_qq_origins")
        assertEquals(201, links)

        val slimmer = DatabaseSlimmer(context, db, dbName)
        val before = slimmer.usage()
        assertTrue(before.legacyPaths > 12_000)
        assertTrue(before.legacyBytes > 0)
        val phases = mutableListOf<String>()
        val saved = slimmer.slim { label, _, _ -> if (phases.lastOrNull() != label) phases += label }

        assertEquals(listOf("整理旧的图片路径", "删除不再需要的路径", "释放空间"), phases)
        assertTrue("saved=$saved", saved > 0)
        assertEquals(1, count("SELECT COUNT(*) FROM qq_origin_paths"))
        assertEquals(QqCachePaths.md5Family(second), long("SELECT family FROM qq_origin_families WHERE origin_id = 'o0'"))
        assertEquals(links, count("SELECT COUNT(*) FROM image_qq_origins"))
        assertEquals(0L, long("PRAGMA freelist_count"))
        assertEquals(1L, long("PRAGMA auto_vacuum"))
        val after = slimmer.usage()
        assertTrue(after.totalBytes < before.totalBytes)
        assertEquals(0L, after.legacyBytes)

        // 之后入库的第二个 MD5 的图按另记的家族关联
        val id = insert("$root/" + QqCachePaths.relativePaths(second)[0])
        assertEquals(1, count("SELECT COUNT(*) FROM image_qq_origins WHERE image_id = $id AND origin_id = 'o0'"))
        // 没什么可精简时什么也不做
        assertEquals(0L, slimmer.slim { _, _, _ -> })
    }

    private suspend fun insert(path: String): Long = db.imageDao().insert(
        ImageEntity(
            path = path, filename = path.substringAfterLast('/'), parentDir = path.substringBeforeLast('/'), sourceType = "chatraw",
            cacheKey = path.substringAfterLast('/'), variantGroup = QqCachePaths.familyKey(path), size = 100, mtime = 1_000,
            firstSeen = 1_000, lastSeen = 1_000, mime = "image/jpeg", width = 10, height = 10,
        ),
    )

    private fun count(sql: String): Int = long(sql).toInt()

    private fun long(sql: String): Long = db.query(sql, emptyArray()).use { it.moveToFirst(); it.getLong(0) }
}
