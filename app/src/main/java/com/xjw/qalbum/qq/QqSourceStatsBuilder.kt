package com.xjw.qalbum.qq

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.xjw.qalbum.data.db.AppDatabase
import com.xjw.qalbum.data.db.QqSourceStatsSql
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * 来源统计的整体重建（仅在导入新来源、名称变化、升级后执行；日常删图由触发器增量维护）。
 *
 * 分三段、每段一个短事务，期间其他写入（删图、扫描）可以插队：
 * 1. 一次遍历消息来源，补齐分类、刷新名称与群名片；
 * 2. 按图片 id 分段，把消息关联投影进 qq_image_contexts（已有行跳过，浏览不受影响）；
 * 3. 补上重建期间新入库的图片，按当前状态重算计数并清除脏标记。
 */
internal class QqSourceStatsBuilder(private val db: AppDatabase) {

    suspend fun rebuild(onProgress: (Float?) -> Unit) {
        val sql = db.openHelper.writableDatabase
        onProgress(null)
        db.withTransaction { QqSourceStatsSql.rebuildNames.forEach(sql::execSQL) }

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
            onProgress((done - minId + 1).toFloat() / span)
        }

        db.withTransaction {
            QqSourceStatsSql.contextsInRange(sql, maxId, Long.MAX_VALUE)
            QqSourceStatsSql.recount.forEach(sql::execSQL)
        }
    }

    private companion object {
        /** 每段覆盖的图片 id 跨度：大图库上约几万条关联，单段事务控制在一两秒内 */
        const val IMAGE_ID_RANGE = 20_000L
    }
}
