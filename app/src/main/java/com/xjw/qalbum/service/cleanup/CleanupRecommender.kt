package com.xjw.qalbum.service.cleanup

import com.xjw.qalbum.data.db.dao.CleanupDao
import com.xjw.qalbum.data.db.dao.ImageDao
import com.xjw.qalbum.data.db.entity.CleanupBatchEntity
import com.xjw.qalbum.data.db.entity.CleanupCandidateEntity
import com.xjw.qalbum.domain.model.CleanupConfig
import timber.log.Timber

/** 风险报告：本批次按原因分类的概览（计数单位为「审核项」，重复组算 1 项） */
data class RiskReport(
    val totalCount: Int,
    val totalSize: Long,
    val byReason: Map<String, ReasonGroup>,
)

data class ReasonGroup(val count: Int, val size: Long)

/** 单次清理批次的 UI 模型：审核项列表 + 概览 */
data class CleanupBatch(
    val id: Long,
    val report: RiskReport,
    val items: List<ReviewBatchItem>,
)

/**
 * 清理推荐器。
 *
 * 1. [ImageDao.cleanupCandidatePool] 在 SQL 层按启用维度粗筛 + `LIMIT poolLimit`，避免把全部图加载进内存；
 * 2. [CleanupRules.classify] 在内存按优先级精判每张图命中的维度；
 * 3. [ReviewAggregator] 聚合成「审核项」（重复组折叠为 1 项），取前 [maxItems] 项——
 *    这使审核数量严格以「项」为单位（200 项 = 屏幕上 200 个待处理项，而非几千张）。
 *
 * 真正的保留/勾选在审核时由 [DuplicateReviewPolicy] 对每个重复组重算，本类不删文件。
 */
