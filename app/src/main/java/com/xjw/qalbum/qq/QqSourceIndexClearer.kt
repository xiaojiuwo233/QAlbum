package com.xjw.qalbum.qq

import androidx.room.withTransaction
import com.xjw.qalbum.data.db.AppDatabase
import com.xjw.qalbum.data.db.BulkDeletes
import com.xjw.qalbum.data.db.QqSourceStatsSql
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * 清除本应用的来源索引。一次级联删除全部账号会在一个事务里收集几千万行、占住写锁十几分钟，最后内存不足让整个进程崩溃；
 * 这里先清子表再清父表，每批单独提交，并在 [BulkDeletes] 的设置下删（不写零、提交时不搬页），删完再分步把空间还给系统。
 * 中途停下（或进程被杀）也不要紧：统计已经标脏，再清一次接着删。
 */
internal class QqSourceIndexClearer(private val db: AppDatabase) {
    suspend fun clear(onProgress: (String, Long, Long) -> Unit) = BulkDeletes.run(db) { sql ->
        db.withTransaction {
            sql.execSQL(QqSourceStatsSql.ENSURE_CACHE_ROW)
            sql.execSQL(QqSourceStatsSql.MARK_DIRTY)
        }
        var total = TABLES.sumOf { (table, _) ->
            sql.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getLong(0) }
        }
        var done = 0L
        for ((table, label) in TABLES) {
            onProgress(label, done, total)
            while (true) {
                currentCoroutineContext().ensureActive()
                val removed = db.withTransaction {
                    sql.delete(table, "rowid IN (SELECT rowid FROM $table ORDER BY rowid LIMIT $BATCH_SIZE)", null)
                }
                if (removed == 0) break
                done += removed
                // 扫描仍可入库，在来源删完之前可能补上少量关联。
                total = maxOf(total, done)
                onProgress(label, done, total)
            }
        }
        db.withTransaction { QqSourceStatsSql.clearDerived.forEach(sql::execSQL) }
        BulkDeletes.reclaim(db, sql, onProgress)
    }

    private companion object {
        // 每批占用的内存随批大小涨（一万行约几十 MB），批太小又慢
        const val BATCH_SIZE = 10_000

        val TABLES = listOf(
            // 中途退出后下次应从头读取，不能沿用已经删除的来源对应的水位。
            "qq_read_marks" to "清除读取进度",
            "qq_image_contexts" to "清除来源统计",
            "qq_source_members" to "清除来源统计",
            "image_qq_origins" to "清除图片关联",
            "qq_origin_paths" to "清除来源路径",
            "qq_origin_families" to "清除来源关联",
            "qq_origins" to "清除消息来源",
            "qq_source_facets" to "清除来源分类",
            "qq_source_accounts" to "清除账号索引",
        )
    }
}
