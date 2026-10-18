package com.xjw.qalbum.service.indexer

import android.content.Context
import android.graphics.Bitmap
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xjw.qalbum.data.db.AppDatabase
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.data.db.entity.OriginalBackupEntity
import com.xjw.qalbum.data.db.entity.QuarantineEntity
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.domain.model.FileInfo
import com.xjw.qalbum.fileops.FileOpsImpl
import com.xjw.qalbum.service.indexer.transport.DirStat
import com.xjw.qalbum.service.indexer.transport.ScanTransport
import com.xjw.qalbum.service.work.BackgroundTasks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** 补读宽高与核对旧版分析写入的缩小尺寸（读文件头在本进程内执行） */
@RunWith(AndroidJUnit4::class)
class ImageSizeBackfillTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val root = File(context.getExternalFilesDir(null), "size-backfill-test")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** 补读跑在这里，等它的子协程结束就是跑完了（DataStore 常驻在 [scope] 里） */
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val storeFile = File(context.cacheDir, "size-backfill-${UUID.randomUUID()}.preferences_pb")
    private val settings = SettingsRepository(context, scope, PreferenceDataStoreFactory.create(scope = scope) { storeFile })
    private var files = 0
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        root.deleteRecursively()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).addCallback(AppDatabase.creationCallback).build()
    }

    @After
    fun tearDown() {
        db.close()
        root.deleteRecursively()
        work.cancel()
        scope.cancel()
        storeFile.delete()
    }

    @Test
    fun shrunkenSizesFromTheOldAnalysisAreReadAgain() = runBlocking {
        // 旧版分析按长边 768 以内解码，记下的是缩小后的尺寸
        val shrunk = insert(picture(2560, 1600), width = 640, height = 400)
        val small = insert(picture(300, 200), width = 300, height = 200)
        val missing = insert(File(root, "gone/Cache_1"), width = 640, height = 400)
        val unread = insert(picture(1200, 900), width = 0, height = 0)
        // 缩略图本来就小，旧版也不分析它们；长边大的是读文件头得到的
        val thumb = insert(picture(1024, 768), width = 256, height = 192, sourceType = "chatthumb")
        val large = insert(picture(2048, 1536), width = 1600, height = 1200)

        run()

        assertEquals(2560 to 1600, size(shrunk))
        assertEquals(300 to 200, size(small))
        // 文件读不到的保持原样
        assertEquals(640 to 400, size(missing))
        assertEquals(1200 to 900, size(unread))
        assertEquals(256 to 192, size(thumb))
        assertEquals(1600 to 1200, size(large))
        assertEquals(1, settings.imageSizeCheckVersion.first())
    }

    @Test
    fun picturesInTheRecycleBinAreReadWhereTheyWereMoved() = runBlocking {
        val moved = picture(2048, 1536)
        val id = insert(File(root, "elsewhere/Cache_9"), width = 512, height = 384)
        db.openHelper.writableDatabase.execSQL(
            "UPDATE images SET status = 'quarantined', path = 'qalbum-internal://quarantine/$id' WHERE id = $id",
        )
        db.cleanupDao().insertQuarantine(
            QuarantineEntity(
                imageId = id, originalPath = File(root, "elsewhere/Cache_9").path, quarantinePath = moved.path,
                reason = "test", size = moved.length(), quarantinedAt = 1, expiresAt = Long.MAX_VALUE,
            ),
        )

        run()

        assertEquals(2048 to 1536, size(id))
    }

    @Test
    fun compressionRecordsGetTheRealOriginalSize() = runBlocking {
        val backup = picture(2560, 1600)
        val withBackup = record(backup.path, original = 640 to 400, compressed = 1920 to 1200)
        // 没有备份：压缩前比压缩后还小，只能记为未知
        val impossible = record(null, original = 640 to 400, compressed = 2560 to 1600)
        val plausible = record(null, original = 800 to 600, compressed = 800 to 600)

        run()

        val dao = db.originalBackupDao()
        assertEquals(2560 to 1600, dao.getById(withBackup)!!.let { it.originalWidth to it.originalHeight })
        assertEquals(0 to 0, dao.getById(impossible)!!.let { it.originalWidth to it.originalHeight })
        assertEquals(800 to 600, dao.getById(plausible)!!.let { it.originalWidth to it.originalHeight })
    }

    @Test
    fun theCheckRunsOnce() = runBlocking {
        val id = insert(picture(2560, 1600), width = 640, height = 400)
        run()
        assertEquals(2560 to 1600, size(id))

        db.openHelper.writableDatabase.execSQL("UPDATE images SET width = 640, height = 400 WHERE id = $id")
        run()
        assertEquals(640 to 400, size(id))
    }

    @Test
    fun anEmptyLibraryIsMarkedCheckedWithoutReading() = runBlocking {
        var opened = 0
        backfill { opened++; InProcessSizes }.startIfNeeded()
        awaitIdle()
        assertEquals(0, opened)
        assertEquals(1, settings.imageSizeCheckVersion.first())
    }

    private suspend fun run() {
        backfill { InProcessSizes }.startIfNeeded()
        awaitIdle()
    }

    private fun backfill(open: suspend () -> ScanTransport?) = ImageSizeBackfill(
        imageDao = db.imageDao(), backups = db.originalBackupDao(), settings = settings,
        openTransport = open, tasks = BackgroundTasks(context), appScope = work,
    )

    private suspend fun awaitIdle() = withTimeout(30_000) {
        work.coroutineContext[Job]!!.children.forEach { it.join() }
    }

    private suspend fun size(id: Long): Pair<Int, Int> = db.imageDao().getById(id)!!.let { it.width to it.height }

    /** 写一张纯色 JPEG */
    private fun picture(width: Int, height: Int): File {
        val n = ++files
        val file = File(root, "chatraw/$n/Cache_$n").apply { parentFile!!.mkdirs() }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF336699.toInt()) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        bitmap.recycle()
        return file
    }

    private suspend fun insert(file: File, width: Int, height: Int, sourceType: String = "chatraw"): Long =
        db.imageDao().insert(
            ImageEntity(
                path = file.path, filename = file.name, parentDir = file.parentFile!!.name, sourceType = sourceType,
                cacheKey = file.name, size = file.length(), mtime = 1_700_000_000_000L, firstSeen = 1, lastSeen = 1,
                mime = "image/jpeg", width = width, height = height,
                status = if (sourceType == "chatthumb") "thumbnail_trash" else "active",
            ),
        )

    private suspend fun record(backupPath: String?, original: Pair<Int, Int>, compressed: Pair<Int, Int>): Long {
        val image = insert(picture(compressed.first, compressed.second), compressed.first, compressed.second)
        return db.originalBackupDao().insert(
            OriginalBackupEntity(
                imageId = image, path = "/qq/$image", backupPath = backupPath,
                originalSize = 1_000_000, originalMtime = 1, originalWidth = original.first, originalHeight = original.second,
                compressedSize = 300_000, compressedWidth = compressed.first, compressedHeight = compressed.second,
                createdAt = 1, expiresAt = Long.MAX_VALUE, state = "ready",
            ),
        )
    }

    /** 只会读宽高的传输：在本进程里解析文件头 */
    private object InProcessSizes : ScanTransport {
        override val name = "in-process"
        override suspend fun listSubdirs(root: String): List<DirStat> = emptyList()
        override fun listFiles(dirs: List<String>): Flow<FileInfo> = emptyFlow()
        override suspend fun readHeaders(paths: List<String>, n: Int): List<ByteArray?> = paths.map { null }
        override val readsImageSizes = true
        override suspend fun readImageSizes(paths: List<String>): List<Pair<Int, Int>?> {
            val sizes = FileOpsImpl.readImageInfo(paths.toTypedArray())
            return paths.indices.map { i -> (sizes[i * 2] to sizes[i * 2 + 1]).takeIf { it.first > 0 && it.second > 0 } }
        }
        override fun close() {}
    }
}