class CleanupRecommender(
    private val imageDao: ImageDao,
    private val cleanupDao: CleanupDao,
) {

    suspend fun generateBatch(
        config: CleanupConfig,
        label: String,
        maxItems: Int = DEFAULT_MAX_ITEMS,
        oldestFirst: Boolean = true,
        allDuplicates: Boolean = false,
    ): CleanupBatch {
        if (allDuplicates && config.duplicateEnabled) {
            return generateAllDuplicatesBatch(config, label, maxItems, oldestFirst)
        }
        val poolLimit = (maxItems.toLong() * POOL_FACTOR)
            .coerceIn(maxItems.toLong(), POOL_HARD_CAP)
            .toInt()

        val pool = imageDao.cleanupCandidatePool(
            corruptEnabled = config.corruptEnabled,
            blankEnabled = config.blankEnabled,
            duplicateEnabled = config.duplicateEnabled,
            lowResEnabled = config.lowResolutionEnabled,
            lowResMaxPixels = config.lowResolutionMaxPixels,
            blurEnabled = config.blurEnabled,
            blurMaxScore = config.blurMaxScore,
            tinyEnabled = config.tinyFileEnabled,
            tinyMaxBytes = config.tinyFileMaxBytes,
            emojiEnabled = config.emojiEnabled,
            emojiSquareRatio = config.emojiSquareRatio,
            emojiMaxSide = config.emojiMaxSide,
            emojiMinSide = config.emojiMinSide,
            oldestFirst = oldestFirst,
            poolLimit = poolLimit,
        )

        val classified = pool.mapNotNull { row ->
            CleanupRules.classify(row, config)?.let { reason -> row to reason }
        }
        val sizeById = pool.associate { it.id to it.size }
        val rowsByGroup = classified
            .filter { it.first.duplicateGroupId != null }
            .groupBy { it.first.duplicateGroupId!! }

        val items = ReviewAggregator.aggregate(
            classified.map { (row, reason) ->
                ReviewAggregator.Candidate(row.id, row.duplicateGroupId, reason)
            },
            maxItems,
        )

        // 概览：按原因统计审核项数与估算大小（重复组按组内候选行求和）
        val reasonCount = LinkedHashMap<String, Int>()
        val reasonSize = HashMap<String, Long>()
        var totalSize = 0L
        fun tally(reason: String, size: Long) {
            reasonCount[reason] = (reasonCount[reason] ?: 0) + 1
            reasonSize[reason] = (reasonSize[reason] ?: 0L) + size
            totalSize += size
        }
        items.forEach { item ->
            when (item) {
                is ReviewBatchItem.Single -> tally(item.reason, sizeById[item.imageId] ?: 0L)
                is ReviewBatchItem.Group -> tally(
                    CleanupRules.REASON_DUPLICATE,
                    rowsByGroup[item.groupId].orEmpty().sumOf { sizeById[it.first.id] ?: 0L },
                )
            }
        }
        val report = RiskReport(
            totalCount = items.size,
            totalSize = totalSize,
            byReason = reasonCount.mapValues { (r, c) -> ReasonGroup(c, reasonSize[r] ?: 0L) },
        )

        val batchId = cleanupDao.createBatch(
            CleanupBatchEntity(
                strategy = label,
                totalCount = items.size,
                totalSize = totalSize,
                status = "pending",
                createdAt = System.currentTimeMillis(),
            )
        )

        // 落库候选（历史/回放）：单图项 1 条；重复组项展开组内候选行
        val candidateEntities = buildList {
            items.forEach { item ->
                when (item) {
                    is ReviewBatchItem.Single -> add(
                        CleanupCandidateEntity(
                            batchId = batchId,
                            imageId = item.imageId,
                            reason = item.reason,
                            riskLevel = if (isRisky(item.reason)) "high" else "safe",
                            score = 0f,
                        )
                    )
                    is ReviewBatchItem.Group ->
                        rowsByGroup[item.groupId].orEmpty().forEach { (row, _) ->
                            add(
                                CleanupCandidateEntity(
                                    batchId = batchId,
                                    imageId = row.id,
                                    reason = CleanupRules.REASON_DUPLICATE,
                                    riskLevel = "safe",
                                    score = row.deleteScore,
                                )
                            )
                        }
                }
            }
        }
        cleanupDao.insertCandidates(candidateEntities)
        Timber.i("CleanupRecommender: batch $batchId, ${items.size} items / ${candidateEntities.size} candidates ($label)")

        return CleanupBatch(batchId, report, items)
    }

    /**
     * 「审核全部重复」：所有重复组一次性纳入（不受 [maxItems] 限制），避免同种重复图分多批、清完又冒出来；
     * 其它维度（低分/模糊/表情包等）仍按 [maxItems] 限量。
     */
    private suspend fun generateAllDuplicatesBatch(
        config: CleanupConfig,
        label: String,
        maxItems: Int,
        oldestFirst: Boolean,
    ): CleanupBatch {
        // 1) 所有重复组（全量，保持组首次出现顺序）
        val dupRows = imageDao.allDuplicateRows(oldestFirst)
        val seenGroups = LinkedHashSet<Long>()
        val dupGroupSize = HashMap<Long, Long>()
        dupRows.forEach {
            seenGroups.add(it.duplicateGroupId)
            dupGroupSize[it.duplicateGroupId] = (dupGroupSize[it.duplicateGroupId] ?: 0L) + it.size
        }
        val dupItems = seenGroups.map { ReviewBatchItem.Group(it) }

        // 2) 其它维度（排除重复组成员），限量 maxItems
        val poolLimit = (maxItems.toLong() * POOL_FACTOR).coerceIn(maxItems.toLong(), POOL_HARD_CAP).toInt()
        val pool = imageDao.cleanupCandidatePool(
            corruptEnabled = config.corruptEnabled,
            blankEnabled = config.blankEnabled,
            duplicateEnabled = false, // 重复已在上面全量处理，这里只取其它维度
            lowResEnabled = config.lowResolutionEnabled,
            lowResMaxPixels = config.lowResolutionMaxPixels,
            blurEnabled = config.blurEnabled,
            blurMaxScore = config.blurMaxScore,
            tinyEnabled = config.tinyFileEnabled,
            tinyMaxBytes = config.tinyFileMaxBytes,
            emojiEnabled = config.emojiEnabled,
            emojiSquareRatio = config.emojiSquareRatio,
            emojiMaxSide = config.emojiMaxSide,
            emojiMinSide = config.emojiMinSide,
            oldestFirst = oldestFirst,
            poolLimit = poolLimit,
        )
        val otherClassified = pool
            .filter { it.duplicateGroupId == null } // 属重复组的图归重复处理，避免一图两处
            .mapNotNull { row -> CleanupRules.classify(row, config)?.let { reason -> row to reason } }
        val otherSizeById = pool.associate { it.id to it.size }
        val otherItems = ReviewAggregator.aggregate(
            otherClassified.map { (row, reason) -> ReviewAggregator.Candidate(row.id, null, reason) },
            maxItems,
        )

        val items = dupItems + otherItems

        val reasonCount = LinkedHashMap<String, Int>()
        val reasonSize = HashMap<String, Long>()
        var totalSize = 0L
        fun tally(reason: String, size: Long) {
            reasonCount[reason] = (reasonCount[reason] ?: 0) + 1
            reasonSize[reason] = (reasonSize[reason] ?: 0L) + size
            totalSize += size
        }
        dupItems.forEach { tally(CleanupRules.REASON_DUPLICATE, dupGroupSize[it.groupId] ?: 0L) }
        otherItems.forEach { item ->
            if (item is ReviewBatchItem.Single) tally(item.reason, otherSizeById[item.imageId] ?: 0L)
        }
        val report = RiskReport(
            totalCount = items.size,
            totalSize = totalSize,
            byReason = reasonCount.mapValues { (r, c) -> ReasonGroup(c, reasonSize[r] ?: 0L) },
        )

        val batchId = cleanupDao.createBatch(
            CleanupBatchEntity(
                strategy = label,
                totalCount = items.size,
                totalSize = totalSize,
                status = "pending",
                createdAt = System.currentTimeMillis(),
            )
        )
        // 落库候选（历史）：每个重复组留一个代表（避免几万条），其它维度单图各一条
        val candidateEntities = buildList {
            val groupSeed = HashMap<Long, Long>()
            dupRows.forEach { row -> groupSeed.getOrPut(row.duplicateGroupId) { row.id } }
            groupSeed.values.forEach { id ->
                add(
                    CleanupCandidateEntity(
                        batchId = batchId,
                        imageId = id,
                        reason = CleanupRules.REASON_DUPLICATE,
                        riskLevel = "safe",
                        score = 0f,
                    )
                )
            }
            otherItems.forEach { item ->
                if (item is ReviewBatchItem.Single) {
                    add(
                        CleanupCandidateEntity(
                            batchId = batchId,
                            imageId = item.imageId,
                            reason = item.reason,
                            riskLevel = if (isRisky(item.reason)) "high" else "safe",
                            score = 0f,
                        )
                    )
                }
            }
        }
        cleanupDao.insertCandidates(candidateEntities)
        Timber.i("CleanupRecommender: allDuplicates batch $batchId, ${dupItems.size} dup groups + ${otherItems.size} others ($label)")
        return CleanupBatch(batchId, report, items)
    }

    /** 重复 / 空白 / 损坏视为低风险（safe），其余视为中高风险 */
    private fun isRisky(reason: String): Boolean = when (reason) {
        CleanupRules.REASON_DUPLICATE, CleanupRules.REASON_BLANK, CleanupRules.REASON_CORRUPT -> false
        else -> true
    }

    companion object {
        const val DEFAULT_MAX_ITEMS = 200
        private const val POOL_FACTOR = 8L
        private const val POOL_HARD_CAP = 50_000L
    }
}
