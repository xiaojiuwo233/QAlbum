package com.xjw.qalbum.service.cleanup

import android.content.Context
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xjw.qalbum.access.AccessManager
import com.xjw.qalbum.data.db.AppDatabase
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.data.repository.ImageRepository
import com.xjw.qalbum.fileops.BoundFileOps
import com.xjw.qalbum.fileops.FileOpsImpl
import com.xjw.qalbum.fileops.IFileOps
import com.xjw.qalbum.qq.QqCachePaths
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 批量移入回收站 / 永久删除 / 清空回收站：整批处理后的文件、记录、图片状态与缩略图（文件操作在本进程内执行，
 * 走与特权进程相同的 [FileOpsImpl]）。
 */
@RunWith(AndroidJUnit4::class)
class PictureBatchDeleteTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val root = File(context.getExternalFilesDir(null), "batch-delete-test/chatpic")
    private val trash = File(context.getExternalFilesDir(null), "quarantine")
    private val dbName = "batch-delete-test.db"
    private lateinit var db: AppDatabase
    private val bound = BoundFileOps(InProcessFileOps) {}
    private val hd = jpeg(480, 360)

    private object InProcessFileOps : IFileOps.Stub() {
        override fun destroy() {}
        override fun listSubdirs(root: String, offset: Int, limit: Int): Array<String> = FileOpsImpl.listSubdirs(root, offset, limit)
        override fun listFiles(dir: String, offset: Int, limit: Int): Array<String> = FileOpsImpl.listFiles(dir, offset, limit)
        override fun readHeaders(paths: Array<String>, n: Int): ByteArray = FileOpsImpl.readHeaders(paths, n)
        override fun deleteFiles(paths: Array<String>): BooleanArray = FileOpsImpl.deleteFiles(paths)
        override fun openRead(path: String): ParcelFileDescriptor? = FileOpsImpl.openRead(path)
        override fun qqAccountCatalog(userId: Int): String = ""
        override fun qqDatabaseState(userId: Int, account: String): String = ""
        override fun openQqDatabaseRead(userId: Int, account: String, name: String): ParcelFileDescriptor? = null
        override fun statFiles(paths: Array<String>): Array<String> = FileOpsImpl.statFiles(paths)
        override fun moveFilesChecked(sources: Array<String>, targets: Array<String>, stamps: Array<String>): BooleanArray =
            FileOpsImpl.moveFilesChecked(sources, targets, stamps)
        override fun deleteFilesChecked(paths: Array<String>, stamps: Array<String>): BooleanArray = FileOpsImpl.deleteFilesChecked(paths, stamps)
    }

    /** 像 Shizuku 那样改名移不进 App 目录：只能走复制再删原文件 */
    private object NoRenameFileOps : IFileOps.Stub() {
        override fun destroy() {}
        override fun listSubdirs(root: String, offset: Int, limit: Int): Array<String> = InProcessFileOps.listSubdirs(root, offset, limit)
        override fun listFiles(dir: String, offset: Int, limit: Int): Array<String> = InProcessFileOps.listFiles(dir, offset, limit)
        override fun readHeaders(paths: Array<String>, n: Int): ByteArray = InProcessFileOps.readHeaders(paths, n)
        override fun deleteFiles(paths: Array<String>): BooleanArray = InProcessFileOps.deleteFiles(paths)
        override fun openRead(path: String): ParcelFileDescriptor? = InProcessFileOps.openRead(path)
        override fun qqAccountCatalog(userId: Int): String = ""
        override fun qqDatabaseState(userId: Int, account: String): String = ""
        override fun openQqDatabaseRead(userId: Int, account: String, name: String): ParcelFileDescriptor? = null
        override fun statFiles(paths: Array<String>): Array<String> = InProcessFileOps.statFiles(paths)
        override fun moveFilesChecked(sources: Array<String>, targets: Array<String>, stamps: Array<String>): BooleanArray = BooleanArray(sources.size)
        override fun deleteFilesChecked(paths: Array<String>, stamps: Array<String>): BooleanArray = InProcessFileOps.deleteFilesChecked(paths, stamps)
    }

    @Before
    fun setUp() {
        root.parentFile!!.deleteRecursively()
        trash.deleteRecursively()
        context.deleteDatabase(dbName)
        db = Room.databaseBuilder(context, AppDatabase::class.java, dbName).addCallback(AppDatabase.creationCallback).build()
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(dbName)
        root.parentFile!!.deleteRecursively()
        trash.deleteRecursively()
    }

    private fun jpeg(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF3366CC.toInt())
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray().also { bitmap.recycle() }
    }

    private class Family(val img: File, val raw: File, val thumb: File, val hd: File) {
        val files get() = listOf(img, raw, thumb, hd)
    }

    /** 每张图有原图 / 预览图 / 缩略图（已入库）与高清缩略图（未入库，删除时登记）；返回各组与所选的原图 id */
    private suspend fun seed(count: Int): Pair<List<Family>, List<Long>> {
        val raw = jpeg(1600, 1200)
        val img = jpeg(800, 600)
        val thumb = jpeg(198, 149)
        val mtime = 1_700_000_000_000L
        val families = (1..count).map { i ->
            val (a, b, c, d) = QqCachePaths.relativePaths("%032x".format(i)).map { File(root, it) }
            Family(a, b, c, d).also { family ->
                listOf(family.img to img, family.raw to raw, family.thumb to thumb, family.hd to hd).forEach { (file, bytes) ->
                    file.parentFile!!.mkdirs()
                    file.writeBytes(bytes)
                    file.setLastModified(mtime)
                }
            }
        }
        val rows = families.flatMap { family ->
            listOf(family.img to "chatimg", family.raw to "chatraw", family.thumb to "chatthumb").map { (file, type) ->
                ImageEntity(
                    path = file.path, filename = file.name, parentDir = file.parent!!, sourceType = type,
                    cacheKey = file.name.removePrefix("Cache_"), variantGroup = QqCachePaths.familyKey(file.path),
                    variantBytes = file.length(), size = file.length(), mtime = mtime, firstSeen = mtime, lastSeen = mtime,
                    mime = "image/jpeg", width = 1, height = 1, status = if (type == "chatthumb") "thumbnail_trash" else "active",
                )
            }
        }
        db.imageDao().insertAll(rows)
        val ids = db.imageDao().getAnyByPaths(families.map { it.raw.path }).associate { it.path to it.id }
        return families to families.map { ids.getValue(it.raw.path) }
    }

    private fun manager(): QuarantineManager {
        val koin = GlobalContext.get()
        val repository = ImageRepository(db, db.imageDao(), db.ocrDao(), db.dedupExclusionDao(), db.tagDao(), db.embeddingDao())
        return QuarantineManager(context, koin.get(), db, db.imageDao(), db.cleanupDao(), koin.get(), koin.get(), repository)
    }

    private suspend fun operations(manager: QuarantineManager, files: BoundFileOps = bound) =
        manager.linkedOperations(files, GlobalContext.get().get<AccessManager>().current())

    private fun count(sql: String): Int = db.query(sql, null).use { it.moveToFirst(); it.getInt(0) }

    private fun records(): List<List<String?>> = db.query("SELECT state, thumbnail_path, quarantine_path, group_id FROM quarantine", null).use { c ->
        generateSequence { if (c.moveToNext()) listOf(c.getString(0), c.getString(1), c.getString(2), c.getString(3)) else null }.toList()
    }

    @Test
    fun quarantineWholeBatchThenPurge() = runBlocking {
        val (families, ids) = seed(30)
        val manager = manager()
        val ops = operations(manager)
        var result = PictureDeleteResult()
        PictureFamilyPlanner(db, root.path).batches(ids, PictureDeleteOptions(), bound) { groups -> result += ops.quarantineAll(groups, "测试", Long.MAX_VALUE) }
        assertEquals(PictureDeleteResult(pictures = 30, files = 120, selectedProcessed = 30), result)
        assertEquals(0, families.sumOf { family -> family.files.count { it.exists() } })
        assertEquals(120, count("SELECT COUNT(*) FROM images WHERE status = 'quarantined'"))
        val records = records()
        assertEquals(setOf("ready"), records.map { it[0] }.toSet())
        records.forEach { assertTrue(File(it[2]!!).isFile) }
        // 每组一张缩略图、组内共用；高清缩略图本身就小，原样存下
        val byGroup = records.groupBy { it[3] }
        assertEquals(30, byGroup.size)
        byGroup.values.forEach { group ->
            assertEquals(1, group.map { it[1] }.toSet().size)
            assertArrayEquals(hd, File(group.first()[1]!!).readBytes())
        }

        var afterId = 0L
        var purged = 0
        while (true) {
            val page = db.cleanupDao().listQuarantinePaged(afterId, 200)
            if (page.isEmpty()) break
            afterId = page.last().id
            purged += manager.purgeByRecords(page)
        }
        assertEquals(30, purged)
        assertEquals(0, count("SELECT COUNT(*) FROM quarantine"))
        assertEquals(120, count("SELECT COUNT(*) FROM images WHERE status = 'deleted'"))
        assertEquals(0, trash.walk().count { it.isFile })
    }

    @Test
    fun permanentDeleteSkipsThumbnails() = runBlocking {
        val (families, ids) = seed(30)
        val ops = operations(manager())
        var result = PictureDeleteResult()
        PictureFamilyPlanner(db, root.path).batches(ids, PictureDeleteOptions(), bound) { groups -> result += ops.permanentlyDeleteAll(groups) }
        assertEquals(PictureDeleteResult(pictures = 30, files = 120, selectedProcessed = 30), result)
        assertEquals(0, families.sumOf { family -> family.files.count { it.exists() } })
        assertEquals(0, count("SELECT COUNT(*) FROM quarantine"))
        assertEquals(120, count("SELECT COUNT(*) FROM images WHERE status = 'deleted'"))
        assertEquals(0, trash.walk().count { it.isFile })
    }

    @Test
    fun copiesWhenRenameIsRefused() = runBlocking {
        val (families, ids) = seed(12)
        val ops = operations(manager(), BoundFileOps(NoRenameFileOps) {})
        val sizes = families.flatMap { it.files }.associate { it.name to it.length() }
        var result = PictureDeleteResult()
        PictureFamilyPlanner(db, root.path).batches(ids, PictureDeleteOptions(), bound) { groups -> result += ops.quarantineAll(groups, "测试", Long.MAX_VALUE) }
        assertEquals(PictureDeleteResult(pictures = 12, files = 48, selectedProcessed = 12), result)
        assertEquals(0, families.sumOf { family -> family.files.count { it.exists() } })
        val records = records()
        assertEquals(48, records.count { it[0] == "ready" })
        records.forEach { record ->
            val copy = File(record[2]!!)
            assertEquals(sizes.getValue(copy.name.substringAfter('_').substringAfter('_')), copy.length())
        }
    }

    @Test
    fun fileChangedMidBatchRollsBackOnlyItsGroup() = runBlocking {
        val (families, ids) = seed(10)
        val ops = operations(manager())
        val changed = families[3]
        var result = PictureDeleteResult()
        PictureFamilyPlanner(db, root.path).batches(ids, PictureDeleteOptions(), bound) { groups ->
            // 规划之后 QQ 改写了其中一张原图：这一组整组放回原处，其余照常移入
            changed.raw.appendBytes(byteArrayOf(1))
            result += ops.quarantineAll(groups, "测试", Long.MAX_VALUE)
        }
        assertEquals(PictureDeleteResult(pictures = 9, files = 36, selectedProcessed = 9, skipped = 1), result)
        assertTrue(changed.files.all { it.isFile })
        assertEquals(0, families.filter { it !== changed }.sumOf { family -> family.files.count { it.exists() } })
        assertEquals(36, count("SELECT COUNT(*) FROM quarantine WHERE state = 'ready'"))
        assertEquals(2, count("SELECT COUNT(*) FROM images WHERE status = 'active'"))
        // 回滚的组不留缩略图
        assertEquals(9, File(trash, "thumb").listFiles().orEmpty().size)
    }
}
