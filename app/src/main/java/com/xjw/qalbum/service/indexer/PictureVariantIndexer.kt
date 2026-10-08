package com.xjw.qalbum.service.indexer

import androidx.sqlite.db.SupportSQLiteDatabase
import com.xjw.qalbum.data.db.AppDatabase
import com.xjw.qalbum.data.db.ImageVariantSql
import com.xjw.qalbum.data.db.dao.ImageDao
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.qq.QqCachePaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 图片家族回填（升级到 v18 后执行一次；新扫描的图片入库时已带家族键）。
 *
 * 1. 撤掉家族索引与触发器，按 id 分页算出每张图的家族键并写入（几千行一个短事务），写完恢复；
 * 2. 按 id 段重算家族的代表图、版本数与总大小（只写有变化的行，此时触发器已在维护后续变化）；
 * 3. 解散旧版查重把同一张图的不同版本当成的「重复组」。
 *
 * 写入直接在底层连接上开事务：Room 的事务每次提交都会通知所有观察 images 的查询（图库分页、
 * 日期分组计数等）整体重查，回填的上百个小事务会让界面反复刷新、也拖慢回填本身。全部写完后统一通知一次。
 * 回填期间放宽 WAL 自动检查点（系统默认每 100 页就回写一次，每个事务都要同步落盘），结束后还原。
 */
class PictureVariantIndexer(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
) {
    private val mutex = Mutex()

    suspend fun ensureIndexed() = withContext(Dispatchers.IO) {
        if (settings.variantIndexVersion.first() >= VERSION) return@withContext
        mutex.withLock {
            if (settings.variantIndexVersion.first() >= VERSION) return@withLock
            val dao = db.imageDao()
            // 首次访问会打开数据库并执行迁移，不计入回填耗时
            val sql = db.openHelper.writableDatabase
            val started = System.currentTimeMillis()
            val checkpoint = sql.quietTransaction { sql.pragma("wal_autocheckpoint") }
                ?.takeIf { it > 0 } ?: DEFAULT_CHECKPOINT_PAGES
            sql.quietTransaction { sql.pragma("wal_autocheckpoint = $BULK_CHECKPOINT_PAGES") }
            try {
                val keyed = writeKeys(dao, sql)
                val keysDone = System.currentTimeMillis()
                recompute(dao, sql)
                val recomputed = System.currentTimeMillis()
                sql.quietTransaction { ImageVariantSql.dissolveFamilyDuplicates.forEach(sql::execSQL) }
                val done = System.currentTimeMillis()
                Timber.i(
                    "Picture families indexed: %d keyed files in %d ms (keys %d, recompute %d, duplicates %d)",
                    keyed, done - started, keysDone - started, recomputed - keysDone, done - recomputed,
                )
            } finally {
                sql.quietTransaction { sql.pragma("wal_autocheckpoint = $checkpoint") }
            }
            db.invalidationTracker.refreshVersionsAsync()
            settings.setVariantIndexVersion(VERSION)
        }
    }

    /** 第 1 步，返回有家族键的文件数 */
    private suspend fun writeKeys(dao: ImageDao, sql: SupportSQLiteDatabase): Int {
        sql.quietTransaction { ImageVariantSql.suspendForBackfill(sql) }
        try {
            var keyed = 0
            var afterId = 0L
            while (true) {
                val rows = dao.variantKeyRows(afterId, KEY_PAGE)
                if (rows.isEmpty()) break
                val keys = rows.mapNotNull { row -> QqCachePaths.familyKey(row.path)?.let { row.id to it } }
                if (keys.isNotEmpty()) sql.quietTransaction {
                    sql.compileStatement("UPDATE images SET variant_group = ? WHERE id = ? AND variant_group IS NOT ?").use { statement ->
                        keys.forEach { (id, key) ->
                            statement.bindLong(1, key)
                            statement.bindLong(2, id)
                            statement.bindLong(3, key)
                            statement.executeUpdateDelete()
                            statement.clearBindings()
                        }
                    }
                }
                keyed += keys.size
                afterId = rows.last().id
            }
            return keyed
        } finally {
            sql.quietTransaction { ImageVariantSql.resumeAfterBackfill(sql) }
        }
    }

    /** 第 2 步：触发器恢复之后再取最大 id，写键期间新入库的图也在重算范围内 */
    private suspend fun recompute(dao: ImageDao, sql: SupportSQLiteDatabase) {
        val maxId = dao.maxId()
        var low = 0L
        while (low < maxId) {
            val high = minOf(low + RECOMPUTE_SPAN, maxId)
            val from = low
            sql.quietTransaction { sql.execSQL(ImageVariantSql.recomputeRange, arrayOf<Any>(from, high)) }
            low = high
        }
    }

    /** 不经过 Room 事务（不触发逐次通知）。事务绑定在当前线程，块不是内联的，编译期就不允许在里面挂起 */
    private fun <T> SupportSQLiteDatabase.quietTransaction(block: () -> T): T {
        beginTransactionNonExclusive()
        try {
            return block().also { setTransactionSuccessful() }
        } finally {
            endTransaction()
        }
    }

    /** 连接级设置要在写连接上执行，调用方放在事务里 */
    private fun SupportSQLiteDatabase.pragma(statement: String): Long? =
        query("PRAGMA $statement").use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }

    private companion object {
        /** 家族键规则版本；规则变化时递增即可让启动时重新回填 */
        const val VERSION = 1
        const val KEY_PAGE = 5_000
        const val RECOMPUTE_SPAN = 20_000L
        /** 回填期间 WAL 累积到约 40MB 才自动回写 */
        const val BULK_CHECKPOINT_PAGES = 10_000
        /** 读不到原值时还原成系统默认 */
        const val DEFAULT_CHECKPOINT_PAGES = 100L
    }
}
