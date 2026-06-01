package com.xjw.qalbum.service.cleanup

import com.xjw.qalbum.data.db.dao.CleanupDao
import com.xjw.qalbum.data.db.entity.CleanupBatchEntity
import com.xjw.qalbum.data.db.entity.CleanupCandidateEntity
import com.xjw.qalbum.data.db.entity.QuarantineEntity
import com.xjw.qalbum.domain.model.CleanupConfig
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

    suspend fun generateBatch(config: CleanupConfig, label: String): CleanupBatch =
        recommender.generateBatch(config, label)

    suspend fun applyBatch(batchId: Long): Int {
        val candidates = cleanupDao.getCandidates(batchId)
        var moved = 0
        for (candidate in candidates) {
            // 用户决策为"删除"或未决（默认按建议执行）才隔离
            if (candidate.userDecision == "keep") continue
            val ok = quarantineManager.quarantine(candidate.imageId, candidate.reason)
            if (ok) moved++
        }
        return moved
    }

    suspend fun listCandidates(batchId: Long): List<CleanupCandidateEntity> =
        cleanupDao.getCandidates(batchId)

    suspend fun moveToTrash(items: List<Pair<Long, String>>): Int {
        var moved = 0
        for ((imageId, reason) in items) {
            if (quarantineManager.quarantine(imageId, reason)) moved++
        }
        return moved
    }

    fun observeRecentBatches(): Flow<List<CleanupBatchEntity>> = cleanupDao.observeRecentBatches()

    fun observeQuarantine(): Flow<List<QuarantineEntity>> = cleanupDao.observeQuarantine()

    suspend fun restore(quarantineId: Long): Boolean = quarantineManager.restore(quarantineId)

    suspend fun purge(quarantineId: Long): Boolean = quarantineManager.purge(quarantineId)
}
