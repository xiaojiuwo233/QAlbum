package com.xjw.qalbum.service.cleanup

/**
 * 审核项：审核网格里的一个「待处理项」。重复组折叠为 1 项（[Group]），其余各图一项（[Single]）。
 * 这是审核数量（`reviewLimit`）的计数单位——「200 张」= 200 个审核项（重复组算 1 项）。
 */
sealed interface ReviewBatchItem {
    data class Single(val imageId: Long, val reason: String) : ReviewBatchItem
    data class Group(val groupId: Long) : ReviewBatchItem
}

/**
 * 把分类后的候选聚合成审核项，并取前 [maxItems] 项（纯函数）。
 *
 * - 属于某重复组的候选（[Candidate.duplicateGroupId] 非空）折叠进同一个 [ReviewBatchItem.Group]，
 *   即使它因其他维度入选也归组，避免同一张图既成组内图又单独成项。
 * - 其余候选各成一个 [ReviewBatchItem.Single]（按 imageId 去重）。
 * - 维持输入顺序（输入应已按分析顺序 mtime 排好）。
 */
object ReviewAggregator {

    data class Candidate(val imageId: Long, val duplicateGroupId: Long?, val reason: String)

    fun aggregate(candidates: List<Candidate>, maxItems: Int): List<ReviewBatchItem> {
        val items = mutableListOf<ReviewBatchItem>()
        val seenGroups = mutableSetOf<Long>()
        val seenSingles = mutableSetOf<Long>()
        for (cand in candidates) {
            if (items.size >= maxItems) break
            val gid = cand.duplicateGroupId
            if (gid != null) {
                if (seenGroups.add(gid)) items += ReviewBatchItem.Group(gid)
            } else {
                if (seenSingles.add(cand.imageId)) items += ReviewBatchItem.Single(cand.imageId, cand.reason)
            }
        }
        return items
    }
}
