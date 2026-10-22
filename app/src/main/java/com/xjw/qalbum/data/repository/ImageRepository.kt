package com.xjw.qalbum.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import com.xjw.qalbum.data.db.dao.ImageDao
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.data.paging.RefreshGate
import com.xjw.qalbum.domain.model.GallerySort
import com.xjw.qalbum.domain.model.TimelineWindow
import com.xjw.qalbum.data.paging.BulkWrites
import com.xjw.qalbum.data.paging.calmDuringBulkWrites
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transform

/**
 * 图片仓库
 *
 * 对 UI 层（ViewModel / Screen）暴露干净的、与具体存储无关的查询接口。
 * 不直接操作文件系统 —— 扫描相关逻辑由 service.indexer 模块负责。
 */
class ImageRepository(
    private val imageDao: ImageDao,
) {

    // ---- 时间线 ----

    /**
     * 时间线 Paging 流：默认每页 50 条，预加载 4 页；按 [sort] 选择对应排序的 PagingSource。
     * [stacked] 为真时同一张 QQ 图片的多个版本只显示最大的那张（家族代表图），按大小排序时按整组合计。
     *
     * 开启占位：网格从一开始就知道总数，快速滚动条可以直接拖到任意位置；跳得远时（超过 [JUMP_THRESHOLD]）
     * 不再逐页加载过去，而是在目标位置重新加载。
     */
    fun timelinePager(sort: GallerySort, stacked: Boolean, gate: RefreshGate? = null): Flow<PagingData<ImageEntity>> {
        val factory = when (sort) {
            GallerySort.DATE_DESC -> { { imageDao.pagingActiveTimeline(stacked) } }
            GallerySort.DATE_ASC -> { { imageDao.pagingActiveTimelineByDateAsc(stacked) } }
            GallerySort.SIZE_DESC -> if (stacked) {
                { imageDao.pagingStackedTimelineBySize() }
            } else {
                { imageDao.pagingActiveTimelineBySize() }
            }
        }
        return Pager(
            config = PagingConfig(
                pageSize = 50,
                prefetchDistance = 200,
                // 跳到远处后一次查询就铺满一两屏，不用再接着补几页
                initialLoadSize = 150,
                enablePlaceholders = true,
                jumpThreshold = JUMP_THRESHOLD,
            ),
            pagingSourceFactory = gated(gate, factory),
        ).flow
    }

    /** 归档页：每张图一格；[statuses] 见 ArchiveManager.statusesOf */
    fun archiveTimelinePager(statuses: List<String>, gate: RefreshGate? = null): Flow<PagingData<ImageEntity>> {
        return Pager(
            config = PagingConfig(
                pageSize = 50,
                prefetchDistance = 100,
                initialLoadSize = 150,
                // 占位：快速滚动条可直接拖到任意位置（同 timelinePager）
                enablePlaceholders = true,
                jumpThreshold = JUMP_THRESHOLD,
            ),
            pagingSourceFactory = gated(gate) { imageDao.pagingArchiveTimeline(statuses) },
        ).flow
    }

    /** 给了闸门就包一层：批量操作期间网格保持原样，结束后统一刷新 */
    private fun gated(gate: RefreshGate?, factory: () -> PagingSource<Int, ImageEntity>): () -> PagingSource<Int, ImageEntity> =
        gate?.gated(factory) ?: factory

    fun observeTimelineDateBucketCounts(stacked: Boolean): Flow<Map<Long, Int>> =
        imageDao.observeTimelineDateBucketCounts(stacked).map { rows -> rows.toBucketCountMap() }.throttleLatest()

    fun observeTimelineSizeBucketCounts(stacked: Boolean): Flow<Map<Long, Int>> =
        (if (stacked) imageDao.observeStackedSizeBucketCounts() else imageDao.observeTimelineSizeBucketCounts())
            .map { rows -> rows.toBucketCountMap() }.throttleLatest()

    // ---- 点分组标题全选 ----

    suspend fun timelineIdsBetween(from: Long, until: Long, stacked: Boolean): List<Long> =
        imageDao.timelineIdsBetween(from, until, stacked)

    /** 大小区间 [low, high)：合并显示时按整组合计 */
    suspend fun timelineIdsBySize(low: Long, high: Long, stacked: Boolean): List<Long> =
        if (stacked) imageDao.stackedIdsBySize(low, high) else imageDao.timelineIdsBySize(low, high)

    suspend fun archiveIdsBetween(statuses: List<String>, from: Long, until: Long): List<Long> =
        imageDao.archiveIdsBetween(statuses, from, until)

    fun observeArchiveDateBucketCounts(statuses: List<String>): Flow<Map<Long, Int>> =
        imageDao.observeArchiveDateBucketCounts(statuses).map { rows -> rows.toBucketCountMap() }.throttleLatest()

    /** 归档页看图仍全量取 id：集合由用户手动归档产生，规模与主时间线不同量级 */
    suspend fun archiveTimelineIds(statuses: List<String>): List<Long> = imageDao.archiveTimelineIds(statuses)

    // ---- 详情页时间线窗口（keyset 定位，替代全量 id 装载）----

    /**
     * 以 [anchor] 为中心取一段连续 id 窗口（前后各至多 [radius] 条），并给出锚点 rank 与
     * 时间线总数（COUNT 走覆盖索引）。滑动耗尽窗口时用 [timelineIdsBefore]/[timelineIdsAfter]
     * 以窗口端点图为新锚点续页。[anchor] 即便已不在时间线（并发被删/迁移），窗口仍按其
     * 排序键定位，且 anchor.id 保留在窗口内——当前图必须可展示。
     */
    suspend fun timelineWindowAround(
        anchor: ImageEntity,
        sort: GallerySort,
        stacked: Boolean,
        radius: Int = TIMELINE_WINDOW_RADIUS,
    ): TimelineWindow {
        val rank = when (sort) {
            GallerySort.DATE_DESC -> imageDao.timelineRankDateDesc(anchor.mtime, anchor.id, stacked)
            GallerySort.DATE_ASC -> imageDao.timelineRankDateAsc(anchor.mtime, anchor.id, stacked)
            GallerySort.SIZE_DESC -> if (stacked) imageDao.stackedRankSizeDesc(anchor.variantBytes, anchor.id)
                else imageDao.timelineRankSizeDesc(anchor.size, anchor.id)
        }
        val total = imageDao.countActiveTimeline(stacked)
        val before = timelineIdsBefore(anchor, sort, radius, stacked)
        val after = timelineIdsAfter(anchor, sort, radius, stacked)
        val ids = ArrayList<Long>(before.size + 1 + after.size).apply {
            addAll(before)
            add(anchor.id)
            addAll(after)
        }
        return TimelineWindow(total = total, rank = rank, ids = ids, anchorIndex = before.size)
    }

    /** [sort] 序上 [anchor] 之后（时间线后方）的一页 id，不含锚点；返回序即时间线顺序，可直接接到窗口尾部 */
    suspend fun timelineIdsAfter(anchor: ImageEntity, sort: GallerySort, limit: Int, stacked: Boolean): List<Long> = when (sort) {
        GallerySort.DATE_DESC -> imageDao.timelineIdsAfterDateDesc(anchor.mtime, anchor.id, limit, stacked)
        // ASC 与 DESC 互为镜像：asc 的「后方」即 desc 的「前方」，该 DAO 返回序恰为 mtime ASC
        GallerySort.DATE_ASC -> imageDao.timelineIdsBeforeDateDesc(anchor.mtime, anchor.id, limit, stacked)
        GallerySort.SIZE_DESC -> if (stacked) imageDao.stackedIdsAfterSizeDesc(anchor.variantBytes, anchor.id, limit)
            else imageDao.timelineIdsAfterSizeDesc(anchor.size, anchor.id, limit)
    }

    /** [sort] 序上 [anchor] 之前（时间线前方）的一页 id，不含锚点；DAO 返回序离键近→远，翻转后即时间线顺序，可直接接到窗口头部 */
    suspend fun timelineIdsBefore(anchor: ImageEntity, sort: GallerySort, limit: Int, stacked: Boolean): List<Long> = when (sort) {
        GallerySort.DATE_DESC -> imageDao.timelineIdsBeforeDateDesc(anchor.mtime, anchor.id, limit, stacked).reversed()
        GallerySort.DATE_ASC -> imageDao.timelineIdsAfterDateDesc(anchor.mtime, anchor.id, limit, stacked).reversed()
        GallerySort.SIZE_DESC -> (if (stacked) imageDao.stackedIdsBeforeSizeDesc(anchor.variantBytes, anchor.id, limit)
            else imageDao.timelineIdsBeforeSizeDesc(anchor.size, anchor.id, limit)).reversed()
    }

    // ---- 详情 ----

    suspend fun getById(id: Long): ImageEntity? = imageDao.getById(id)

    /** 按 id 取图；分块查询，避免 id 过多触及 SQLite 变量上限（调用方均按 id 重映，不依赖顺序）。 */
    suspend fun getByIds(ids: List<Long>): List<ImageEntity> =
        if (ids.isEmpty()) emptyList()
        else if (ids.size <= SQL_IDS_CHUNK) imageDao.getByIds(ids)
        else ids.chunked(SQL_IDS_CHUNK).flatMap { imageDao.getByIds(it) }

    /** 同一张图的全部现存版本（原图 / 预览图 / 缩略图） */
    suspend fun familyMembers(group: Long): List<ImageEntity> = imageDao.familyMembers(group)

    // ---- 仪表盘统计 ----

    fun observeImageCount(): Flow<Int> = imageDao.observeImageCount().throttleLatest()
    fun observeTotalSize(): Flow<Long> = imageDao.observeTotalSize().throttleLatest()
    fun observeCleanedStats(): Flow<ImageDao.CleanedStatsRow> = imageDao.observeCleanedStats().calmDuringBulkWrites()
    fun observeCleanedStatsSince(since: Long): Flow<ImageDao.CleanedStatsRow> = imageDao.observeCleanedStatsSince(since).calmDuringBulkWrites()
    fun observeSourceBreakdown(): Flow<List<ImageDao.SourceBreakdownRow>> = imageDao.observeSourceBreakdown().throttleLatest()
    fun observeArchiveCount(statuses: List<String>): Flow<Int> = imageDao.observeArchiveCount(statuses).throttleLatest()
    fun observeThumbnailTrashSummary(): Flow<ImageDao.ThumbnailTrashSummary> =
        imageDao.observeThumbnailTrashSummary().calmDuringBulkWrites()

    companion object {
        /** 单条 IN 查询里 id 的安全上限（远低于 SQLite 999/32766 变量限制） */
        private const val SQL_IDS_CHUNK = 900

        /** 详情页时间线窗口默认半径（锚点前后各取的 id 数），滑动耗尽再续页 */
        const val TIMELINE_WINDOW_RADIUS = 500

        /** 图库网格离已加载范围超过这么多项（快速滚动拖得远）时直接在目标位置重新加载 */
        const val JUMP_THRESHOLD = 600
    }
}

private fun List<ImageDao.BucketCountRow>.toBucketCountMap(): Map<Long, Int> =
    associate { row -> row.bucketKey to row.count }

/** 聚合观察流节流周期：扫描 / 分析批量提交期间把 200w 行 COUNT/SUM/GROUP BY 重查压到每周期至多一次（批量删除等期间更长） */
private const val AGGREGATE_THROTTLE_MS = 1_500L

/**
 * 聚合观察流节流：emit 后原地挂起 [periodMs]，让背压传导进 Room 的重查循环——其失效信号
 * 通道是 CONFLATED，挂起期间的多次表失效会合并为一次重查。首个值仍立即下发。
 * 不能换成 sample/debounce：二者内部先起协程急切收集上游，重查仍会每次失效各跑一遍，
 * 只是丢弃下发结果，省不掉查询本身。
 */
private fun <T> Flow<T>.throttleLatest(periodMs: Long = AGGREGATE_THROTTLE_MS): Flow<T> =
    transform { value ->
        emit(value)
        delay(periodMs)
        BulkWrites.awaitIdle(BulkWrites.REFRESH_MS - periodMs)
    }
