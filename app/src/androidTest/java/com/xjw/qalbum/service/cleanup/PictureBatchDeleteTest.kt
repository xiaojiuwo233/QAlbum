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
import com.xjw.qalbum.data.db.entity.QqOriginEntity
import com.xjw.qalbum.data.db.entity.QqOriginPathEntity
import com.xjw.qalbum.data.db.entity.QqSourceAccountEntity
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.fileops.BoundFileOps
import com.xjw.qalbum.fileops.FileOpsImpl
import com.xjw.qalbum.fileops.IFileOps
import com.xjw.qalbum.qq.QqCachePaths
import com.xjw.qalbum.qq.QqSourceStatsBuilder
import kotlinx.coroutines.flow.first
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
import kotlinx.coroutines.CoroutineScope

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

    @Test
    fun jpegAliasIsQuarantinedRestoredAndProtectedWithItsFamily() = runBlocking {
        val (families, ids) = seed(1)
        val family = families.single()
        val sources = db.qqSourceDao()
        sources.putAccount(QqSourceAccountEntity("account", "Account", 1_000, 1))
        sources.insertOrigins(listOf(QqOriginEntity("origin", "account", "group", "Photography", 2, "sender", "Alice", "", 1_000, "%032x".format(1))))
        sources.insertPaths(family.files.map { QqOriginPathEntity(it.path, "origin") })
        sources.reconcileLinks()
        QqSourceStatsBuilder(db).rebuild {}
        // .jpg 尚未扫描入库，仍应由精确缓存名发现并一起清理。
        val jpg = File(family.raw.path + ".jpg")
        family.raw.copyTo(jpg)
        val expected = family.raw.readBytes()
        val files = family.files + jpg
        val ops = operations(manager())
        var result = PictureDeleteResult()
        val planner = PictureFamilyPlanner(db, root.path)
        planner.batches(ids, PictureDeleteOptions(), bound) { groups -> result += ops.quarantineAll(groups, "测试", Long.MAX_VALUE) }
        assertEquals(PictureDeleteResult(pictures = 1, files = 5, selectedProcessed = 1), result)
        assertTrue(files.none { it.exists() })
        val head = db.cleanupDao().listQuarantinePaged(0, 10).single()
        val records = db.cleanupDao().getQuarantineGroup(head.groupId!!)
        assertEquals(files.map { it.path }.toSet(), records.map { it.originalPath }.toSet())
        assertEquals(LinkedPictureOperations.RestoreOutcome.RESTORED, ops.restore(records).outcome)
        assertTrue(files.all { it.isFile })
        assertArrayEquals(expected, jpg.readBytes())
        assertEquals(0, count("SELECT COUNT(*) FROM quarantine"))

        val jpgId = db.imageDao().getAnyByPaths(listOf(jpg.path)).single().id
        assertEquals("Alice", sources.observeImageOrigins(jpgId).first().single().senderName)
        // 归档（照常显示）后整组受保护
        db.imageDao().archive(listOf(jpgId), hidden = false)
        planner.batches(ids, PictureDeleteOptions(), bound) { groups ->
            assertEquals(PictureFileGroup.Block.PROTECTED, groups.single().blocked)
            assertEquals(PictureDeleteResult(skipped = 1), ops.permanentlyDeleteAll(groups))
        }
        assertTrue(files.all { it.isFile })
        db.imageDao().unarchive(listOf(jpgId))
        // 从 .jpg 本身发起清理也包含原图、预览图和两份缩略图。
        planner.batches(listOf(jpgId), PictureDeleteOptions(), bound) { groups ->
            assertEquals(PictureDeleteResult(pictures = 1, files = 5, selectedProcessed = 1), ops.permanentlyDeleteAll(groups))
        }
        assertTrue(files.none { it.exists() })
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
        override fun pinQqDatabase(userId: Int, account: String, name: String): Int = 0
        override fun unpinQqDatabase(handle: Int) = Unit
        override fun statFiles(paths: Array<String>): Array<String> = InProcessFileOps.statFiles(paths)
        override fun moveFilesChecked(sources: Array<String>, targets: Array<String>, stamps: Array<String>): BooleanArray = BooleanArray(sources.size)
        override fun deleteFilesChecked(paths: Array<String>, stamps: Array<String>): BooleanArray = InProcessFileOps.deleteFilesChecked(paths, stamps)
        override fun readImageInfo(paths: Array<String>): IntArray = InProcessFileOps.readImageInfo(paths)
        override fun replaceFileChecked(target: String, content: ParcelFileDescriptor, backup: String, stamp: String, mtimeMs: Long): Int =
            InProcessFileOps.replaceFileChecked(target, content, backup, stamp, mtimeMs)
        override fun restoreFileChecked(backup: String, target: String, stamp: String, mtimeMs: Long): Int =
            InProcessFileOps.restoreFileChecked(backup, target, stamp, mtimeMs)
        override fun writeFileChecked(target: String, content: ParcelFileDescriptor, mtimeMs: Long): Int =
            InProcessFileOps.writeFileChecked(target, content, mtimeMs)
        override fun repairOwnership(paths: Array<String>): IntArray = InProcessFileOps.repairOwnership(paths)
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
        return QuarantineManager(context, koin.get(), db, db.imageDao(), db.cleanupDao(), koin.get(), koin.get(), archive())
    }

    private fun archive() = ArchiveManager(db, GlobalContext.get().get<SettingsRepository>(), GlobalContext.get().get<CoroutineScope>())

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
    fun archivedPicturesAreDeletedOnlyFromTheArchiveAndRestoreIntoIt() = runBlocking {
        val (families, ids) = seed(3)
        db.imageDao().archive(ids, hidden = true)
        val ops = operations(manager())
        val planner = PictureFamilyPlanner(db, root.path)
        var result = PictureDeleteResult()
        // 其他入口：归档的图不在可删除的范围里
        planner.batches(ids, PictureDeleteOptions(), bound) { groups -> result += ops.quarantineAll(groups, "测试", Long.MAX_VALUE) }
        assertEquals(PictureDeleteResult(), result)
        assertTrue(families.all { family -> family.files.all { it.isFile } })
        // 归档页删除：连同还没入库的高清缩略图一起移入回收站，归档标记保留
        planner.batches(ids, PictureDeleteOptions(includeArchived = true), bound) { groups ->
            result += ops.quarantineAll(groups, "测试", Long.MAX_VALUE, archived = true)
        }
        assertEquals(PictureDeleteResult(pictures = 3, files = 12, selectedProcessed = 3), result)
        assertEquals(0, families.sumOf { family -> family.files.count { it.exists() } })
        assertEquals(9, count("SELECT COUNT(*) FROM images WHERE status = 'quarantined' AND is_protected = 1"))
        // 恢复后回到归档（整张图，包括后来登记的高清缩略图）
        val head = db.cleanupDao().listQuarantinePaged(0, 10).first()
        assertEquals(LinkedPictureOperations.RestoreOutcome.RESTORED, ops.restore(db.cleanupDao().getQuarantineGroup(head.groupId!!)).outcome)
        archive().sync(visible = false)
        assertEquals(4, count("SELECT COUNT(*) FROM images WHERE status = 'archived' AND is_protected = 1"))
    }

    @Test
    fun restoreCanKeepFilesQqDownloadedAgain() = runBlocking {
        val (families, ids) = seed(1)
        val family = families.single()
        val originals = family.files.associate { it.path to it.readBytes() }
        // 从归档里删的：恢复后要回到归档
        db.imageDao().archive(ids, hidden = false)
        val ops = operations(manager())
        PictureFamilyPlanner(db, root.path).batches(ids, PictureDeleteOptions(includeArchived = true), bound) { groups ->
            ops.quarantineAll(groups, "测试", Long.MAX_VALUE, archived = true)
        }
        assertTrue(family.files.none { it.exists() })
        val records = db.cleanupDao().getQuarantineGroup(db.cleanupDao().listQuarantinePaged(0, 10).single().groupId!!)
        val oldRaw = records.single { it.originalPath == family.raw.path }.imageId

        // QQ 又下载了原图（扫描已入库）和预览图（还没扫描）
        val again = jpeg(1600, 1200).copyOf(30_000)
        family.raw.writeBytes(again)
        family.img.writeBytes(again)
        val newRaw = db.imageDao().insert(
            ImageEntity(
                path = family.raw.path, filename = family.raw.name, parentDir = family.raw.parent!!, sourceType = "chatraw",
                cacheKey = family.raw.name, variantGroup = QqCachePaths.familyKey(family.raw.path), size = again.size.toLong(),
                mtime = 1, firstSeen = 1, lastSeen = 1, mime = "image/jpeg", width = 1, height = 1, status = "active",
            ),
        )
        // 默认不动：整组留在回收站
        assertEquals(LinkedPictureOperations.RestoreOutcome.OCCUPIED, ops.restore(records).outcome)
        assertEquals(records.size, count("SELECT COUNT(*) FROM quarantine"))

        // 用现有的文件：原图、预览图保持 QQ 的新文件，其余从回收站送回；回收站清空
        val result = ops.restore(records, keepExisting = true)
        assertEquals(LinkedPictureOperations.RestoreOutcome.RESTORED, result.outcome)
        assertArrayEquals(again, family.raw.readBytes())
        assertArrayEquals(again, family.img.readBytes())
        assertArrayEquals(originals.getValue(family.thumb.path), family.thumb.readBytes())
        assertArrayEquals(originals.getValue(family.hd.path), family.hd.readBytes())
        assertEquals(0, count("SELECT COUNT(*) FROM quarantine"))
        assertEquals(0, trash.walk().count { it.isFile })
        // 旧原图记录作废，新原图接着归档；预览图的旧记录指回原位置
        assertEquals("deleted", db.imageDao().getById(oldRaw)!!.status)
        assertEquals(listOf(newRaw), result.archive)
        assertEquals(family.img.path, db.imageDao().getAnyByPaths(listOf(family.img.path)).single().path)
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
        val contents = families.flatMap { it.files }.associate { it.path to it.readBytes() }
        val mtimes = families.flatMap { it.files }.associate { it.path to it.lastModified() }
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

        // 恢复时改名同样送不回去（各应用目录有各自的存储配额）：复制回原位置，内容与修改时间不变，回收站副本删掉
        records.map { it[3]!! }.distinct().forEach { group ->
            assertEquals(LinkedPictureOperations.RestoreOutcome.RESTORED, ops.restore(db.cleanupDao().getQuarantineGroup(group)).outcome)
        }
        contents.forEach { (path, bytes) ->
            assertArrayEquals(bytes, File(path).readBytes())
            assertEquals(mtimes.getValue(path), File(path).lastModified())
        }
        assertEquals(0, count("SELECT COUNT(*) FROM quarantine"))
        assertTrue(records.none { File(it[2]!!).exists() })
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
