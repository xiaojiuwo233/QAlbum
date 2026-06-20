package com.xjw.qalbum.service.analysis

import com.xjw.qalbum.data.db.dao.DuplicateAssignment
import com.xjw.qalbum.data.db.dao.ImageDao
import timber.log.Timber

/**
 * 重复检测器。
 *
 * 全量重算：一次窄投影加载（[ImageDao.dedupProjection]）→ 内存聚类（[DuplicateClusterer]，
 * 精确 xxHash + 受「宽高比 / 距离 / 组大小」约束的视觉 pHash）→ 一个事务批量写回
 * （[ImageDao.assignDuplicates]）。
 *
 * 取代了旧的「逐个 getById + 逐个 assign」实现（20w 图会发起成千上万次单查/单写）。
 * 调用方应先 [ImageDao.clearDuplicateAssignments] 清空旧分配。
 */
class DuplicateDetector(private val imageDao: ImageDao) {

    suspend fun recluster() {
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
                width = it.width,
                height = it.height,
                size = it.size,
            )
        }
        val startGroupId = imageDao.maxDuplicateGroupId() + 1
        val assignments = DuplicateClusterer.cluster(items, startGroupId)
        imageDao.assignDuplicates(
            assignments.map { DuplicateAssignment(it.id, it.groupId, it.isBest) }
        )
        val groups = assignments.map { it.groupId }.distinct().size
        Timber.i("DuplicateDetector.recluster: ${assignments.size} rows into $groups groups (from ${rows.size} candidates)")
    }
}
