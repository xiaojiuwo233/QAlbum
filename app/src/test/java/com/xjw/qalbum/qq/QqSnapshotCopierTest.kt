package com.xjw.qalbum.qq

import com.google.protobuf.CodedOutputStream
import com.xjw.qalbum.fileops.QqReadAccess
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.random.Random

/**
 * 复制 QQ 数据库的一致性：QQ 运行中追加 WAL 不影响；复制主库途中 checkpoint 改写主库、但 WAL 没重新开始时也一致；
 * 主库没变、WAL 在复制它之前重新开始也一致；WAL 复制途中重新开始、两样都变了、QQ 一直占着锁都失败，不重试；
 * 复制主库途中已经对不上就停下。这里用本地文件模拟 QQ 的写入。
 */
class QqSnapshotCopierTest {
    private lateinit var qq: File
    private lateinit var out: File
    private val random = Random(7)
    private val body = random.nextBytes(300 * 1024)
    private val wal = walHeader(salt = 1) + random.nextBytes(100 * 1024)

    /** 模拟 QQ 那边：文件、修改计数、读取时的回调（用来在复制途中写入） */
    private inner class FakeQq(var pinResult: Int = 0) : QqSnapshotCopier.Source {
        val pins = mutableListOf<Int>()
        val modified = mutableMapOf<String, Int>()
        val opens = mutableMapOf<String, Int>()
        val unpinned = mutableListOf<Int>()
        var pinned = false
        /** 每个读取流读过 [crossed] 字节时调用一次（模拟 QQ 在复制途中写入） */
        var onRead: (name: String) -> Unit = {}
        var crossed = 128L * 1024

        fun file(name: String) = File(qq, name)

        fun write(name: String, bytes: ByteArray, append: Boolean = false) {
            if (append) file(name).appendBytes(bytes) else file(name).writeBytes(bytes)
            modified[name] = (modified[name] ?: 0) + 1
        }

        override suspend fun states(): Map<String, QqSnapshotCopier.FileState> =
            qq.listFiles().orEmpty().associate { it.name to QqSnapshotCopier.FileState(it.length(), "${it.length()}:${modified[it.name] ?: 0}") }

        override suspend fun open(name: String): InputStream {
            opens[name] = (opens[name] ?: 0) + 1
            val input = FileInputStream(file(name))
            return object : InputStream() {
                var read = 0L
                override fun read(): Int = input.read().also { if (it >= 0) read++ }
                override fun read(b: ByteArray, off: Int, len: Int): Int =
                    input.read(b, off, minOf(len, 64 * 1024)).also { n ->
                        if (n > 0) {
                            val before = read
                            read += n
                            if (before < crossed && read >= crossed) onRead(name)
                        }
                    }
                override fun close() = input.close()
            }
        }

        override suspend fun pin(name: String): Int = pinResult.also {
            pins += it
            pinned = it > 0
        }

        override suspend fun unpin(handle: Int) {
            unpinned += handle
            pinned = false
        }
    }

    @Before
    fun setUp() {
        qq = Files.createTempDirectory("qq").toFile()
        out = Files.createTempDirectory("snapshot").toFile()
    }

    @After
    fun tearDown() {
        qq.deleteRecursively()
        out.deleteRecursively()
    }

    private fun copier(source: FakeQq, checkEveryBytes: Long = Long.MAX_VALUE) = QqSnapshotCopier(source, out, { _, _ -> }, checkEveryBytes)

    private fun busy(block: suspend () -> Unit): Throwable? = runBlocking { runCatching { block() }.exceptionOrNull() }

    private fun seed(source: FakeQq, withWal: Boolean = true) {
        source.write("nt_msg.db", qqHeader() + body)
        if (withWal) source.write("nt_msg.db-wal", wal)
    }

