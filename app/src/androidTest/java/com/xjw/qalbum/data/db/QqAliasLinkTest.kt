package com.xjw.qalbum.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.data.db.entity.QqOriginEntity
import com.xjw.qalbum.data.db.entity.QqSourceAccountEntity
import com.xjw.qalbum.qq.QqCachePaths
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * QQ 按原文件名带后缀另存的缓存（Cache_xxx.png / .gif，发送本地图片、另存后转发时出现）与同名缓存同一家族：
 * 读取来源后补关联、扫描新入库时，都按家族键关联上消息，不会被算成「聊天记录已删除」。
 */
@RunWith(AndroidJUnit4::class)
class QqAliasLinkTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val root = "/storage/emulated/0/Android/data/com.tencent.mobileqq/Tencent/MobileQQ/chatpic"
    private val dbName = "qq-alias-link-test.db"
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

    private fun image(path: String) = ImageEntity(
        path = path, filename = path.substringAfterLast('/'), parentDir = path.substringBeforeLast('/'), sourceType = "chatraw",
        cacheKey = path.substringAfterLast('/'), variantGroup = QqCachePaths.familyKey(path), size = 1_000, mtime = 1_000,
        firstSeen = 1_000, lastSeen = 1_000, mime = "image/png", width = 10, height = 10,
    )

    private suspend fun linked(path: String): Int = db.query(
        "SELECT COUNT(*) FROM image_qq_origins l JOIN images i ON i.id = l.image_id WHERE i.path = ?", arrayOf(path),
    ).use { it.moveToFirst(); it.getInt(0) }

    @Test
    fun aliasesWithAnyPictureExtensionFollowTheirCacheName() = runBlocking {
        val md5 = "0123456789abcdef0123456789abcdef"
        val raw = "$root/" + QqCachePaths.relativePaths(md5).first { it.startsWith("chatraw/") }
        val sources = db.qqSourceDao()
        sources.putAccount(QqSourceAccountEntity("account", "Account", 1_000, 1))
        // 已经入库的别名：读到这条消息后按家族键补上关联
        listOf("png", "gif", "JPEG").forEach { db.imageDao().insertAll(listOf(image("$raw.$it"))) }
        db.imageDao().insertAll(listOf(image("$raw.txt")))
        val baseline = sources.readBaseline()
        sources.insertOrigins(listOf(QqOriginEntity("origin", "account", "group", "群", 2, "sender", "Alice", "", 1_000, md5, family = QqCachePaths.md5Family(md5))))
        sources.linkAfter(baseline)
        assertEquals(1, linked("$raw.png"))
        assertEquals(1, linked("$raw.gif"))
        assertEquals(1, linked("$raw.JPEG"))
        assertEquals(0, linked("$raw.txt"))

        // 扫描新入库的别名：来源触发器插入时按家族键关联
        val webp = "$raw.webp"
        db.imageDao().insertAll(listOf(image(webp)))
        assertEquals(1, linked(webp))
    }
}
