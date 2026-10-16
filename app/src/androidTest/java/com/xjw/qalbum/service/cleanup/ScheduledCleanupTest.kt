package com.xjw.qalbum.service.cleanup

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xjw.qalbum.data.db.AppDatabase
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.qq.QqCachePaths
import com.xjw.qalbum.service.cleanup.rules.CleanupRule
import com.xjw.qalbum.service.cleanup.rules.ScheduledCleanup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** 定时清理：只跑设置里选的规则、用规则当前的参数，没有文件访问就跳过，结果记进设置 */
@RunWith(AndroidJUnit4::class)
class ScheduledCleanupTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val root = "/storage/emulated/0/Android/data/com.tencent.mobileqq/Tencent/MobileQQ/chatpic"
    private val now = 1_760_000_000_000L
    private val day = 24L * 60 * 60 * 1000
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val storeFile = File(context.cacheDir, "scheduled-cleanup-test-${UUID.randomUUID()}.preferences_pb")
    private val settings = SettingsRepository(context, scope, PreferenceDataStoreFactory.create(scope = scope) { storeFile })
    private val calls = mutableListOf<Call>()
    private var families = 0
    private lateinit var db: AppDatabase

    private data class Call(val ids: List<Long>, val title: String, val linkedVersions: Boolean)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).addCallback(AppDatabase.creationCallback).build()
    }

    @After
    fun tearDown() {
        db.close()
        scope.cancel()
        storeFile.delete()
    }

    private fun cleanup(accessError: String? = null) = ScheduledCleanup(
        settings, db,
        accessError = { accessError },
        trash = { ids, title, linked -> calls += Call(ids, title, linked); ids.size },
    )

    @Test
    fun runsOnlySelectedRulesWithTheirCurrentOptions() = runBlocking {
        val oldThumb = insert(family()[2], "chatthumb", "thumbnail_trash", 20_000, age = 40)
        val recentThumb = insert(family()[2], "chatthumb", "thumbnail_trash", 30_000, age = 10)
        val (img, raw) = family()
        insert(img, "chatimg", "active", 200_000, age = 3 * 365)
        val oldPicture = insert(raw, "chatraw", "active", 2_000_000, age = 3 * 365)

        // 只选了缩略图（30 天前）：旧图片不动，只处理缩略图本身
        settings.setScheduledCleanupRule(CleanupRule.Thumbnails.id, true)
        settings.setCleanupRuleParam(CleanupRule.Thumbnails.id, 30)
        val first = cleanup().run(now)
        assertEquals(listOf(Call(listOf(oldThumb), CleanupRule.Thumbnails.title, false)), calls)
        assertEquals(1, first.moved)
        assertTrue(settings.scheduledCleanupLast.first()!!.endsWith("QQ 缩略图 1 张"))

        // 改成「全部」并加选旧图片：缩略图都算，旧图片按整张图处理
        calls.clear()
        settings.setCleanupRuleParam(CleanupRule.Thumbnails.id, 0)
        settings.setScheduledCleanupRule(CleanupRule.OldPictures.id, true)
        val second = cleanup().run(now)
        assertEquals(
            listOf(
                Call(listOf(oldThumb, recentThumb), CleanupRule.Thumbnails.title, false),
                Call(listOf(oldPicture), CleanupRule.OldPictures.title, true),
            ),
            calls.map { it.copy(ids = it.ids.sorted()) },
        )
        assertEquals(3, second.moved)
        // 整张图的大小含预览图
        assertEquals(50_000L + 2_200_000L, second.bytes)
    }

    @Test
    fun skipsWithoutRulesOrFileAccess() = runBlocking {
        insert(family()[2], "chatthumb", "thumbnail_trash", 20_000, age = 40)
        // 没选规则：什么都不做，也不记结果
        assertEquals(0, cleanup().run(now).moved)
        assertNull(settings.scheduledCleanupLast.first())

        settings.setScheduledCleanupRule(CleanupRule.Thumbnails.id, true)
        val skipped = cleanup(accessError = "文件访问未授权").run(now)
        assertEquals("文件访问未授权", skipped.skipped)
        assertTrue(calls.isEmpty())
        assertTrue(settings.scheduledCleanupLast.first()!!.endsWith("没执行：文件访问未授权"))
    }

    /** 一张新图的各版本路径：chatimg、chatraw、chatthumb、chatthumb _hd */
    private fun family(): List<String> = QqCachePaths.relativePaths("%032x".format(++families)).map { "$root/$it" }

    private suspend fun insert(path: String, sourceType: String, status: String, size: Long, age: Int): Long {
        val mtime = now - age * day
        return db.imageDao().insert(
            ImageEntity(
                path = path, filename = path.substringAfterLast('/'), parentDir = path.substringBeforeLast('/'),
                sourceType = sourceType, cacheKey = path.substringAfterLast('/'), variantGroup = QqCachePaths.familyKey(path),
                size = size, mtime = mtime, firstSeen = mtime, lastSeen = mtime, mime = "image/jpeg",
                width = 1000, height = 800, isAnimated = false, status = status,
            ),
        )
    }
}