    @Test
    fun `主库去掉 QQ 文件头复制，WAL 整个复制`() = runBlocking {
        val source = FakeQq(pinResult = 7)
        seed(source)
        val copies = copier(source).copyAll(Long.MAX_VALUE)

        val copy = copies.getValue("nt_msg.db")
        assertEquals("0123456789abcdef", copy.header.random)
        assertArrayEquals(body, copy.file.readBytes())
        assertArrayEquals(wal, File(out, "nt_msg.db-wal").readBytes())
        assertEquals(listOf(7), source.unpinned)
        // 加上了锁：复制时 QQ 在运行
        assertTrue(copy.qqRunning)
    }

    @Test
    fun `复制途中 WAL 追加新消息不影响`() = runBlocking {
        val source = FakeQq()
        seed(source)
        val frames = random.nextBytes(40 * 1024)
        // 复制主库时 QQ 收到新消息：只追加 WAL，主库不变
        source.onRead = { name -> if (name == "nt_msg.db") source.write("nt_msg.db-wal", frames, append = true) }
        val copy = copier(source).copyAll(Long.MAX_VALUE).getValue("nt_msg.db")

        assertEquals(1, source.opens["nt_msg.db"])
        assertArrayEquals(wal + frames, File(out, "nt_msg.db-wal").readBytes())
        assertFalse(copy.qqRunning)
    }

    @Test
    fun `复制 WAL 时之后追加的帧不必复制`() = runBlocking {
        val source = FakeQq()
        seed(source)
        source.crossed = 40L * 1024
        source.onRead = { name -> if (name == "nt_msg.db-wal") source.write("nt_msg.db-wal", random.nextBytes(8 * 1024), append = true) }
        copier(source).copyAll(Long.MAX_VALUE)

        assertArrayEquals(wal, File(out, "nt_msg.db-wal").readBytes())
    }

    @Test
    fun `复制主库途中 checkpoint、WAL 没重新开始时一致`() = runBlocking {
        val source = FakeQq()
        seed(source)
        // 复制主库时 QQ 做了 checkpoint（改写主库），WAL 没重新开始：改写的页都还在复制到的 WAL 里，一次就行
        source.onRead = { name -> if (name == "nt_msg.db") source.write("nt_msg.db", qqHeader() + body.copyOf().also { it[0] = 1 }) }
        copier(source).copyAll(Long.MAX_VALUE)

        assertEquals(1, source.opens["nt_msg.db"])
        assertArrayEquals(wal, File(out, "nt_msg.db-wal").readBytes())
    }

    @Test
    fun `主库没变、WAL 在复制它之前重新开始也一致`() = runBlocking {
        val source = FakeQq()
        seed(source)
        // 复制主库时 WAL 重新开始了（新的 salt，从头写），主库没变：复制到的是新一轮的帧，都比主库新
        val restarted = walHeader(salt = 2) + random.nextBytes(1024)
        source.onRead = { name -> if (name == "nt_msg.db") source.write("nt_msg.db-wal", restarted) }
        copier(source).copyAll(Long.MAX_VALUE)

        assertArrayEquals(restarted, File(out, "nt_msg.db-wal").readBytes())
    }

    @Test
    fun `WAL 复制途中重新开始就失败`() {
        val source = FakeQq()
        seed(source)
        // 复制 WAL 时它重新开始了（从头写、变短）：复制到的只是上一轮的前一段，比主库里回写的旧
        source.crossed = QqSnapshotCopier.WAL_HEADER_BYTES + 1L
        source.onRead = { name -> if (name == "nt_msg.db-wal") source.write("nt_msg.db-wal", walHeader(salt = 2) + random.nextBytes(1024)) }

        assertTrue(busy { copier(source).copyAll(Long.MAX_VALUE) } is QqDatabaseBusyException)
    }

