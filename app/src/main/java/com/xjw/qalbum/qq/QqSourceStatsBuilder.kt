package com.xjw.qalbum.qq

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.xjw.qalbum.data.db.AppDatabase
import com.xjw.qalbum.data.db.QqSourceStatsSql
import com.xjw.qalbum.data.db.QqSourceStatsSql.FacetKind
import com.xjw.qalbum.data.db.dao.QqReadBaseline
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * 来源统计的整体重建（第一次读取、升级、清除索引后执行；日常删图由触发器、读取来源由 [applyNew] 增量维护）。
 *
 * 分四步、每段一个短事务，期间其他写入（删图、扫描）可以插队；每段只在内存里放一小段（见 QqSourceStatsSql 开头），
 * 几百万条来源也不会把内存用光：
 * 1. 补齐分类（沿索引按键分组，每段几百个键），已有的分类按全部来源重算名称；
 * 2. 群名片与最近时间：按来源行号分段汇总并入，再由群名片算发送人的搜索文本；
 * 3. 按图片 id 分段，把消息关联投影进 qq_image_contexts（已有行跳过，浏览不受影响）；
 * 4. 补上重建期间新入库的图片，按当前状态重算计数并清除脏标记。
 */
internal class QqSourceStatsBuilder(private val db: AppDatabase) {

    suspend fun rebuild(onProgress: (Float?) -> Unit) {
        val sql = db.openHelper.writableDatabase
        onProgress(null)
        val existing = db.withTransaction {
            sql.execSQL(QqSourceStatsSql.ENSURE_CACHE_ROW)
            sql.long("SELECT COALESCE(MAX(id), 0) FROM qq_source_facets")
        }
        for (kind in FacetKind.entries) {
            var after = ""
            while (true) {
                currentCoroutineContext().ensureActive()
                val upTo = db.withTransaction {
                    QqSourceStatsSql.facetKeyBoundary(sql, kind, after, FACET_KEYS).also { QqSourceStatsSql.insertFacets(sql, kind, after, it) }
                }
                after = upTo ?: break
            }
            var id = 0L
            while (id < existing) {
                currentCoroutineContext().ensureActive()
                val from = id
                id = minOf(id + FACET_IDS, existing)
                db.withTransaction { QqSourceStatsSql.refreshFacets(sql, kind, from, id) }
            }
        }

        val lastOrigin = db.withTransaction {
            sql.execSQL("DELETE FROM qq_source_members")
            sql.long("SELECT COALESCE(MAX(rowid), 0) FROM qq_origins")
        }
        var origin = 0L
        while (origin < lastOrigin) {
            currentCoroutineContext().ensureActive()
            val from = origin
            origin = minOf(origin + QqSourceStatsSql.MEMBER_ROWS, lastOrigin)
            db.withTransaction { QqSourceStatsSql.mergeMembers(sql, from, origin) }
            onProgress(MEMBERS_SHARE * origin / lastOrigin)
        }
        db.withTransaction { QqSourceStatsSql.updateSenderSearchText(sql, touchedOnly = false) }

        val (minId, maxId) = db.query(SimpleSQLiteQuery(QqSourceStatsSql.LINKED_IMAGE_RANGE)).use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(0) to cursor.getLong(1)
        }
        val span = (maxId - minId + 1).coerceAtLeast(1)
        var done = minId - 1
        while (done < maxId) {
            currentCoroutineContext().ensureActive()
            val upTo = minOf(done + IMAGE_ID_RANGE, maxId)
            val from = done
            db.withTransaction { QqSourceStatsSql.contextsInRange(sql, from, upTo) }
            done = upTo
            onProgress(MEMBERS_SHARE + (1 - MEMBERS_SHARE) * (done - minId + 1) / span)
        }

        db.withTransaction {
            QqSourceStatsSql.contextsInRange(sql, maxId, Long.MAX_VALUE)
            QqSourceStatsSql.recount.forEach(sql::execSQL)
        }
    }

    /**
     * 读取来源后的增量更新（见 QqSourceStatsSql.applyNew）：[baseline] 之后新增的来源与关联计入统计，
     * [renamed] 里改了名的会话（0）/ 发送人（1）重算名称。缓存已标脏（等整体重建）时什么也不做，返回 false。
     */
    suspend fun applyNew(baseline: QqReadBaseline, renamed: Set<Pair<Int, String>>): Boolean = db.withTransaction {
        if (db.qqSourceDao().cacheDirty()) return@withTransaction false
        QqSourceStatsSql.applyNew(db.openHelper.writableDatabase, baseline.origins, baseline.links, renamed)
        true
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.long(sql: String): Long = query(sql).use { c -> c.moveToFirst(); c.getLong(0) }

    private companion object {
        /** 补齐分类时每段的键数 */
        const val FACET_KEYS = 500

        /** 已有分类重算名称时每段的分类数 */
        const val FACET_IDS = 500L

        /** 每段覆盖的图片 id 跨度：大图库上约几万条关联，单段事务控制在一两秒内 */
        const val IMAGE_ID_RANGE = 20_000L

        /** 进度里群名片一步占的比例（其余是投影关联） */
        const val MEMBERS_SHARE = 0.2f
    }
}
