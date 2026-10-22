package com.xjw.qalbum.data.db

import android.content.Context
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import com.xjw.qalbum.qq.QqCachePaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * 数据库的占用与精简（设置 → 存储空间）。
 *
 * 精简分两步：
 * 1. v27 以前每条聊天来源存了 4 条完整路径（见 [QqFamilyMigration]）：能由家族键推出的删掉，同一张图其他 MD5 的家族
 *    记进 qq_origin_families，推不出的留下；
 * 2. 把空出来的页还给系统。Android 的 SQLite 默认自动回收（auto_vacuum = FULL），一次删掉上 GB 的数据会在提交时
 *    搬动几十万页、临时占用一两 GB；VACUUM 的临时库又在内存里。所以先切到增量回收，删完再每次回收 16 MB
 *    （每步一个事务，写前日志随即检查点，临时占用小），做完切回自动回收。中途退出也不要紧，剩下的空页下次接着回收。
 */
class DatabaseSlimmer(private val context: Context, private val db: AppDatabase, private val name: String = AppDatabase.DB_NAME) {

    /** [legacyPaths] 旧版留下的路径行数，[legacyBytes] 是它们大约的占用 */
    data class Usage(
        val fileBytes: Long,
        val walBytes: Long,
        val freeBytes: Long,
        val legacyPaths: Long,
        val legacyBytes: Long,
        val images: Int,
        val sources: Int,
        val videos: Int,
    ) {
        val totalBytes: Long get() = fileBytes + walBytes
        /** 精简后大约能省下的 */
        val reclaimableBytes: Long get() = freeBytes + legacyBytes
    }

    suspend fun usage(): Usage = withContext(Dispatchers.IO) {
        val file = context.getDatabasePath(name)
        val sql = db.openHelper.readableDatabase
        val pageSize = sql.long("PRAGMA page_size")
        // 行号最大值约等于行数（只在清除来源索引时整表删除，之后从头编号），不必把几百万行数一遍
        val legacyPaths = sql.long("SELECT COALESCE(MAX(rowid), 0) FROM qq_origin_paths")
        // 每行：路径与消息 id 各存两份（表与主键索引），消息 id 索引一份，外加每格的开销
        val sample = sql.long("SELECT COALESCE(AVG(length(path)), 0) FROM (SELECT path FROM qq_origin_paths ORDER BY rowid DESC LIMIT 1000)")
        Usage(
            fileBytes = file.length(),
            walBytes = File(file.path + "-wal").length(),
            freeBytes = sql.long("PRAGMA freelist_count") * pageSize,
            legacyPaths = legacyPaths,
            legacyBytes = if (legacyPaths > SLIM_THRESHOLD) legacyPaths * (2 * (sample + ID_LENGTH) + ID_LENGTH + ROW_OVERHEAD) else 0,
            images = sql.long("SELECT COUNT(*) FROM images").toInt(),
            sources = sql.long("SELECT COUNT(*) FROM qq_origins").toInt(),
            videos = sql.long("SELECT COUNT(*) FROM videos").toInt(),
        )
    }

    /**
     * 精简；[progress]（说明, 已完成, 总数）。返回数据库文件省下的字节数。
     * 调用方保证期间不读取聊天来源（见 QqSourceRepository.exclusive）。
     */
    suspend fun slim(progress: (String, Long, Long) -> Unit): Long = withContext(Dispatchers.IO) {
        val file = context.getDatabasePath(name)
        val before = file.length() + File(file.path + "-wal").length()
        val sql = db.openHelper.writableDatabase
        val mode = db.withTransaction { sql.long("PRAGMA auto_vacuum") }
        // 系统的 SQLite 默认安全删除（删掉的页先写零），删上 GB 的数据就要多写上 GB；这个连接上先关掉
        val secure = db.withTransaction { sql.long("PRAGMA secure_delete") }
        db.withTransaction { sql.long("PRAGMA secure_delete = 0") }
        // 自动回收模式下删掉大表要在提交时一次搬完；切到增量回收（两种模式之间可以直接切换）
        if (mode == FULL) db.withTransaction { sql.long("PRAGMA auto_vacuum = $INCREMENTAL") }
        try {
            if (sql.long("SELECT COALESCE(MAX(rowid), 0) FROM qq_origin_paths") > SLIM_THRESHOLD) slimPaths(sql, progress)
            reclaim(sql, progress)
        } finally {
            if (mode == FULL) db.withTransaction { sql.long("PRAGMA auto_vacuum = $FULL") }
            db.withTransaction { sql.long("PRAGMA secure_delete = $secure") }
        }
        val after = file.length() + File(file.path + "-wal").length()
        Timber.i("Database slimmed: %d -> %d bytes", before, after)
        (before - after).coerceAtLeast(0)
    }

