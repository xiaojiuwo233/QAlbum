package com.xjw.qalbum.service.cleanup

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.xjw.qalbum.data.db.dao.CleanupDao
import com.xjw.qalbum.data.db.entity.QuarantineEntity
import kotlinx.coroutines.flow.Flow

/**
 * 回收站的统一外观接口（给 ViewModel 用）：列出、恢复、永久删除。
 */
class CleanupController(
    private val quarantineManager: QuarantineManager,
    private val cleanupDao: CleanupDao,
) {

    /** 回收站列表分页（UI 用）；不再全量装载，避免 quarantine 项过多撞 CursorWindow。 */
    fun pagingQuarantine(): Flow<PagingData<QuarantineEntity>> =
        Pager(
            config = PagingConfig(
                pageSize = 60,
                prefetchDistance = 120,
                initialLoadSize = 60,
                enablePlaceholders = false,
            ),
            pagingSourceFactory = { cleanupDao.pagingQuarantine() },
        ).flow

    /** 回收站总数 / 总大小（聚合查询，轻量）；UI 标题与「一键清理」总量用。 */
    fun observeQuarantineStats(): Flow<CleanupDao.QuarantineStatsRow> = cleanupDao.observeQuarantineStats()

    suspend fun countQuarantine(): Int = cleanupDao.countQuarantine()

    /** keyset 分页遍历回收站记录（一键清理逐页 purge 用）。 */
    suspend fun listQuarantinePaged(afterId: Long, limit: Int): List<QuarantineEntity> =
        cleanupDao.listQuarantinePaged(afterId, limit)

    suspend fun restore(quarantineId: Long): Boolean = quarantineManager.restore(quarantineId)

    suspend fun purge(quarantineId: Long): Boolean = quarantineManager.purge(quarantineId)

    /** 批量永久删除回收站记录（一键清理逐页批删）。 */
    suspend fun purgeByRecords(records: List<QuarantineEntity>): Int =
        quarantineManager.purgeByRecords(records)
}
