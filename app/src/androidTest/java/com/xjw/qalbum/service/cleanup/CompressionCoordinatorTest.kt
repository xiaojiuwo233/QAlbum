package com.xjw.qalbum.service.cleanup

import android.content.Context
import android.graphics.Bitmap
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xjw.qalbum.data.db.AppDatabase
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.fileops.BoundFileOps
import com.xjw.qalbum.service.cleanup.CompressionCoordinator.Session
import com.xjw.qalbum.service.cleanup.rules.CleanupRunner
import com.xjw.qalbum.service.work.BackgroundTasks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.random.Random

/** 先对比再替换：对比、只应用勾选的、撤销、换质量重压、大批量取样张、不能压缩的说明原因（文件操作在本进程内执行） */
@RunWith(AndroidJUnit4::class)
class CompressionCoordinatorTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val root = File(context.getExternalFilesDir(null), "coordinator-test/chatpic")
    private val previewDir = File(context.externalCacheDir ?: context.cacheDir, "compress/preview")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val storeFile = File(context.cacheDir, "coordinator-test-${UUID.randomUUID()}.preferences_pb")
    private val settings = SettingsRepository(context, scope, PreferenceDataStoreFactory.create(scope = scope) { storeFile })
    private val mtime = 1_700_000_000_000L
    private var files = 0
    private lateinit var db: AppDatabase
    private lateinit var coordinator: CompressionCoordinator

    @Before
    fun setUp() = runBlocking {
        root.deleteRecursively()
        previewDir.deleteRecursively()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).addCallback(AppDatabase.creationCallback).build()
        settings.setCompressMaxSide(1024)
        settings.setCompressKeepOriginal(false)
        val koin = GlobalContext.get()
        val compressor = OriginalCompressor(context, db, koin.get(), koin.get(), settings, bindOverride = { BoundFileOps(InProcessFileOps) {} })
        val tasks = BackgroundTasks(context)
        val quarantine = QuarantineManager(context, koin.get(), db, db.imageDao(), db.cleanupDao(), settings, koin.get(), ArchiveManager(db, settings, scope))
        coordinator = CompressionCoordinator(db, compressor, CleanupRunner(quarantine, compressor, tasks, scope), settings, tasks, scope)
    }

    @After
    fun tearDown() {
        coordinator.dismiss()
        db.close()
        root.deleteRecursively()
        scope.cancel()
        storeFile.delete()
    }

    @Test
    fun reviewThenApplyReplacesOnlyCheckedImages() = runBlocking {
        val images = (1..3).map { original(2048, 1536) }
        val originals = images.map { it.first.readBytes() }
        coordinator.start(images.map { it.second })
        val review = awaitReview()
        assertEquals(3, review.entries.count { it.prepared != null })
        assertFalse(review.sampled)
        // 对比期间原图不动
        images.forEachIndexed { index, (file, _) -> assertArrayEquals(originals[index], file.readBytes()) }

        val skipped = images[1].second
        coordinator.toggle(skipped)
        assertEquals(2, (coordinator.session.value as Session.Review).chosen.size)
        val message = async { coordinator.messages.first() }
        yield()
        coordinator.apply()
        assertTrue(withTimeout(30_000) { message.await() }.startsWith("已压缩 2 张"))
        assertNull(coordinator.session.value)
        assertArrayEquals(originals[1], images[1].first.readBytes())
        assertEquals(listOf(1, 0, 1), images.map { db.imageDao().getById(it.second)!!.optimized })
        assertEquals(80, db.originalBackupDao().getByImageId(images[0].second)!!.quality)
        assertTrue(previewDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun undoLeavesOriginalsAndChangingQualityRecompresses() = runBlocking {
        val (file, id) = original(2048, 1536)
        val before = file.readBytes()
        coordinator.start(listOf(id))
        val first = awaitReview().entries.single().prepared!!
        assertEquals(80, first.quality)

        coordinator.setQuality(50)
        val second = awaitReview { it.quality == 50 }.entries.single().prepared!!
        assertTrue(second.size < first.size)
        // 换质量后上一次的结果删掉
        assertFalse(first.file.exists())

        coordinator.dismiss()
        assertNull(coordinator.session.value)
        assertFalse(second.file.exists())
        assertArrayEquals(before, file.readBytes())
        assertEquals(0, db.imageDao().getById(id)!!.optimized)
    }

    @Test
    fun largeSelectionsCompressSamplesAndListUnsupportedOnes() = runBlocking {
        val many = (1..CompressionCoordinator.FULL_LIMIT + 5).map { original(320, 240).second }
        coordinator.start(many)
        val sampled = awaitReview()
        assertTrue(sampled.sampled)
        assertEquals(CompressionCoordinator.SAMPLE_SIZE, sampled.entries.size)
        assertEquals(many.size - CompressionCoordinator.SAMPLE_SIZE, sampled.remaining.size)
        assertEquals(many.size, sampled.eligible)
        coordinator.dismiss()

        // 张数不多时，不能压缩的也列出来并说明原因
        val gif = db.imageDao().insert(row(File(root, "gif"), 1000, animated = true))
        val (_, jpeg) = original(800, 600)
        coordinator.start(listOf(gif, jpeg))
        val mixed = awaitReview()
        assertEquals(1, mixed.skipped)
        val unavailable = mixed.entries.single { it.prepared == null }.preview as OriginalCompressor.Preview.Unavailable
        assertEquals("动图不压缩", unavailable.reason)
    }

    private suspend fun awaitReview(predicate: (Session.Review) -> Boolean = { true }): Session.Review = withTimeout(60_000) {
        coordinator.session.first { it is Session.Review && predicate(it) } as Session.Review
    }

    /** 写一张噪点 JPEG 原图并入库 */
    private suspend fun original(width: Int, height: Int): Pair<File, Long> {
        val n = ++files
        val file = File(root, "chatraw/$n/Cache_$n").apply { parentFile!!.mkdirs() }
        val random = Random(n)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(IntArray(width * height) { 0xFF000000.toInt() or random.nextInt(0x1000000) }, 0, width, 0, 0, width, height)
        file.writeBytes(ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }.toByteArray())
        bitmap.recycle()
        file.setLastModified(mtime)
        return file to db.imageDao().insert(row(file, file.length()).copy(width = width, height = height))
    }

    private fun row(file: File, size: Long, animated: Boolean = false) = ImageEntity(
        path = file.path, filename = file.name, parentDir = file.parent!!, sourceType = "chatraw",
        cacheKey = file.name, size = size, mtime = mtime, firstSeen = mtime, lastSeen = mtime,
        mime = if (animated) "image/gif" else "image/jpeg", width = 100, height = 100, isAnimated = animated,
    )
}
