package com.xjw.qalbum.fileops

import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xjw.qalbum.appContext
import com.xjw.qalbum.domain.model.AccessMode
import com.xjw.qalbum.service.indexer.transport.AidlScanTransport
import com.xjw.qalbum.service.indexer.transport.DirectoryReadException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 特权进程里给 QQ 缓存页与视频页用的目录操作（在测试进程里直接跑同一份 [FileOpsImpl]） */
@RunWith(AndroidJUnit4::class)
class FileOpsTreeTest {
    private val root = File(appContext.filesDir, "fileops-tree-test")

    @Before
    fun setUp() {
        root.deleteRecursively()
        root.mkdirs()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun write(relative: String, bytes: Int): File =
        File(root, relative).apply { parentFile!!.mkdirs(); writeBytes(ByteArray(bytes)) }

    @Test
    fun missingAndEmptyScanDirectoriesStillReturnEmptyPages() {
        val empty = File(root, "empty").apply { mkdirs() }
        for (dir in listOf(empty.path, "${root.path}/missing")) {
            assertTrue(FileOpsImpl.listSubdirs(dir, 0, 10).isEmpty())
            assertTrue(FileOpsImpl.listFiles(dir, 0, 10).isEmpty())
        }
    }

    @Test
    fun deniedScanDirectoriesReportPermissionInsteadOfEmptyPages() {
        val denied = write("denied/file", 1).parentFile!!
        Os.chmod(denied.path, 0)
        try {
            val transport = AidlScanTransport(inProcessFiles(), AccessMode.SHIZUKU)
            val dirs = assertThrows(DirectoryReadException::class.java) { runBlocking { transport.listSubdirs(denied.path) } }
            val files = assertThrows(DirectoryReadException::class.java) { runBlocking { transport.listFiles(listOf(denied.path)).toList() } }
            assertEquals(OsConstants.EACCES, dirs.errno)
            assertEquals(OsConstants.EACCES, files.errno)
            assertEquals(denied.path, files.path)
            transport.close()
        } finally {
            Os.chmod(denied.path, 0x1C0)
        }
    }

    @Test
    fun permissionLostBetweenPagesDoesNotHideFiles() {
        val dir = write("paged/a", 1).parentFile!!
        write("paged/b", 2)
        assertEquals(FileOpsImpl.MORE_MARKER, FileOpsImpl.listFiles(dir.path, 0, 1).last())
        Os.chmod(dir.path, 0)
        try {
            assertEquals(
                listOf("${FileOpsImpl.DIRECTORY_ERROR_MARKER}${OsConstants.EACCES}|${dir.path}/b"),
                FileOpsImpl.listFiles(dir.path, 1, 1).toList(),
            )
        } finally {
            Os.chmod(dir.path, 0x1C0)
        }
    }

    @Test
    fun statsCountFilesRecursivelyWithoutFollowingLinks() {
        write("cache/a", 10)
        write("cache/sub/b", 20)
        write("cache/sub/deeper/c", 30)
        val outside = write("outside/big", 1000)
        Os.symlink(outside.parent!!, File(root, "cache/link").path)
        val stats = FileOpsImpl.pathStats(arrayOf("${root.path}/cache", "${root.path}/missing", "${root.path}/cache/a"))
        // 符号链接本身算一项（大小是链接内容的长度），不进去数
        val link = Os.lstat("${root.path}/cache/link").st_size
        assertArrayEquals(longArrayOf(FileOpsImpl.PATH_PRESENT, 4, 60 + link, FileOpsImpl.PATH_MISSING, 0, 0, FileOpsImpl.PATH_PRESENT, 1, 10), stats)
    }

    @Test
    fun largestChildrenComeFirst() {
        write("cache/small", 5)
        write("cache/dir/x", 50)
        write("cache/dir/y", 50)
        write("cache/mid", 60)
        val children = FileOpsImpl.largestChildren("${root.path}/cache", 2).toList()
        assertEquals(listOf("D|2|100|dir", "F|1|60|mid"), children)
    }

    @Test
    fun clearingEmptiesTheDirectoryButKeepsItAndNeverLeavesIt() {
        write("cache/a", 10)
        write("cache/sub/b", 20)
        val outside = write("outside/keep", 7)
        Os.symlink(outside.parent!!, File(root, "cache/sub/link").path)
        val result = FileOpsImpl.clearContentsUnchecked("${root.path}/cache")
        // 两个文件 + 链接本身（大小是链接内容的长度）
        assertEquals(3L, result[0])
        assertEquals(0L, result[2])
        assertTrue(result[1] > 30L)
        val cache = File(root, "cache")
        assertTrue(cache.isDirectory)
        assertEquals(0, cache.list()!!.size)
        // 链接指向的目录与文件原样保留
        assertTrue(outside.isFile)
    }

    @Test
    fun theGuardRefusesAnythingThatIsNotAQqCacheDirectory() {
        write("cache/a", 10)
        val result = FileOpsImpl.clearQqCache(arrayOf("${root.path}/cache"))
        assertEquals(-1L, result[0])
        assertTrue(File(root, "cache/a").isFile)
    }

    @Test
    fun walkingListsNestedFilesSkipsHiddenOnesAndPages() {
        write("v/2024-01/a.mp4", 3)
        write("v/2024-01/b.mp4", 4)
        write("v/thumbs/a.jpg", 1)
        write("v/.tmp/partial.mp4", 9)
        write("v/.nomedia", 0)
        write("v/c.mp4", 5)
        val base = "${root.path}/v"
        val first = FileOpsImpl.walkFiles(base, 0, 2).toList()
        assertEquals(FileOpsImpl.MORE_MARKER, first.last())
        val second = FileOpsImpl.walkFiles(base, 2, 2).toList()
        val all = (first + second).filter { it != FileOpsImpl.MORE_MARKER }
        assertFalse(second.contains(FileOpsImpl.MORE_MARKER))
        assertEquals(listOf("2024-01/a.mp4", "2024-01/b.mp4", "c.mp4", "thumbs/a.jpg"), all.map { it.split('|', limit = 3)[2] })
        assertEquals("3", all.first().split('|')[0])
    }
}
