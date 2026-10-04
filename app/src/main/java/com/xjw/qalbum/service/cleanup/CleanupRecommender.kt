package com.xjw.qalbum.service.cleanup

import com.xjw.qalbum.data.db.dao.CleanupDao
import com.xjw.qalbum.data.db.dao.CleanupSignalRow
import com.xjw.qalbum.data.db.dao.ImageDao
import com.xjw.qalbum.data.db.entity.CleanupBatchEntity
import com.xjw.qalbum.data.db.entity.CleanupCandidateEntity
import com.xjw.qalbum.domain.model.CleanupConfig
import com.xjw.qalbum.service.analysis.DeleteFeatures
import com.xjw.qalbum.service.analysis.DeletePreferenceModel
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
        recommendModel: DeletePreferenceModel? = null,
        recommendLimit: Int = 0,
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

        val baseItems = ReviewAggregator.aggregate(
            classified.map { (row, reason) ->
                // 关闭「重复」维度时不携带组 id：命中其他维度（模糊/低分辨率等）的组员按
                // 单图原因入项，不折叠成重复组（否则整组连未命中成员一起被展示并默认勾选）
                ReviewAggregator.Candidate(
                    row.id,
                    if (config.duplicateEnabled) row.duplicateGroupId else null,
                    reason,
                )
            },
            maxItems,
        )
        // 「推荐删除」通道：额外配额（不占 maxItems），用删除偏好模型给未纳入的图打分排序
        val recSizeById = HashMap<Long, Long>()
        val recItems = buildRecommendations(baseItems, rowsByGroup, recommendModel, recommendLimit, recSizeById)
        val items = baseItems + recItems

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
                is ReviewBatchItem.Single -> tally(item.reason, sizeById[item.imageId] ?: recSizeById[item.imageId] ?: 0L)
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
                            riskLevel = "high",
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
                                    riskLevel = "high",
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
     * 「推荐删除」候选：取按 delete_score 粗筛的候选池，排除已被其他维度纳入的图，
     * 用删除偏好模型打分，概率严格大于阈值的按概率降序取前 [limit]。[outSize] 回填候选 size 供概览统计。
     */
    private suspend fun buildRecommendations(
        baseItems: List<ReviewBatchItem>,
        rowsByGroup: Map<Long, List<Pair<CleanupSignalRow, String>>>,
        model: DeletePreferenceModel?,
        limit: Int,
        outSize: HashMap<Long, Long>,
    ): List<ReviewBatchItem> {
        if (model == null || limit <= 0) return emptyList()
        val usedIds = buildSet {
            baseItems.forEach { item ->
                when (item) {
                    is ReviewBatchItem.Single -> add(item.imageId)
                    is ReviewBatchItem.Group -> rowsByGroup[item.groupId].orEmpty().forEach { add(it.first.id) }
                }
            }
        }
        val now = System.currentTimeMillis()
        return imageDao.recommendCandidatePool(limit * REC_POOL_FACTOR).asSequence()
            // 重复组成员（含组内保留图 is_duplicate_best）一律排除：组的去留由重复审核项
            // 整组决策，推荐通道再把保留图默认勾选会导致「按推荐勾选」后整组全删
            .filter { !it.inDuplicateGroup && it.id !in usedIds }
            .map { row ->
                outSize[row.id] = row.size
                row.id to model.probability(
                    DeleteFeatures.extract(
                        row.featureGrid, row.blurScore, row.width, row.height, row.size,
                        row.sourceType, row.isAnimated, row.mtime, row.inDuplicateGroup, now,
                    ),
                )
            }
            // 严格大于：全零冷模型对任何特征 σ(0)=0.5 恰好压线，若用 >= 会让候选池全体并列
            // 命中、按 delete_score 硬凑出「推荐」；真实学习过的模型输出不会恰为 0.5，不受影响
            .filter { it.second > REC_THRESHOLD }
            .sortedByDescending { it.second }
            .take(limit)
            .map { ReviewBatchItem.Single(it.first, CleanupRules.REASON_RECOMMENDED) }
            .toList()
    }

    /**
     * 「审核全部重复」：重复组不受 [maxItems] 限制、但按 [MAX_DUP_GROUPS] 分波——本波组员
     * 处理后离开 active，下次生成自然轮到后续组。一次性全量纳入会让审核页把 10w+ 组员的
     * 完整实体物化进内存（数百 MB 直接 OOM）。其它维度（低分/模糊/表情包等）仍按 [maxItems] 限量。
     */
    private suspend fun generateAllDuplicatesBatch(
        config: CleanupConfig,
        label: String,
        maxItems: Int,
        oldestFirst: Boolean,
    ): CleanupBatch {
        // 1) 重复组（保持组首次出现顺序，最多 MAX_DUP_GROUPS 组/波；组一旦入选则统计其全部成员）
        val dupRows = imageDao.allDuplicateRows(oldestFirst)
        val seenGroups = LinkedHashSet<Long>()
        val dupGroupSize = HashMap<Long, Long>()
        for (row in dupRows) {
            if (row.duplicateGroupId in seenGroups) {
                dupGroupSize[row.duplicateGroupId] = (dupGroupSize[row.duplicateGroupId] ?: 0L) + row.size
            } else if (seenGroups.size < MAX_DUP_GROUPS) {
                seenGroups.add(row.duplicateGroupId)
                dupGroupSize[row.duplicateGroupId] = row.size
            }
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
        // 落库候选（历史）：每个入选的重复组留一个代表（避免几万条），其它维度单图各一条
        val candidateEntities = buildList {
            val groupSeed = HashMap<Long, Long>()
            dupRows.forEach { row ->
                if (row.duplicateGroupId in seenGroups) groupSeed.getOrPut(row.duplicateGroupId) { row.id }
            }
            groupSeed.values.forEach { id ->
                add(
                    CleanupCandidateEntity(
                        batchId = batchId,
                        imageId = id,
                        reason = CleanupRules.REASON_DUPLICATE,
                        riskLevel = "high",
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
                            riskLevel = "high",
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

    // 尚未接入完整 QQ 消息引用检查，不再把重复 / 空白 / 损坏直接标为安全删除。

    companion object {
        const val DEFAULT_MAX_ITEMS = 200
        private const val POOL_FACTOR = 8L
        private const val POOL_HARD_CAP = 50_000L

        /**
         * 「审核全部重复」单波组数上限：审核页会按组装载全部成员实体（约 0.5~1KB/张），
         * 2000 组 × 平均几张成员是几十 MB 量级的可控上限；超出的组留给下一波。
         */
        private const val MAX_DUP_GROUPS = 2000

        /** 推荐删除：概率 ≥ 此值才纳入；候选池粗筛倍数（取 limit×此值 再模型精排）。 */
        private const val REC_THRESHOLD = 0.5
        private const val REC_POOL_FACTOR = 4
    }
}
