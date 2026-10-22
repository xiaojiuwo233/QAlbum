package com.xjw.qalbum.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.data.db.entity.QqOriginEntity
import com.xjw.qalbum.data.db.entity.QqSourceAccountEntity
import com.xjw.qalbum.qq.QqCachePaths
import com.xjw.qalbum.qq.QqSourceStatsBuilder
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v27：图片按家族键关联消息，来源统计随新增的关联增量更新。新入库的图、带后缀的别名、改家族键（发送图片缓存并进
 * 原图那一家）、恢复、读取后新增的消息（含新的发送人）之后，各分类与汇总都与按当前状态整体重算一致。
 */
@RunWith(AndroidJUnit4::class)
class QqSourceFamilyStatsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val root = "/storage/emulated/0/Android/data/com.tencent.mobileqq/Tencent/MobileQQ/chatpic"
    private val photo = "11111111111111111111111111111111"
    private val shared = "22222222222222222222222222222222"
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).addCallback(AppDatabase.creationCallback).build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun linksAddedByFamilyKeepStatsInStepWithARecount() = runBlocking {
        val sources = db.qqSourceDao()
        sources.putAccount(QqSourceAccountEntity("a", "账号", 1, 0))
        sources.insertOrigins(
            listOf(
                origin("o1", "g1", "s1", photo, 1_000),
                origin("o2", "g1", "s2", shared, 2_000),
                origin("o3", "p1", "s1", photo, 3_000),
            ),
        )
        val (photoImg, photoRaw, photoThumb) = paths(photo)
        insert(photoImg, "chatimg", "active", 100)
        insert(photoThumb, "chatthumb", "thumbnail_trash", 10)
        // 第一次整体重建之后才增量维护
        QqSourceStatsBuilder(db).rebuild {}
        assertFalse(sources.cacheDirty())
        assertConsistent()

        // 扫描入库：另一张图的预览图、这张图的原图与带后缀的别名
        insert(paths(shared)[0], "chatimg", "active", 200)
        insert(photoRaw, "chatraw", "active", 1_000)
        insert("$photoRaw.png", "chatraw", "active", 900)
        assertEquals(2, links(photoRaw))
        assertConsistent()

        // 发送图片缓存：先按自己内容的家族入库（没有来源），并进原图那一家后关联
        val preview = insert("$root/chatimg/abc/Cache_123abc", "chatimg", "active", 50)
        assertEquals(0, links(preview))
        db.openHelper.writableDatabase.execSQL("UPDATE images SET variant_group = ${QqCachePaths.md5Family(photo)} WHERE id = $preview")
        assertEquals(2, links(preview))
        assertConsistent()

        // 移入回收站再恢复
        db.openHelper.writableDatabase.execSQL("UPDATE images SET status = 'quarantined', path = 'qalbum-internal://quarantine/$preview' WHERE id = $preview")
        assertConsistent()
        db.openHelper.writableDatabase.execSQL("UPDATE images SET status = 'active', path = '$root/chatimg/abc/Cache_123abc' WHERE id = $preview")
        assertConsistent()

        // 读到新消息：已有的群里来了新发送人（还没有分类，关联时先跳过，读取结束时补上）
        val baseline = sources.readBaseline()
        sources.insertOrigins(listOf(origin("o4", "g1", "s3", shared, 4_000), origin("o5", "g2", "s1", photo, 5_000)))
        sources.linkAfter(baseline)
        QqSourceStatsBuilder(db).applyNew(baseline, emptySet())
        assertConsistent()
        assertEquals(listOf(3L, 3L), db.query("SELECT COUNT(*) FROM qq_source_facets WHERE kind = 0 UNION ALL SELECT COUNT(*) FROM qq_source_facets WHERE kind = 1", emptyArray()).use { c ->
            buildList { while (c.moveToNext()) add(c.getLong(0)) }
        })
    }

    private fun origin(id: String, conversation: String, sender: String, md5: String, time: Long) = QqOriginEntity(
        id, "a", conversation, "会话$conversation", if (conversation.startsWith("g")) 2 else 1, sender, "人$sender", "", time, md5,
        family = QqCachePaths.md5Family(md5),
    )

    private fun paths(md5: String) = QqCachePaths.relativePaths(md5).map { "$root/$it" }

    private suspend fun insert(path: String, type: String, status: String, size: Long): Long = db.imageDao().insert(
        ImageEntity(
            path = path, filename = path.substringAfterLast('/'), parentDir = path.substringBeforeLast('/'), sourceType = type,
            cacheKey = path.substringAfterLast('/'), variantGroup = QqCachePaths.familyKey(path), size = size, mtime = 1_000,
            firstSeen = 1_000, lastSeen = 1_000, mime = "image/jpeg", width = 10, height = 10, status = status,
        ),
    )

    private fun links(path: String): Int = db.query("SELECT COUNT(*) FROM image_qq_origins l JOIN images i ON i.id = l.image_id WHERE i.path = ?", arrayOf(path))
        .use { it.moveToFirst(); it.getInt(0) }

    private fun links(id: Long): Int = db.query("SELECT COUNT(*) FROM image_qq_origins WHERE image_id = $id", emptyArray()).use { it.moveToFirst(); it.getInt(0) }

    /** 增量维护的统计与按当前状态整体重算完全一致（投影、各分类、汇总） */
    private fun assertConsistent() {
        val sql = db.openHelper.writableDatabase
        fun facets() = db.query("SELECT kind, `key`, image_count, image_bytes, thumb_count, thumb_bytes FROM qq_source_facets ORDER BY kind, `key`", emptyArray()).use { c ->
            buildList { while (c.moveToNext()) add("${c.getInt(0)}:${c.getString(1)} " + (2..5).map { c.getLong(it) }) }
        }
        fun cache() = db.query("SELECT image_count, image_bytes, thumb_count, thumb_bytes, dirty FROM qq_source_cache", emptyArray()).use { c ->
            c.moveToFirst(); (0..4).map { c.getLong(it) }
        }
        fun contexts() = db.query("SELECT image_id, conversation_id, sender_id FROM qq_image_contexts ORDER BY 1, 2, 3", emptyArray()).use { c ->
            buildList { while (c.moveToNext()) add((0..2).map { c.getLong(it) }) }
        }
        val stored = Triple(facets(), cache(), contexts())
        // 投影按关联重新投一遍、计数按当前状态重算
        sql.execSQL(QqSourceStatsSql.CONTEXTS_IN_RANGE, arrayOf<Any>(0, Long.MAX_VALUE))
        QqSourceStatsSql.recount.forEach(sql::execSQL)
        assertEquals(Triple(facets(), cache(), contexts()), stored)
    }
}
