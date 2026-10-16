package com.xjw.qalbum.qq

import android.database.sqlite.SQLiteDatabaseCorruptException
import com.google.protobuf.InvalidProtocolBufferException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import net.zetetic.database.sqlcipher.SQLiteDatabase
import timber.log.Timber

internal data class QqMessageRecord(
    val values: List<String>,
    val sentAt: Long,
    val pictures: List<QqPictureElement>,
    val forwardedPictures: List<QqForwardPicture> = emptyList(),
)

/** [maxRowid] 是检查过的最大 rowid（下次增量读取从它之后开始），一行都没读到为 0 */
internal data class QqMessageReadStats(
    val inspected: Int = 0,
    val skipped: Int = 0,
    val unreadable: Int = 0,
    val incompleteTable: Boolean = false,
    val maxRowid: Long = 0,
)

/**
 * 只读副本：先枚举有限数量的 rowid，再读这些记录，避免 CursorWindow 隐式扫描整张表。
 * [fieldStats] 不为空时顺带统计图片段的字段分布（诊断日志用）。
 */
internal class QqMessageReader(private val database: SQLiteDatabase, private val fieldStats: QqPictureFieldStats? = null) {
    /** 按 rowid 顺序分批读 [table]；[startAfter] 不为空时只读之后的新消息（增量） */
    suspend fun forEachBatch(
        table: String,
        columns: Set<String>,
        startAfter: Long? = null,
        consume: suspend (List<QqMessageRecord>, QqMessageReadStats) -> Unit,
    ): QqMessageReadStats {
        require(table in setOf("group_msg_table", "c2c_msg_table"))
        val fields = listOf("40001", "40020", "40021", "40027", "40030", "40033", "40050", "40090", "40093", "40011")
        val select = fields.joinToString(",") { if (it in columns) "[$it]" else "NULL" }
        val cache = if ("40900" in columns) "[40900]" else "NULL"
        val forwardCandidate = if ("40011" in columns) "[40011] IN (8,11)" else "1"
        // 只取含图片段（45002 = 2）、旧式转发段（16）、Ark 卡片段（10）或转发类型的消息：在 SQL 里按消息段的
        // 字节特征过滤，纯文字消息不必经 CursorWindow 送到这边解析（45002 的 tag 为 D0 FC 15，值都是单字节）
        val content = "CAST([40800] AS BLOB)"
        val candidates = "(instr($content, X'D0FC1502') > 0 OR instr($content, X'D0FC1510') > 0 OR " +
            "instr($content, X'D0FC150A') > 0 OR ($forwardCandidate AND $cache IS NOT NULL))"
        var after: Long? = startAfter
        var stats = QqMessageReadStats()

        suspend fun readRows(ids: List<Long>): Batch {
            currentCoroutineContext().ensureActive()
            return try {
                // 强制走表，避免 QQ 历史索引异常影响本来可读的图片记录；不重建任何索引。
                val sql = "SELECT $select, CASE WHEN length([40800]) <= ${QqPictureCodec.MAX_MESSAGE_BYTES} THEN [40800] ELSE NULL END, " +
                    "CASE WHEN $forwardCandidate AND length($cache) <= $INLINE_CACHE_BYTES THEN $cache ELSE NULL END, length($cache), length([40800]), rowid " +
                    "FROM $table NOT INDEXED WHERE rowid IN (${ids.joinToString(",") { "?" }}) AND $candidates ORDER BY rowid"
                val rows = mutableListOf<QqMessageRecord>()
                var skipped = 0
                database.rawQuery(sql, ids.map { it.toString() }.toTypedArray()).use { cursor ->
                    while (cursor.moveToNext()) {
                        currentCoroutineContext().ensureActive()
                        val decoded = if (cursor.isNull(10)) {
                            if (cursor.getLong(13) > QqPictureCodec.MAX_MESSAGE_BYTES) skipped++
                            QqDecodedElements(emptyList(), false)
                        } else try { QqPictureCodec.decodeElements(cursor.getBlob(10), fieldStats) }
                        catch (_: InvalidProtocolBufferException) { skipped++; QqDecodedElements(emptyList(), false) }
                        // 11 是合并转发；旧版用元素类型 16。回复（9）中的缓存不可归给回复者。
                        val forwarded = if (cursor.getInt(9) == 11 || (cursor.getInt(9) != 9 && decoded.hasForward)) {
                            val size = cursor.getLong(12)
                            when {
                                size <= 0 -> { skipped++; emptyList() }
                                size > QqForwardPictureCodec.MAX_CACHE_BYTES -> { skipped++; emptyList() }
                                else -> {
                                    val bytes = if (!cursor.isNull(11)) cursor.getBlob(11)
                                        else readCache(table, cursor.getLong(14), size.toInt())
                                    val cached = QqForwardPictureCodec.decode(bytes)
                                    if (cached.incomplete) skipped++
                                    cached.pictures
                                }
                            }
                        } else emptyList()
                        if (decoded.pictures.isNotEmpty() || forwarded.isNotEmpty()) {
                            rows += QqMessageRecord(fields.indices.map { cursor.text(it) }, cursor.getLong(6), decoded.pictures, forwarded)
                        }
                    }
                }
                // 整个批次读完才交给导入器，失败后二分重读不会重复累计前半批的图片。
                Batch(rows, skipped)
            } catch (_: SQLiteDatabaseCorruptException) {
                if (ids.size == 1) Batch(unreadable = 1)
                else {
                    val middle = ids.size / 2
                    val left = readRows(ids.subList(0, middle))
                    val right = readRows(ids.subList(middle, ids.size))
                    Batch(left.rows + right.rows, left.skipped + right.skipped,
                        left.unreadable + right.unreadable, left.incomplete || right.incomplete)
                }
            }
        }

        suspend fun consumeIds(ids: List<Long>) {
            val batch = readRows(ids.sorted())
            stats = QqMessageReadStats(stats.inspected + ids.size, stats.skipped + batch.skipped,
                stats.unreadable + batch.unreadable, stats.incompleteTable || batch.incomplete, maxOf(stats.maxRowid, ids.max()))
            consume(batch.rows, stats)
        }

        suspend fun recoverAfter(afterId: Long?): QqMessageReadStats {
            // 普通索引也存 rowid：覆盖索引可避开消息表的坏页，仍只枚举图片定位所需的行号。
            for (index in coveringIndexes(table)) {
                currentCoroutineContext().ensureActive()
                var delivered = false
                try {
                    val where = if (afterId == null) "" else " WHERE rowid > ?"
                    val args = afterId?.let { arrayOf(it.toString()) } ?: emptyArray<String>()
                    val sql = "SELECT rowid FROM $table INDEXED BY ${quote(index)}$where"
                    val covering = database.rawQuery("EXPLAIN QUERY PLAN $sql", args).use { plan ->
                        var found = false
                        while (plan.moveToNext()) if (plan.getString(3).contains("COVERING INDEX", true)) found = true
                        found
                    }
                    if (!covering) continue
                    database.rawQuery(sql, args).use { cursor ->
                        val ids = ArrayList<Long>(BATCH_SIZE)
                        while (cursor.moveToNext()) {
                            currentCoroutineContext().ensureActive()
                            ids += cursor.getLong(0)
                            if (ids.size == BATCH_SIZE) { consumeIds(ids); ids.clear(); delivered = true }
                        }
                        if (ids.isNotEmpty()) { consumeIds(ids); delivered = true }
                    }
                    Timber.i("QQ copied rowid recovery: table=%s method=covering_index", table)
                    return stats
                } catch (_: SQLiteDatabaseCorruptException) {
                    // 已交付的行不能在换索引后重复计数；复制期间仍保持原有来源不丢失。
                    if (delivered) return stats.copy(incompleteTable = true)
                }
            }
            // 索引也不可读时，从末尾读取坏页另一侧；未知缺口会明确保留在报告中。
            var before: Long? = null
            while (true) {
                currentCoroutineContext().ensureActive()
                val conditions = mutableListOf<String>()
                val args = mutableListOf<String>()
                afterId?.let { conditions += "rowid > ?"; args += it.toString() }
                before?.let { conditions += "rowid < ?"; args += it.toString() }
                val where = if (conditions.isEmpty()) "" else " WHERE " + conditions.joinToString(" AND ")
                val ids: List<Long> = try {
                    database.rawQuery("SELECT rowid FROM $table NOT INDEXED$where ORDER BY rowid DESC LIMIT $BATCH_SIZE", args.toTypedArray()).use { cursor ->
                        buildList<Long> { while (cursor.moveToNext()) add(cursor.getLong(0)) }
                    }
                } catch (_: SQLiteDatabaseCorruptException) { return stats.copy(incompleteTable = true) }
                if (ids.isEmpty()) return stats.copy(incompleteTable = true)
                consumeIds(ids)
                before = ids.last()
            }
        }

        while (true) {
            currentCoroutineContext().ensureActive()
            val ids: List<Long> = try {
                val where = if (after == null) "" else " WHERE rowid > ?"
                val args = after?.let { arrayOf(it.toString()) } ?: emptyArray<String>()
                database.rawQuery("SELECT rowid FROM $table NOT INDEXED$where ORDER BY rowid LIMIT $BATCH_SIZE", args).use { cursor ->
                    buildList<Long> { while (cursor.moveToNext()) add(cursor.getLong(0)) }
                }
            } catch (_: SQLiteDatabaseCorruptException) {
                Timber.w("QQ copied messages unreadable: table=%s operation=rowids", table)
                return recoverAfter(after)
            }
            if (ids.isEmpty()) return stats
            consumeIds(ids)
            after = ids.last()
        }
    }

