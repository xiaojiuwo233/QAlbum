package com.xjw.qalbum.service.cleanup

import com.xjw.qalbum.data.db.dao.CleanupDao
import com.xjw.qalbum.data.db.dao.ImageDao
import com.xjw.qalbum.data.db.entity.CleanupBatchEntity
import com.xjw.qalbum.data.db.entity.CleanupCandidateEntity
import com.xjw.qalbum.domain.model.CleanupConfig
import timber.log.Timber

/** 风险报告：本批次按原因分类的概览 */
data class RiskReport(
    val totalCount: Int,
    val totalSize: Long,
    val riskyCount: Int,
    val safeCount: Int,
    val byReason: Map<String, ReasonGroup>,
)

data class ReasonGroup(val count: Int, val size: Long)

/** 单次清理批次的 UI 模型 */
data class CleanupBatch(
    val id: Long,
    val report: RiskReport,
    val candidates: List<CleanupCandidateEntity>,
)

/**
 * 清理推荐器（方案 A）
 *
 * 输入：当前数据库中的图片 + 用户的 [CleanupConfig]
 * 输出：[CleanupBatch]，包含命中启用维度的候选 + 分类风险报告
 *
 * 准入完全由 [CleanupRules] 按配置判定（命中任一启用维度即入候选），
 * delete_score 仅用于候选排序。收藏 / 用户保留的图已在 DAO 查询层排除。
 *
 * 本类不实际删除文件，只生成候选，
 * 真正的删除由用户在 UI 审核后通过 [QuarantineManager] 执行。
 */
class CleanupRecommender(
    private val imageDao: ImageDao,
    private val cleanupDao: CleanupDao,
) {

    suspend fun generateBatch(
        config: CleanupConfig,
        label: String,
        maxCount: Int = 500,
        oldestFirst: Boolean = true,
    ): CleanupBatch {
        // 候选池按分析顺序（mtime 升/降序）排序；命中启用维度的取前 maxCount 个，
        // 让本次审核遵守与分析一致的早到晚/晚到早顺序
        val pool = imageDao.cleanupCandidatePool(oldestFirst)
        val hits = pool
            .mapNotNull { row -> CleanupRules.classify(row, config)?.let { reason -> row to reason } }
            .take(maxCount)

        val byReason = hits.groupBy { it.second }
        val report = RiskReport(
            totalCount = hits.size,
            totalSize = hits.sumOf { it.first.size },
            riskyCount = hits.count { isRisky(it.second) },
            safeCount = hits.count { !isRisky(it.second) },
            byReason = byReason.mapValues { (_, list) ->
                ReasonGroup(list.size, list.sumOf { it.first.size })
            },
        )

        val batchId = cleanupDao.createBatch(
            CleanupBatchEntity(
                strategy = label,
                totalCount = hits.size,
                totalSize = report.totalSize,
                status = "pending",
                createdAt = System.currentTimeMillis(),
            )
        )

        val entities = hits.map { (row, reason) ->
            CleanupCandidateEntity(
                batchId = batchId,
                imageId = row.id,
                reason = reason,
                riskLevel = if (isRisky(reason)) "high" else "safe",
                score = row.deleteScore,
            )
        }
        cleanupDao.insertCandidates(entities)
        Timber.i("CleanupRecommender: batch $batchId with ${hits.size} candidates ($label)")

        return CleanupBatch(batchId, report, entities)
    }

    /** 重复 / 空白 / 损坏视为低风险（safe），其余视为中高风险 */
    private fun isRisky(reason: String): Boolean = when (reason) {
        CleanupRules.REASON_DUPLICATE, CleanupRules.REASON_BLANK, CleanupRules.REASON_CORRUPT -> false
        else -> true
    }
}