    /** 旧路径：能推出家族键的不再存，另一个家族的记下，推不出的留下（换成只有这些行的新表） */
    private suspend fun slimPaths(sql: SupportSQLiteDatabase, progress: (String, Long, Long) -> Unit) {
        val total = sql.long("SELECT COALESCE(MAX(rowid), 0) FROM qq_origin_paths")
        val kept = ArrayList<Pair<String, String>>()
        var after = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            progress("整理旧的图片路径", after, total)
            val rows = sql.query(
                SimpleSQLiteQuery(
                    """SELECT p.rowid, p.path, p.origin_id, o.family FROM qq_origin_paths p CROSS JOIN qq_origins o ON o.id = p.origin_id
                        WHERE p.rowid > ? ORDER BY p.rowid LIMIT $PAGE""",
                    arrayOf(after),
                ),
            ).use { c ->
                buildList { while (c.moveToNext()) add(PathRow(c.getLong(0), c.getString(1), c.getString(2), if (c.isNull(3)) null else c.getLong(3))) }
            }
            if (rows.isEmpty()) break
            val families = ArrayList<Pair<Long, String>>()
            for (row in rows) {
                val key = QqCachePaths.familyKey(row.path)
                when {
                    key == null -> kept += row.path to row.originId
                    key != row.family -> families += key to row.originId
                }
            }
            if (families.isNotEmpty()) db.withTransaction {
                families.forEach { (family, origin) ->
                    sql.execSQL("INSERT OR IGNORE INTO qq_origin_families(family, origin_id) VALUES(?, ?)", arrayOf<Any>(family, origin))
                }
            }
            after = rows.last().rowid
        }
        progress("删除不再需要的路径", total, total)
        db.withTransaction {
            sql.execSQL("DROP TABLE IF EXISTS temp.kept_paths")
            sql.execSQL("CREATE TEMP TABLE kept_paths(path TEXT NOT NULL, origin_id TEXT NOT NULL)")
            kept.forEach { (path, origin) -> sql.execSQL("INSERT INTO temp.kept_paths VALUES(?, ?)", arrayOf<Any>(path, origin)) }
            sql.execSQL("DROP TABLE qq_origin_paths")
            sql.execSQL(QqFamilyMigration.CREATE_PATHS)
            sql.execSQL(QqFamilyMigration.PATHS_INDEX)
            sql.execSQL("INSERT OR IGNORE INTO qq_origin_paths(path, origin_id) SELECT path, origin_id FROM temp.kept_paths")
            sql.execSQL("DROP TABLE temp.kept_paths")
        }
        Timber.i("Legacy source paths slimmed: %d rows, %d kept", total, kept.size)
    }

    /** 每次回收 [STEP_PAGES] 页，直到没有空页 */
    private suspend fun reclaim(sql: SupportSQLiteDatabase, progress: (String, Long, Long) -> Unit) {
        val pageSize = sql.long("PRAGMA page_size")
        val total = sql.long("PRAGMA freelist_count")
        var free = total
        while (free > 0) {
            currentCoroutineContext().ensureActive()
            progress("释放空间", (total - free) * pageSize, total * pageSize)
            db.withTransaction { sql.long("PRAGMA incremental_vacuum($STEP_PAGES)") }
            val left = sql.long("PRAGMA freelist_count")
            if (left >= free) break
            free = left
        }
    }

    private fun SupportSQLiteDatabase.long(statement: String): Long =
        query(statement).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L }

    private class PathRow(val rowid: Long, val path: String, val originId: String, val family: Long?)

    private companion object {
        const val FULL = 1L
        const val INCREMENTAL = 2L
        const val PAGE = 5_000
        const val STEP_PAGES = 4_096

        /** 旧路径少于这么多行（升级后新读到的都不存路径，只有少数推不出家族键的）不必精简 */
        const val SLIM_THRESHOLD = 1_000L

        /** 消息 id 的长度（SHA-256 十六进制） */
        const val ID_LENGTH = 64L
        const val ROW_OVERHEAD = 30L
    }
}