    /** 大转发 BLOB 分块读取，避免单行超过 Android CursorWindow；SQL 仅对只读副本执行。 */
    private suspend fun readCache(table: String, rowid: Long, size: Int): ByteArray {
        val bytes = ByteArray(size)
        var offset = 0
        while (offset < size) {
            currentCoroutineContext().ensureActive()
            val count = minOf(CACHE_CHUNK_BYTES, size - offset)
            database.rawQuery("SELECT substr([40900],?,?) FROM $table NOT INDEXED WHERE rowid=?",
                arrayOf((offset + 1).toString(), count.toString(), rowid.toString())).use { cursor ->
                if (!cursor.moveToFirst()) throw SQLiteDatabaseCorruptException("Copied message record missing")
                val chunk = cursor.getBlob(0)
                if (chunk.size != count) throw SQLiteDatabaseCorruptException("Copied message cache incomplete")
                chunk.copyInto(bytes, offset)
            }
            offset += count
        }
        return bytes
    }

    private fun coveringIndexes(table: String): List<String> = database.rawQuery("PRAGMA index_list($table)", emptyArray<String>()).use { cursor ->
        buildList {
            val partial = cursor.getColumnIndex("partial")
            while (cursor.moveToNext()) if (partial < 0 || cursor.getInt(partial) == 0) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
        }
    }

    private fun quote(identifier: String) = "\"${identifier.replace("\"", "\"\"")}\""

    private data class Batch(
        val rows: List<QqMessageRecord> = emptyList(),
        val skipped: Int = 0,
        val unreadable: Int = 0,
        val incomplete: Boolean = false,
    )

    private companion object {
        const val BATCH_SIZE = 128
        const val INLINE_CACHE_BYTES = 256 * 1024
        const val CACHE_CHUNK_BYTES = 128 * 1024
    }
}