    @Test
    fun `主库与 WAL 都变了就失败，不重试`() {
        val source = FakeQq(pinResult = QqReadAccess.PIN_FAILED)
        seed(source)
        // 没加上锁，复制主库途中 QQ checkpoint 并让 WAL 重新开始：主库与 WAL 都变了
        source.onRead = { name ->
            if (name == "nt_msg.db") {
                source.write("nt_msg.db", qqHeader() + body.copyOf().also { it[0] = 2 })
                source.write("nt_msg.db-wal", walHeader(salt = 9) + random.nextBytes(2048))
            }
        }

        assertTrue(busy { copier(source).copyAll(Long.MAX_VALUE) } is QqDatabaseBusyException)
        assertEquals(1, source.opens["nt_msg.db"])
        assertEquals(listOf(QqReadAccess.PIN_FAILED), source.pins)
        assertEquals(emptyList<Int>(), source.unpinned)
    }

    @Test
    fun `复制主库途中已经对不上就停下，不复制剩下的`() {
        val source = FakeQq()
        seed(source)
        source.crossed = 64L * 1024
        source.onRead = { name ->
            if (name == "nt_msg.db") {
                source.write("nt_msg.db", qqHeader() + body.copyOf().also { it[0] = 3 })
                source.write("nt_msg.db-wal", walHeader(salt = 5) + random.nextBytes(2048))
            }
        }

        assertTrue(busy { copier(source, checkEveryBytes = 64L * 1024).copyAll(Long.MAX_VALUE) } is QqDatabaseBusyException)
        assertTrue(File(out, "nt_msg.db").length() < body.size)
    }

    @Test
    fun `QQ 一直占着锁时不复制`() {
        val source = FakeQq(pinResult = QqReadAccess.PIN_BUSY)
        seed(source)

        assertTrue(busy { copier(source).copyAll(Long.MAX_VALUE) } is QqDatabaseBusyException)
        assertEquals(null, source.opens["nt_msg.db"])
    }

    @Test
    fun `拿到 pin 时 QQ 不能 checkpoint，一次就够`() = runBlocking {
        val source = FakeQq(pinResult = 3)
        seed(source)
        var deferred = false
        // QQ 想 checkpoint：拿不到锁就跳过，等放开后再做
        source.onRead = { name -> if (name == "nt_msg.db") { if (source.pinned) deferred = true else source.write("nt_msg.db", qqHeader() + body) } }
        copier(source).copyAll(Long.MAX_VALUE)

        assertTrue(deferred)
        assertEquals(1, source.opens["nt_msg.db"])
        assertEquals(listOf(3), source.unpinned)
    }

    @Test
    fun `没有 WAL 时只复制主库`() = runBlocking {
        val source = FakeQq()
        seed(source, withWal = false)
        copier(source).copyAll(Long.MAX_VALUE)

        assertFalse(File(out, "nt_msg.db-wal").exists())
        assertArrayEquals(body, File(out, "nt_msg.db").readBytes())
    }

    @Test
    fun `空间不足时复制之前就失败`() = runBlocking {
        val source = FakeQq()
        seed(source)
        val error = runCatching { copier(source).copyAll(freeSpace = 1024) }.exceptionOrNull()

        assertTrue(error?.message.orEmpty().contains("空间不足"))
        assertEquals(null, source.opens["nt_msg.db"])
    }

    /** QQ 数据库前 1024 字节：「QQ_NT DB」+ 长度 + protobuf（2 = random，4 = HMAC 算法） */
    private fun qqHeader(): ByteArray {
        val payload = ByteArrayOutputStream().also { bytes ->
            CodedOutputStream.newInstance(bytes).apply {
                writeString(2, "0123456789abcdef")
                writeString(4, "HMAC_SHA1")
                flush()
            }
        }.toByteArray()
        val header = ByteArray(1024)
        "QQ_NT DB".toByteArray().copyInto(header)
        ByteBuffer.wrap(header, 8, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(payload.size)
        payload.copyInto(header, 12)
        return header
    }

    /** WAL 文件头：magic、版本、页大小、checkpoint 序号、salt 1/2、校验和 */
    private fun walHeader(salt: Int): ByteArray = ByteBuffer.allocate(QqSnapshotCopier.WAL_HEADER_BYTES)
        .putInt(0x377f0682).putInt(3007000).putInt(4096).putInt(salt).putInt(salt).putInt(salt * 31).putInt(0).putInt(0)
        .array()
}
