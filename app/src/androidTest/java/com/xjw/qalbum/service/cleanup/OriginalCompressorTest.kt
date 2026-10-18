package com.xjw.qalbum.service.cleanup

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xjw.qalbum.data.db.AppDatabase
import com.xjw.qalbum.data.db.dao.ImageDao
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.data.db.entity.OriginalBackupEntity
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.fileops.BoundFileOps
import com.xjw.qalbum.fileops.FileOpsImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

/** 原图压缩：先对比再替换、原地替换、备份与还原、压缩记录、跳过被改过 / 不划算的图、启动对账（文件操作在本进程内执行） */
@RunWith(AndroidJUnit4::class)
class OriginalCompressorTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val root = File(context.getExternalFilesDir(null), "compress-test/chatpic")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val storeFile = File(context.cacheDir, "compress-test-${UUID.randomUUID()}.preferences_pb")
    private val settings = SettingsRepository(context, scope, PreferenceDataStoreFactory.create(scope = scope) { storeFile })
    private val mtime = 1_700_000_000_000L
    private val backups = mutableListOf<File>()
    private var files = 0
    private lateinit var db: AppDatabase

    @Before
    fun setUp() = runBlocking {
        root.deleteRecursively()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).addCallback(AppDatabase.creationCallback).build()
        settings.setCompressMaxSide(1024)
    }

    @After
    fun tearDown() {
        db.close()
        root.deleteRecursively()
        backups.forEach { it.delete() }
        scope.cancel()
        storeFile.delete()
    }

    @Test
    fun compressReplacesInPlaceAndRestoreBringsBackTheOriginal() = runBlocking {
        val (file, id) = original(2048, 1536)
        val before = file.readBytes()
        val compressor = compressor()

        val outcome = compressor.compress(listOf(id), QUALITY, MAX_SIDE)
        assertEquals(1, outcome.compressed)
        assertEquals(before.size - file.length(), outcome.savedBytes)
        // 原位置换成缩到长边 1024 的新图，修改时间不变，不留临时文件
        assertEquals(1024 to 768, decodedSize(file))
        assertEquals(mtime, file.lastModified())
        assertFalse(File(file.path + FileOpsImpl.TEMP_SUFFIX).exists())
        val row = db.imageDao().getById(id)!!
        assertEquals(listOf(file.length(), 1024L, 768L, 1L, mtime), listOf(row.size, row.width.toLong(), row.height.toLong(), row.optimized.toLong(), row.mtime))
        val record = db.originalBackupDao().getByImageId(id)!!
        assertEquals("ready", record.state)
        assertEquals(QUALITY, record.quality)
        val backup = File(record.backupPath!!).also { backups += it }
        assertArrayEquals(before, backup.readBytes())

        // 已压缩的不再处理
        assertEquals(OriginalCompressor.Outcome(skipped = 1), compressor.compress(listOf(id), QUALITY, MAX_SIDE))

        assertTrue(compressor.restore(id))
        assertArrayEquals(before, file.readBytes())
        assertEquals(mtime, file.lastModified())
        assertFalse(backup.exists())
        assertNull(db.originalBackupDao().getByImageId(id))
        val restored = db.imageDao().getById(id)!!
        assertEquals(listOf(before.size.toLong(), 2048L, 1536L, 2L), listOf(restored.size, restored.width.toLong(), restored.height.toLong(), restored.optimized.toLong()))
    }

    @Test
    fun withoutKeepingOriginalsOnlyTheRecordIsLeft() = runBlocking {
        settings.setCompressKeepOriginal(false)
        val (file, id) = original(2048, 1536)
        val compressor = compressor()
        assertEquals(1, compressor.compress(listOf(id), 60, MAX_SIDE).compressed)
        // 没有备份，但压缩记录留着（详情里显示质量与前后大小）
        val record = db.originalBackupDao().getByImageId(id)!!
        assertNull(record.backupPath)
        assertEquals(listOf(60L, file.length()), listOf(record.quality.toLong(), record.compressedSize))
        assertEquals(1, db.imageDao().getById(id)!!.optimized)
        assertEquals(file.length(), db.imageDao().getById(id)!!.size)
        assertFalse(compressor.restore(id))
        // 回收站「压缩前的原图」只列有备份的
        assertEquals(0, db.originalBackupDao().listReady(0, 10).size)
    }

    @Test
    fun prepareLeavesTheOriginalUntilApplied() = runBlocking {
        val (file, id) = original(2048, 1536)
        val before = file.readBytes()
        val compressor = compressor()
        val image = db.imageDao().getById(id)!!

        val prepared = (compressor.prepare(listOf(image), QUALITY, MAX_SIDE).single() as OriginalCompressor.Preview.Ready).prepared
        // 只压到缓存：原图、索引都没动
        assertArrayEquals(before, file.readBytes())
        assertEquals(0, db.imageDao().getById(id)!!.optimized)
        assertNull(db.originalBackupDao().getByImageId(id))
        assertTrue(prepared.file.isFile)
        assertEquals(1024 to 768, prepared.width to prepared.height)
        assertTrue(prepared.savedRatio > 0.5f)

        val outcome = compressor.apply(listOf(prepared))
        assertEquals(OriginalCompressor.Outcome(compressed = 1, savedBytes = prepared.savedBytes), outcome)
        assertFalse(prepared.file.exists())
        assertEquals(prepared.size, file.length())
        assertEquals(mtime, file.lastModified())
        assertEquals(1, db.imageDao().getById(id)!!.optimized)
        backups += File(db.originalBackupDao().getByImageId(id)!!.backupPath!!)
    }

    @Test
    fun theRecordKeepsTheRealOriginalSizeWhenTheIndexHadAShrunkenOne() = runBlocking {
        val (_, id) = original(2048, 1536)
        // 旧版分析写入的缩小尺寸
        db.openHelper.writableDatabase.execSQL("UPDATE images SET width = 512, height = 384 WHERE id = $id")
        val compressor = compressor()
        val image = db.imageDao().getById(id)!!
        val prepared = (compressor.prepare(listOf(image), QUALITY, MAX_SIDE).single() as OriginalCompressor.Preview.Ready).prepared
        assertEquals(2048 to 1536, prepared.originalWidth to prepared.originalHeight)

        assertEquals(1, compressor.apply(listOf(prepared)).compressed)
        val record = db.originalBackupDao().getByImageId(id)!!
        backups += File(record.backupPath!!)
        assertEquals(2048 to 1536, record.originalWidth to record.originalHeight)
        // 还原后库里是原图真正的尺寸
        assertTrue(compressor.restore(id))
        assertEquals(2048 to 1536, db.imageDao().getById(id)!!.let { it.width to it.height })
    }

    @Test
    fun savedSpaceCountsAsFreedOnceTheOriginalIsGone() = runBlocking {
        val (_, kept) = original(2048, 1536)
        val compressor = compressor()
        val saved = compressor.compress(listOf(kept), QUALITY, MAX_SIDE).savedBytes
        val dao = db.originalBackupDao()
        // 原图还在回收站：还不算
        assertEquals(ImageDao.CleanedStatsRow(0, 0), dao.observeFreedStats().first())
        assertNull(dao.getByImageId(kept)!!.freedAt)

        val purgedAt = System.currentTimeMillis()
        assertEquals(1, compressor.purgeByImageIds(listOf(kept)))
        assertEquals(ImageDao.CleanedStatsRow(1, saved), dao.observeFreedStats().first())
        assertTrue(dao.getByImageId(kept)!!.freedAt!! >= purgedAt)
        assertEquals(ImageDao.CleanedStatsRow(1, saved), dao.observeFreedStatsSince(purgedAt).first())

        // 不保留原图：替换完就算
        settings.setCompressKeepOriginal(false)
        val (_, direct) = original(2048, 1536)
        val more = compressor.compress(listOf(direct), QUALITY, MAX_SIDE).savedBytes
        assertEquals(ImageDao.CleanedStatsRow(2, saved + more), dao.observeFreedStats().first())
        assertNotNull(dao.getByImageId(direct)!!.freedAt)
        assertEquals(ImageDao.CleanedStatsRow(0, 0), dao.observeFreedStatsSince(Long.MAX_VALUE).first())
    }

    @Test
    fun discardOrChangedFilesKeepTheOriginal() = runBlocking {
        val (kept, keptId) = original(2048, 1536)
        val (changed, changedId) = original(2048, 1536)
        val keptBytes = kept.readBytes()
        val compressor = compressor()
        val previews = compressor.prepare(db.imageDao().getAnyByIds(listOf(keptId, changedId)).sortedBy { it.id }, QUALITY, MAX_SIDE)
        val (first, second) = previews.map { (it as OriginalCompressor.Preview.Ready).prepared }

        // 撤销：删掉缓存，原图不动
        compressor.discard(listOf(first))
        assertFalse(first.file.exists())
        assertArrayEquals(keptBytes, kept.readBytes())

        // 对比期间 QQ 改写了文件：不替换
        changed.appendBytes(ByteArray(16))
        val changedBytes = changed.readBytes()
        assertEquals(OriginalCompressor.Outcome(skipped = 1), compressor.apply(listOf(second)))
        assertArrayEquals(changedBytes, changed.readBytes())
        assertFalse(second.file.exists())
        assertEquals(listOf(0, 0), listOf(keptId, changedId).map { db.imageDao().getById(it)!!.optimized })
    }

    @Test
    fun ownershipRepairLeavesCorrectFilesAloneAndRunsOnce() = runBlocking {
        val (file, id) = original(800, 600)
        db.openHelper.writableDatabase.execSQL("UPDATE images SET optimized = 1 WHERE id = $id")
        val before = file.readBytes()
        val compressor = compressor()
        // 文件与目录同属主（本应用）：不用修
        assertEquals(0, compressor.repairOwnership())
        assertTrue(settings.ownershipRepaired.first())
        assertArrayEquals(before, file.readBytes())
        assertEquals(0, compressor.repairOwnership())
    }

    @Test
    fun opaquePngIsStoredAsJpegAndRestoredAsPng() = runBlocking {
        val (file, id) = original(1600, 1200, format = Bitmap.CompressFormat.PNG)
        val before = file.readBytes()
        val compressor = compressor()
        assertEquals(1, compressor.compress(listOf(id), QUALITY, MAX_SIDE).compressed)
        // 文件名不变，内容是 JPEG（QQ 按内容解码）
        val after = file.readBytes()
        assertEquals(listOf(0xFF, 0xD8), listOf(after[0].toInt() and 0xFF, after[1].toInt() and 0xFF))
        assertEquals("image/jpeg", db.imageDao().getById(id)!!.mime)
        val record = db.originalBackupDao().getByImageId(id)!!
        assertEquals("image/png", record.originalMime)
        backups += File(record.backupPath!!)

        assertTrue(compressor.restore(id))
        assertArrayEquals(before, file.readBytes())
        assertEquals("image/png", db.imageDao().getById(id)!!.mime)
    }

    @Test
    fun transparentOrAnimatedPngIsLeftAlone() = runBlocking {
        val (transparent, transparentId) = original(800, 600, format = Bitmap.CompressFormat.PNG, alpha = 0x80)
        val (animated, animatedId) = original(800, 600, format = Bitmap.CompressFormat.PNG) { file ->
            // 在 IHDR 之后插入 acTL 块，就是动画 PNG
            val bytes = file.readBytes()
            val acTL = byteArrayOf(0, 0, 0, 8) + "acTL".toByteArray() + ByteArray(8) + ByteArray(4)
            file.writeBytes(bytes.copyOfRange(0, 33) + acTL + bytes.copyOfRange(33, bytes.size))
        }
        val compressor = compressor()
        val images = db.imageDao().getAnyByIds(listOf(transparentId, animatedId)).sortedBy { it.id }
        val reasons = compressor.prepare(images, QUALITY, MAX_SIDE).map { (it as OriginalCompressor.Preview.Unavailable).reason }
        assertEquals(listOf("透明 PNG 不压缩", "动图不压缩"), reasons)
        // 直接压缩时记为不划算，原文件不动
        val transparentBytes = transparent.readBytes()
        assertEquals(OriginalCompressor.Outcome(skipped = 2), compressor.compress(listOf(transparentId, animatedId), QUALITY, MAX_SIDE))
        assertArrayEquals(transparentBytes, transparent.readBytes())
        assertEquals(listOf(2, 2), listOf(transparentId, animatedId).map { db.imageDao().getById(it)!!.optimized })
        assertTrue(animated.isFile)
    }

    @Test
    fun unsupportedImagesAreExplained() = runBlocking {
        val (_, jpegId) = original(800, 600)
        val compressor = compressor()
        val jpeg = db.imageDao().getById(jpegId)!!
        assertNull(compressor.unsupportedReason(jpeg))
        assertEquals("动图不压缩", compressor.unsupportedReason(jpeg.copy(isAnimated = true, mime = "image/gif")))
        assertNull(compressor.unsupportedReason(jpeg.copy(mime = "image/png")))
        assertEquals("只能压缩 JPEG 和 PNG", compressor.unsupportedReason(jpeg.copy(mime = "image/webp")))
        assertEquals("已经压缩过", compressor.unsupportedReason(jpeg.copy(optimized = OriginalCompressor.COMPRESSED)))
        assertEquals("缩略图不压缩", compressor.unsupportedReason(jpeg.copy(sourceType = "chatthumb")))
        // 归档的图也能压缩（归档只是不会被删除）
        assertNull(compressor.unsupportedReason(jpeg.copy(isArchived = true, status = "archived")))
    }

    @Test
    fun rotatedOriginalIsWrittenUpright() = runBlocking {
        val (file, id) = original(2048, 1536) { path ->
            ExifInterface(path.path).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
        }
        assertEquals(1, compressor().compress(listOf(id), QUALITY, MAX_SIDE).compressed)
        // 新文件不带 EXIF：像素本身转正，宽高与转正后的显示一致
        assertEquals(768 to 1024, decodedSize(file))
        val row = db.imageDao().getById(id)!!
        assertEquals(768 to 1024, row.width to row.height)
        val record = db.originalBackupDao().getByImageId(id)!!
        backups += File(record.backupPath!!)
        // 压缩前的尺寸也按显示方向
        assertEquals(1536 to 2048, record.originalWidth to record.originalHeight)
    }

    @Test
    fun skipsFilesChangedOnDiskAndPicturesNotWorthCompressing() = runBlocking {
        val (changed, changedId) = original(2048, 1536)
        changed.appendBytes(ByteArray(16))
        val changedBytes = changed.readBytes()
        // 本来就压得很狠的小图：按 80% 重新编码反而变大
        val (small, smallId) = original(800, 600, quality = 50)
        val smallBytes = small.readBytes()

        assertEquals(OriginalCompressor.Outcome(skipped = 2), compressor().compress(listOf(changedId, smallId), QUALITY, MAX_SIDE))
        assertArrayEquals(changedBytes, changed.readBytes())
        assertArrayEquals(smallBytes, small.readBytes())
        assertEquals(0, db.imageDao().getById(changedId)!!.optimized)
        assertEquals(2, db.imageDao().getById(smallId)!!.optimized)
        assertNull(db.originalBackupDao().getByImageId(changedId))
        assertNull(db.originalBackupDao().getByImageId(smallId))
    }

    @Test
    fun reconcileFinishesReplacedFilesAndDropsUnreplacedOnes() = runBlocking {
        // 替换已发生（文件已是压缩后的大小）但没来得及写回：补写
        val (done, doneId) = original(800, 600)
        val doneBackup = File(root, "backup-done").apply { writeBytes(ByteArray(10)) }
        insertPending(doneId, done, doneBackup, originalSize = done.length() + 5_000, compressedSize = done.length())
        db.openHelper.writableDatabase.execSQL("UPDATE images SET size = size + 5000 WHERE id = $doneId")
        // 替换没发生（文件还是原图）：丢掉日志与备份
        val (untouched, untouchedId) = original(800, 600)
        val strayBackup = File(root, "backup-stray").apply { writeBytes(ByteArray(10)) }
        insertPending(untouchedId, untouched, strayBackup, originalSize = untouched.length(), compressedSize = 123)

        assertEquals(2, compressor().reconcile())
        assertEquals("ready", db.originalBackupDao().getByImageId(doneId)!!.state)
        val row = db.imageDao().getById(doneId)!!
        assertEquals(done.length() to 1, row.size to row.optimized)
        assertNull(db.originalBackupDao().getByImageId(untouchedId))
        assertFalse(strayBackup.exists())
        assertEquals(0, db.imageDao().getById(untouchedId)!!.optimized)
    }

    private companion object {
        const val QUALITY = 80
        const val MAX_SIDE = 1024
    }

    private fun compressor() = OriginalCompressor(
        context, db, GlobalContext.get().get(), GlobalContext.get().get(), settings,
        bindOverride = { BoundFileOps(InProcessFileOps) {} },
    )

    /** 写一张噪点 JPEG 原图并入库（大小、修改时间与磁盘一致，宽高按显示方向） */
    private suspend fun original(
        width: Int, height: Int, quality: Int = 100,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG, alpha: Int = 0xFF,
        edit: (File) -> Unit = {},
    ): Pair<File, Long> {
        val n = ++files
        val file = File(root, "chatraw/$n/Cache_$n").apply { parentFile!!.mkdirs() }
        val random = Random(n)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(IntArray(width * height) { (alpha shl 24) or random.nextInt(0x1000000) }, 0, width, 0, 0, width, height)
        file.writeBytes(ByteArrayOutputStream().also { bitmap.compress(format, quality, it) }.toByteArray())
        bitmap.recycle()
        edit(file)
        file.setLastModified(mtime)
        val rotated = ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) in 5..8
        val id = db.imageDao().insert(
            ImageEntity(
                path = file.path, filename = file.name, parentDir = file.parent!!, sourceType = "chatraw",
                cacheKey = "$n", size = file.length(), mtime = mtime, firstSeen = mtime, lastSeen = mtime,
                mime = if (format == Bitmap.CompressFormat.PNG) "image/png" else "image/jpeg",
                width = if (rotated) height else width, height = if (rotated) width else height,
            ),
        )
        return file to id
    }

    private suspend fun insertPending(imageId: Long, file: File, backup: File, originalSize: Long, compressedSize: Long) {
        db.originalBackupDao().insert(
            OriginalBackupEntity(
                imageId = imageId, path = file.path, backupPath = backup.path,
                originalSize = originalSize, originalMtime = mtime, originalWidth = 800, originalHeight = 600,
                compressedSize = compressedSize, compressedWidth = 800, compressedHeight = 600,
                createdAt = mtime, expiresAt = Long.MAX_VALUE, state = "pending",
            ),
        )
    }

    private fun decodedSize(file: File): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        return options.outWidth to options.outHeight
    }
}
