package com.xjw.qalbum.service.cleanup

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.xjw.qalbum.data.db.dao.CleanupDao
import com.xjw.qalbum.data.db.entity.CleanupBatchEntity
import com.xjw.qalbum.data.db.entity.CleanupCandidateEntity
import com.xjw.qalbum.data.db.entity.QuarantineEntity
import com.xjw.qalbum.domain.model.CleanupConfig
import com.xjw.qalbum.service.analysis.DeletePreferenceModel
import kotlinx.coroutines.flow.Flow

/**
 * 清理流程的统一外观接口（给 ViewModel 用）
 *
 * - 生成清理批次
 * - 列出回收站
 * - 执行 / 取消批次
 */
class CleanupController(
    private val recommender: CleanupRecommender,
    private val quarantineManager: QuarantineManager,
    private val cleanupDao: CleanupDao,
) {

    suspend fun generateBatch(
        config: CleanupConfig,
        label: String,
        maxItems: Int = 200,
        oldestFirst: Boolean = true,
        allDuplicates: Boolean = false,
        recommendModel: DeletePreferenceModel? = null,
        recommendLimit: Int = 0,
    ): CleanupBatch =
        recommender.generateBatch(config, label, maxItems, oldestFirst, allDuplicates, recommendModel, recommendLimit)

    suspend fun applyBatch(batchId: Long): Int {
        // 用户决策为"删除"或未决（默认按建议执行）才隔离；批量并发移入
        val items = cleanupDao.getCandidates(batchId)
            .filter { it.userDecision != "keep" }
            .map { it.imageId to it.reason }
        return quarantineManager.quarantineBatch(items)
    }

    suspend fun listCandidates(batchId: Long): List<CleanupCandidateEntity> =
        cleanupDao.getCandidates(batchId)

    /**
     * 批量移入回收站：并发移动文件 + 批量写库（见 [QuarantineManager.quarantineBatch]）。
     * [onProgress]（done, total）随处理进度回调，供 UI 展示进度条。
     */
    suspend fun moveToTrash(
        items: List<Pair<Long, String>>,
        options: PictureDeleteOptions = PictureDeleteOptions(),
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Int = quarantineManager.quarantinePictures(items.map { it.first },options,items.firstOrNull()?.second.orEmpty(),onProgress=onProgress).selectedProcessed

    fun observeRecentBatches(): Flow<List<CleanupBatchEntity>> = cleanupDao.observeRecentBatches()

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
