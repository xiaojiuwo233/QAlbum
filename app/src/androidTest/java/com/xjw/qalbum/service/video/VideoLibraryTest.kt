package com.xjw.qalbum.service.video

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xjw.qalbum.TestDatabase
import com.xjw.qalbum.appContext
import com.xjw.qalbum.data.db.entity.VideoEntity
import com.xjw.qalbum.fileops.inProcessFiles
import com.xjw.qalbum.qq.QqStorageRoots
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File

/** 视频页的扫描、重扫与删除（文件操作在本进程内执行，走与特权进程相同的 FileOpsImpl） */
@RunWith(AndroidJUnit4::class)
class VideoLibraryTest {
    @get:Rule val database = TestDatabase("video-library-test.db")
    private val db get() = database.db
    private val base = File(appContext.getExternalFilesDir(null), "video-library-test")
    private val roots = QqStorageRoots(0, "${base.path}/internal", base.path)
    private val bound = inProcessFiles()

    @Before
    fun setUp() {
        base.deleteRecursively()
    }

    @After
    fun tearDown() {
        base.deleteRecursively()
    }

    private fun library(): VideoLibrary {
        val koin = GlobalContext.get()
        return VideoLibrary(appContext, db, koin.get(), koin.get(), koin.get())
    }

    private fun ftyp(size: Int, brand: String = "isom"): ByteArray = ByteArray(size).also { bytes ->
        (byteArrayOf(0, 0, 0, 0x20) + "ftyp$brand".toByteArray(Charsets.ISO_8859_1)).copyInto(bytes)
    }

    private fun write(dir: String, relative: String, bytes: ByteArray): File =
        File(dir, relative).apply { parentFile!!.mkdirs(); writeBytes(bytes) }

    private suspend fun all(): List<VideoEntity> =
        db.videoDao().bySource(VideoEntity.SOURCE_CHAT) + db.videoDao().bySource(VideoEntity.SOURCE_FILE)

    @Test
    fun videosAreFoundByNameOrHeaderAndOtherFilesAreSkipped() = runBlocking {
        val named = write(roots.shortVideo, "2024-05/a.mp4", ftyp(1_000))
        val bare = write(roots.shortVideo, "Cache_1a2b3c", ftyp(20_000))
        write(roots.shortVideo, "thumbs/a.jpg", ByteArray(20_000) { 0xFF.toByte() })
        write(roots.shortVideo, "Cache_heif", ftyp(20_000, "heic"))
        val received = write(roots.fileRecv, "movie.mkv", ByteArray(500))
        write(roots.fileRecv, "doc.pdf", ByteArray(50_000))
        write(roots.fileRecv, ".tmp/partial.mp4", ftyp(1_000))

        assertEquals(3, library().scan(bound, roots))
        val rows = all()
        assertEquals(setOf(named.path, bare.path), rows.filter { it.source == VideoEntity.SOURCE_CHAT }.map { it.path }.toSet())
        assertEquals(listOf(received.path), rows.filter { it.source == VideoEntity.SOURCE_FILE }.map { it.path })
        assertTrue(rows.all { it.durationMs < 0 && it.size > 0 })
        assertEquals(named.lastModified() / 1000 * 1000, rows.first { it.path == named.path }.mtime)
    }

    @Test
    fun rescanKeepsUnchangedRowsResetsChangedOnesAndDropsMissingOnes() = runBlocking {
        val same = write(roots.shortVideo, "same.mp4", ftyp(1_000))
        val changed = write(roots.shortVideo, "changed.mp4", ftyp(1_000))
        val removed = write(roots.fileRecv, "removed.mp4", ftyp(1_000))
        val lib = library()
        lib.scan(bound, roots)
        all().forEach { db.videoDao().setMetadata(it.id, it.size, it.mtime, 5_000, 640, 360) }
        val before = all().associateBy { it.path }

        changed.writeBytes(ftyp(2_000))
        removed.delete()
        assertEquals(2, lib.scan(bound, roots))
        val after = all().associateBy { it.path }
        assertEquals(setOf(same.path, changed.path), after.keys)
        assertEquals(before.getValue(same.path), after.getValue(same.path).copy(seenAt = before.getValue(same.path).seenAt))
        assertEquals(before.getValue(changed.path).id, after.getValue(changed.path).id)
        assertEquals(-1L, after.getValue(changed.path).durationMs)
        assertEquals(2_000L, after.getValue(changed.path).size)
    }

    @Test
    fun aFolderThatDisappearsTakesItsVideosWithIt() = runBlocking {
        write(roots.fileRecv, "a.mp4", ftyp(1_000))
        val lib = library()
        assertEquals(1, lib.scan(bound, roots))
        File(roots.fileRecv).deleteRecursively()
        assertEquals(0, lib.scan(bound, roots))
        assertTrue(all().isEmpty())
    }

    @Test
    fun deletingChecksTheFileIsStillTheScannedOne() = runBlocking {
        val keep = write(roots.shortVideo, "keep.mp4", ftyp(1_000))
        val delete = write(roots.shortVideo, "delete.mp4", ftyp(1_500))
        val replaced = write(roots.shortVideo, "replaced.mp4", ftyp(1_000))
        val gone = write(roots.fileRecv, "gone.mp4", ftyp(1_000))
        val lib = library()
        lib.scan(bound, roots)
        val ids = all().associate { it.path to it.id }

        replaced.writeBytes(ftyp(3_000))
        gone.delete()
        val report = lib.delete(listOf(ids.getValue(delete.path), ids.getValue(replaced.path), ids.getValue(gone.path)), bound)
        assertEquals(VideoLibrary.DeleteReport(deleted = 1, bytes = 1_500, gone = 1, failed = 1), report)
        assertFalse(delete.exists())
        // 扫描后被换掉的文件不删，记录也留着（下次扫描更新）
        assertTrue(replaced.isFile)
        assertTrue(keep.isFile)
        assertEquals(setOf(keep.path, replaced.path), all().map { it.path }.toSet())
    }
}
