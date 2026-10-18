package com.xjw.qalbum.qq

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.protobuf.CodedOutputStream
import kotlinx.coroutines.runBlocking
import net.zetetic.database.DatabaseErrorHandler
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

/**
 * 消息表有坏页（QQ 库本来就坏的页）时：从索引取行号绕过去，按行号顺序读完其余的消息，水位推进到最后一条；
 * 没有可用索引时退回从末尾读，并报告没读完整。用不加密的库造 SQLite 层面的坏页（加密库的校验失败另当别论）。
 */
@RunWith(AndroidJUnit4::class)
class QqMessageReaderTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val file = File(context.cacheDir, "reader-test-${UUID.randomUUID()}.db")

    @Before
    fun setUp() {
        System.loadLibrary("sqlcipher")
    }

    @After
    fun tearDown() {
        file.delete()
        File(file.path + "-journal").delete()
    }

    @Test
    fun aDamagedPageIsReadAroundInRowidOrder() = runBlocking {
        create(rows = 600, indexed = true)
        val damaged = damageMiddleTableLeaf()
        open { db ->
            val read = mutableListOf<Long>()
            val stats = QqMessageReader(db).forEachBatch("group_msg_table", db.columns("group_msg_table")) { records, _ ->
                records.forEach { read += it.values[0].toLong() }
            }
            assertTrue("unreadable=${stats.unreadable}", stats.unreadable in 1..damaged)
            assertFalse(stats.incompleteTable)
            assertEquals(600, stats.inspected)
            assertEquals(600L, stats.maxRowid)
            assertEquals(600 - stats.unreadable, read.size)
            // 坏页前后都按行号顺序读
            assertEquals(read.sorted(), read)
            assertEquals(600L, read.last())

            // 从水位往后只读新消息
            db.execSQL(INSERT, arrayOf(601L, content(601), ByteArray(0)))
            val next = mutableListOf<Long>()
            val more = QqMessageReader(db).forEachBatch("group_msg_table", db.columns("group_msg_table"), startAfter = stats.maxRowid) { records, _ ->
                records.forEach { next += it.values[0].toLong() }
            }
            assertEquals(listOf(601L), next)
            assertEquals(1, more.inspected)
        }
    }

    @Test
    fun withoutAnIndexTheTailIsReadAndTheTableReportedIncomplete() = runBlocking {
        create(rows = 300, indexed = false)
        damageMiddleTableLeaf()
        open { db ->
            val read = mutableListOf<Long>()
            val stats = QqMessageReader(db).forEachBatch("group_msg_table", db.columns("group_msg_table")) { records, _ ->
                records.forEach { read += it.values[0].toLong() }
            }
            assertTrue(stats.incompleteTable)
            // 坏页之后的从末尾读到了
            assertTrue(300L in read)
            assertTrue(read.size < 300)
        }
    }

    // ---- 造库 ----

    private fun create(rows: Int, indexed: Boolean) = open { db ->
        db.execSQL(
            "CREATE TABLE group_msg_table (\"40001\" INTEGER, \"40020\" TEXT, \"40021\" TEXT, \"40027\" INTEGER, \"40030\" INTEGER, " +
                "\"40033\" INTEGER, \"40050\" INTEGER, \"40090\" TEXT, \"40093\" TEXT, \"40011\" INTEGER, \"40800\" BLOB, \"40900\" BLOB, \"40999\" BLOB)",
        )
        db.beginTransaction()
        // 每条带 1 KB 填充：消息表占一两百页，坏一页只丢几条
        for (id in 1L..rows) db.execSQL(INSERT, arrayOf(id, content(id.toInt()), ByteArray(1000) { (id + it).toByte() }))
        db.setTransactionSuccessful()
        db.endTransaction()
        if (indexed) db.execSQL("CREATE INDEX msg_time ON group_msg_table(\"40050\")")
    }

    /** 把中间一个消息表叶子页的格子数改成不可能的值（SQLite 读到这页就报库损坏），返回这页原来的格子数 */
    private fun damageMiddleTableLeaf(): Int = RandomAccessFile(file, "rw").use { raf ->
        val header = ByteArray(100).also { raf.readFully(it) }
        val pageSize = ((header[16].toInt() and 0xFF) shl 8 or (header[17].toInt() and 0xFF)).let { if (it == 1) 65536 else it }
        val leaves = (2..(raf.length() / pageSize).toInt()).filter { page ->
            raf.seek((page - 1L) * pageSize)
            raf.read() == 0x0D
        }
        val page = leaves[leaves.size / 2]
        raf.seek((page - 1L) * pageSize + 3)
        val cells = (raf.read() shl 8) or raf.read()
        raf.seek((page - 1L) * pageSize + 3)
        raf.write(byteArrayOf(0xFF.toByte(), 0xFF.toByte()))
        cells
    }

    private inline fun <T> open(block: (SQLiteDatabase) -> T): T = SQLiteDatabase.openDatabase(
        file.path, ByteArray(0), null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY,
        DatabaseErrorHandler { _, _ -> }, null,
    ).use(block)

    private fun content(n: Int): ByteArray = encode { out ->
        out.writeByteArray(40800, encode { element ->
            val md5 = ByteArray(16) { (n * 31 + it).toByte() }
            element.writeUInt64(45001, md5[0].toLong() and 0xff)
            element.writeInt32(45002, 2)
            element.writeByteArray(45406, md5)
            element.writeInt32(QqPictureCodec.PIC_TYPE_FIELD, 1000)
        })
    }

    private fun encode(block: (CodedOutputStream) -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        val out = CodedOutputStream.newInstance(bytes)
        block(out)
        out.flush()
        return bytes.toByteArray()
    }

    private companion object {
        const val INSERT = "INSERT INTO group_msg_table(\"40001\", \"40020\", \"40021\", \"40027\", \"40030\", \"40033\", \"40050\", " +
            "\"40090\", \"40093\", \"40011\", \"40800\", \"40999\") VALUES (?, 'u_alice', '', 0, 1001, 0, 1700000000, '', 'alice', 2, ?, ?)"
    }
}
