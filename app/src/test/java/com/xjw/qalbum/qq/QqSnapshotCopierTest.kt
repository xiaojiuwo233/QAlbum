package com.xjw.qalbum.qq

import com.google.protobuf.CodedOutputStream
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
 * 主库没变时 WAL 重新开始也一致；两样都变了才重试（还没拿到 pin 就再拿）；一直这样则放弃。这里用本地文件模拟 QQ 的写入。
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
        /** 依次返回的 pin 结果，用完后返回 [pinResult] */
        var pinResults = ArrayDeque<Int>()
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

        override suspend fun pin(name: String): Int = (pinResults.removeFirstOrNull() ?: pinResult).also {
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

    private fun copier(source: FakeQq) = QqSnapshotCopier(source, out, { _, _ -> }, attempts = 3, retryDelayMs = 0)

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
    }

    @Test
    fun `复制途中 WAL 追加新消息不影响`() = runBlocking {
        val source = FakeQq()
        seed(source)
        val frames = random.nextBytes(40 * 1024)
        // 复制主库时 QQ 收到新消息：只追加 WAL，主库不变
        source.onRead = { name -> if (name == "nt_msg.db") source.write("nt_msg.db-wal", frames, append = true) }
        copier(source).copyAll(Long.MAX_VALUE)

        assertEquals(1, source.opens["nt_msg.db"])
        assertArrayEquals(wal + frames, File(out, "nt_msg.db-wal").readBytes())
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
    fun `主库没变时 WAL 重新开始也一致`() = runBlocking {
        val source = FakeQq()
        seed(source)
        // 复制 WAL 时它重新开始了（新的 salt，从头写），主库没变：复制到的帧要么已在主库里，要么是之后的新事务
        source.crossed = QqSnapshotCopier.WAL_HEADER_BYTES + 1L
        source.onRead = { name -> if (name == "nt_msg.db-wal") source.write("nt_msg.db-wal", walHeader(salt = 2) + random.nextBytes(1024)) }
        copier(source).copyAll(Long.MAX_VALUE)

        assertEquals(1, source.opens["nt_msg.db"])
    }

    @Test
    fun `主库与 WAL 都变了就重试，没拿到 pin 的再拿`() = runBlocking {
        val source = FakeQq(pinResult = 5)
        source.pinResults = ArrayDeque(listOf(-1))
        seed(source)
        var rounds = 0
        // 第一次（没拿到 pin）复制主库途中 QQ checkpoint 并让 WAL 重新开始：主库与 WAL 都变了，这一轮不一致
        source.onRead = { name ->
            if (name == "nt_msg.db" && rounds++ == 0) {
                source.write("nt_msg.db", qqHeader() + body.copyOf().also { it[0] = 2 })
                source.write("nt_msg.db-wal", walHeader(salt = 9) + random.nextBytes(2048))
            }
        }
        val copy = copier(source).copyAll(Long.MAX_VALUE).getValue("nt_msg.db")

        assertEquals(2, source.opens["nt_msg.db"])
        assertEquals(listOf(-1, 5), source.pins)
        assertEquals(listOf(5), source.unpinned)
        assertEquals(2, copy.file.readBytes()[0].toInt())
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
    fun `每次都碰上 checkpoint 和 WAL 重新开始时放弃`() = runBlocking {
        val source = FakeQq()
        seed(source)
        var salt = 10
        source.onRead = { name ->
            if (name == "nt_msg.db") {
                source.write("nt_msg.db", qqHeader() + body)
                source.write("nt_msg.db-wal", walHeader(salt = salt++) + random.nextBytes(1024))
            }
        }
        val error = runCatching { copier(source).copyAll(Long.MAX_VALUE) }.exceptionOrNull()

        assertTrue(error?.message.orEmpty().contains("正在更新"))
        assertEquals(3, source.opens["nt_msg.db"])
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
