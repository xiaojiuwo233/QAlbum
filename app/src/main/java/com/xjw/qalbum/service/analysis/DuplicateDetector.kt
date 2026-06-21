package com.xjw.qalbum.service.analysis

import com.xjw.qalbum.data.db.dao.DedupExclusionDao
import com.xjw.qalbum.data.db.dao.DuplicateAssignment
import com.xjw.qalbum.data.db.dao.ImageDao
import com.xjw.qalbum.domain.model.DuplicateStrictness
import timber.log.Timber

/**
 * 重复检测器。
 *
 * 全量重算：一次窄投影加载（[ImageDao.dedupProjection]）→ 内存聚类（[DuplicateClusterer]：精确 xxHash +
 * 受「分块指纹精筛 / 宽高比 / pHash 距离 / 组大小」约束、并尊重人工拆分排斥的视觉合议）→
 * 一个事务批量写回（[ImageDao.assignDuplicates]）。
 *
 * 调用方应先 [ImageDao.clearDuplicateAssignments] 清空旧分配。
 */
class DuplicateDetector(
    private val imageDao: ImageDao,
    private val dedupExclusionDao: DedupExclusionDao,
) {

    suspend fun recluster(
        maxVisualGroupSize: Int = DuplicateClusterer.DEFAULT_MAX_VISUAL_GROUP,
        strictness: DuplicateStrictness = DuplicateStrictness.DEFAULT,
    ) {
        val rows = imageDao.dedupProjection()
        if (rows.isEmpty()) {
            Timber.i("DuplicateDetector.recluster: nothing to cluster")
            return
        }
        val items = rows.map {
            DuplicateClusterer.Item(
                id = it.id,
                xxhash = it.xxhash,
                phash = it.phash,
                blockHash = it.blockHash,
                isBlank = it.isBlank,
                width = it.width,
                height = it.height,
                size = it.size,
            )
        }
        val exclusions = buildExclusionMap()
        val startGroupId = imageDao.maxDuplicateGroupId() + 1
        val assignments = DuplicateClusterer.cluster(
            items,
            startGroupId,
            visualThreshold = strictness.visualThreshold,
            blockThreshold = strictness.blockThreshold,
            maxVisualGroupSize = maxVisualGroupSize,
            exclusions = exclusions,
        )
        imageDao.assignDuplicates(
            assignments.map { DuplicateAssignment(it.id, it.groupId, it.isBest) }
        )
        val groups = assignments.map { it.groupId }.distinct().size
        Timber.i(
            "DuplicateDetector.recluster: ${assignments.size} rows into $groups groups " +
                "(from ${rows.size} candidates, strictness=$strictness, exclusions=${exclusions.size})",
        )
    }

    /** 构建 imageId → 所属 cohort 集合（人工拆分的「禁止再聚」约束）。表很小，全量读入。 */
    private suspend fun buildExclusionMap(): Map<Long, Set<Long>> {
        val rows = dedupExclusionDao.all()
        if (rows.isEmpty()) return emptyMap()
        val map = HashMap<Long, MutableSet<Long>>()
        for (r in rows) map.getOrPut(r.imageId) { mutableSetOf() }.add(r.cohortId)
        return map
    }
}
