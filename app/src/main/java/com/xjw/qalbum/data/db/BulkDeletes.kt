package com.xjw.qalbum.data.db

import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * 删掉几百 MB 以上的数据（精简数据库、清除来源索引）时的数据库设置。
 *
 * Android 的 SQLite 默认安全删除（删掉的页先写零）、自动回收（auto_vacuum = FULL：每次提交把空出来的页全部搬走）：
 * 删除和回收挤在一个提交里时要多写上 GB、临时占用一两 GB，手机上卡住几分钟甚至内存不足。所以期间在写连接上关掉安全删除、
 * 切到增量回收（两种回收模式之间可以直接切换），分批删完再由 [reclaim] 一次回收。
 */
internal object BulkDeletes {

    /**
     * 在大批量删除的设置下执行 [block]。结束后恢复安全删除；没走到 [reclaim]（中途停止）时空页多就留在增量回收：
     * 这时切回自动回收，下一次随便一个提交都要把空页一次收完。空页留着给以后写入，下次精简或清除时再回收。
     */
    suspend fun <T> run(db: AppDatabase, block: suspend (SupportSQLiteDatabase) -> T): T {
        val sql = db.openHelper.writableDatabase
        val secure = db.withTransaction { sql.long("PRAGMA secure_delete") }
        db.withTransaction { sql.long("PRAGMA secure_delete = 0") }
        db.withTransaction { sql.long("PRAGMA auto_vacuum = $INCREMENTAL") }
        try {
            return block(sql)
        } finally {
            withContext(NonCancellable) {
                if (sql.long("PRAGMA freelist_count") <= STEP_PAGES) db.withTransaction { sql.long("PRAGMA auto_vacuum = $FULL") }
                db.withTransaction { sql.long("PRAGMA secure_delete = $secure") }
            }
        }
    }

    /**
     * 把空页还给系统：切回自动回收，这个提交里收完。只搬还在用的、位置在新末尾之后的页，空页直接截掉；
     * 分步的增量回收每回收一页都要在空页表里找它，空页上百万时要几个小时。期间别的写入等一会儿，读照常。
     */
    suspend fun reclaim(db: AppDatabase, sql: SupportSQLiteDatabase, progress: (String, Long, Long) -> Unit) {
        currentCoroutineContext().ensureActive()
        progress("释放空间", 0, 0)
        db.withTransaction { sql.long("PRAGMA auto_vacuum = $FULL") }
    }

    private fun SupportSQLiteDatabase.long(statement: String): Long =
        query(statement).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L }

    private const val FULL = 1L
    private const val INCREMENTAL = 2L

    /** 空页少于这么多（16 MB）时切回自动回收不会卡 */
    private const val STEP_PAGES = 4_096
}
