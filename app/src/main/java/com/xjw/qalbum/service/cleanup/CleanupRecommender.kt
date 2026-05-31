package com.xjw.qalbum.service.cleanup

import com.xjw.qalbum.data.db.dao.CleanupDao
import com.xjw.qalbum.data.db.dao.ImageDao
import com.xjw.qalbum.data.db.entity.CleanupBatchEntity
import com.xjw.qalbum.data.db.entity.CleanupCandidateEntity
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.service.analysis.CleanupSignals
import timber.log.Timber

/**
 * 清理策略
 *
 * - CONSERVATIVE：仅删除高置信度候选（delete_score ≥ 0.7）
 * - BALANCED：中等置信度（≥ 0.5）
 * - AGGRESSIVE：激进（≥ 0.3）
 * - Custom：用户自定义阈值
 */
sealed class CleanupStrategy(val displayName: String, val threshold: Float) {
    data object Conservative : CleanupStrategy("保守", 0.7f)
    data object Balanced : CleanupStrategy("平衡", 0.5f)
    data object Aggressive : CleanupStrategy("激进", 0.3f)
    data class Custom(val custom: Float) : CleanupStrategy("自定义", custom)

    val name: String get() = when (this) {
        Conservative -> "conservative"
        Balanced -> "balanced"
        Aggressive -> "aggressive"
        is Custom -> "custom"
    }
}

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
 * 清理推荐器
 *
 * 输入：当前数据库中的图片 + 用户选择的策略
 * 输出：[CleanupBatch]，包含建议删除的候选 + 分类风险报告
 *
 * 注意：本类不实际删除文件，只生成候选，
 * 真正的删除由用户在 UI 审核后通过 [QuarantineManager] 执行。
 */
class CleanupRecommender(
    private val imageDao: ImageDao,
    private val cleanupDao: CleanupDao,
) {

    suspend fun generateBatch(strategy: CleanupStrategy, maxCount: Int = 500): CleanupBatch {
        val threshold = strategy.threshold
        val candidates = imageDao.getByDeleteScoreAbove(threshold, maxCount)

        val byReason = candidates.groupBy { classifyReason(it) }
        val report = RiskReport(
            totalCount = candidates.size,
            totalSize = candidates.sumOf { it.size },
            riskyCount = candidates.count { isRisky(it) },
            safeCount = candidates.count { !isRisky(it) },
            byReason = byReason.mapValues { (_, list) ->
                ReasonGroup(list.size, list.sumOf { it.size })
            },
        )

        val batchId = cleanupDao.createBatch(
            CleanupBatchEntity(
                strategy = strategy.name,
                totalCount = candidates.size,
                totalSize = report.totalSize,
                status = "pending",
                createdAt = System.currentTimeMillis(),
            )
        )

        val entities = candidates.map { img ->
            CleanupCandidateEntity(
                batchId = batchId,
                imageId = img.id,
                reason = classifyReason(img),
                riskLevel = if (isRisky(img)) "high" else "safe",
                score = img.deleteScore,
            )
        }
        cleanupDao.insertCandidates(entities)
        Timber.i("CleanupRecommender: batch $batchId with ${candidates.size} candidates (${strategy.displayName})")

        return CleanupBatch(batchId, report, entities)
    }

    private fun classifyReason(img: ImageEntity): String = when {
        img.isCorrupt -> "损坏"
        img.isBlank -> "空白/纯色"
        img.duplicateGroupId != null && !img.isDuplicateBest -> "重复"
        CleanupSignals.isVeryLowResolution(img) -> "极低分辨率"
        CleanupSignals.isLowResolution(img) -> "低分辨率"
        (img.blurScore ?: 1000f) < 100f -> "模糊"
        img.size <= 4096 -> "极小文件"
        else -> "其他"
    }

    private fun isRisky(img: ImageEntity): Boolean {
        // OCR / AI 标签接入后会扩展该判定。Phase 2 简化：把"重复非最优"视为低风险，其它都视作中高风险
        return !(img.duplicateGroupId != null && !img.isDuplicateBest ||
            img.isBlank ||
            img.isCorrupt)
    }
}
