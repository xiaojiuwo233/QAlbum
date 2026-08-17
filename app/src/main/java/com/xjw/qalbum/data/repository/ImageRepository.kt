package com.xjw.qalbum.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.room.withTransaction
import com.xjw.qalbum.data.db.AppDatabase
import com.xjw.qalbum.data.db.dao.DedupExclusionDao
import com.xjw.qalbum.data.db.dao.EmbeddingDao
import com.xjw.qalbum.data.db.dao.ImageDao
import com.xjw.qalbum.data.db.dao.ImageFeatureRow
import com.xjw.qalbum.data.db.dao.OcrDao
import com.xjw.qalbum.data.db.dao.TagDao
import com.xjw.qalbum.data.db.entity.DedupExclusionEntity
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.data.db.entity.OcrTextEntity
import com.xjw.qalbum.domain.model.GallerySort
import com.xjw.qalbum.domain.model.TimelineWindow
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
    private val db: AppDatabase,
    private val imageDao: ImageDao,
    private val ocrDao: OcrDao,
    private val dedupExclusionDao: DedupExclusionDao,
    private val tagDao: TagDao,
    private val embeddingDao: EmbeddingDao,
) {

    // ---- 时间线 ----

    /** 时间线 Paging 流：默认每页 50 条，预加载 4 页；按 [sort] 选择对应排序的 PagingSource */
    fun timelinePager(sort: GallerySort): Flow<PagingData<ImageEntity>> {
        val factory = when (sort) {
            GallerySort.DATE_DESC -> imageDao::pagingActiveTimeline
            GallerySort.DATE_ASC -> imageDao::pagingActiveTimelineByDateAsc
            GallerySort.SIZE_DESC -> imageDao::pagingActiveTimelineBySize
        }
        return Pager(
            config = PagingConfig(
                pageSize = 50,
                prefetchDistance = 200,
                initialLoadSize = 50,
                enablePlaceholders = false,
            ),
            pagingSourceFactory = factory,
        ).flow
    }

    fun favoriteTimelinePager(): Flow<PagingData<ImageEntity>> {
        return Pager(
            config = PagingConfig(
                pageSize = 50,
                prefetchDistance = 100,
                initialLoadSize = 50,
                enablePlaceholders = false,
            ),
            pagingSourceFactory = { imageDao.pagingFavoriteTimeline() },
        ).flow
    }

    /** 某标签下活跃图片的 Paging 流（标签管理页用） */
    fun imagesByTagPager(tagId: Long): Flow<PagingData<ImageEntity>> {
        return Pager(
            config = PagingConfig(
                pageSize = 60,
                prefetchDistance = 120,
                initialLoadSize = 60,
                enablePlaceholders = false,
            ),
            pagingSourceFactory = { imageDao.pagingImagesByTag(tagId) },
        ).flow
    }

    suspend fun countImagesByTag(tagId: Long): Int = imageDao.countActiveImagesByTag(tagId)

    /** keyset 分块取某标签下活跃图片 id（按 tag 全删时逐块处理，不一次性装载整组） */
    suspend fun imageIdsByTagChunk(tagId: Long, afterId: Long, limit: Int): List<Long> =
        imageDao.imageIdsByTagChunk(tagId, afterId, limit)

    fun observeTimelineDateBucketCounts(): Flow<Map<Long, Int>> =
        imageDao.observeTimelineDateBucketCounts().map { rows -> rows.toBucketCountMap() }.throttleLatest()

    fun observeTimelineSizeBucketCounts(): Flow<Map<Long, Int>> =
        imageDao.observeTimelineSizeBucketCounts().map { rows -> rows.toBucketCountMap() }.throttleLatest()

    fun observeFavoriteDateBucketCounts(): Flow<Map<Long, Int>> =
        imageDao.observeFavoriteDateBucketCounts().map { rows -> rows.toBucketCountMap() }.throttleLatest()

    /** 某标签下活跃图片的日期分桶计数（标签详情页时间线表头用，与图库同口径） */
    fun observeTagDateBucketCounts(tagId: Long): Flow<Map<Long, Int>> =
        imageDao.observeTagDateBucketCounts(tagId).map { rows -> rows.toBucketCountMap() }.throttleLatest()

    /** 收藏时间线仍全量取 id：集合由用户手动收藏产生，规模与主时间线不同量级 */
    suspend fun favoriteTimelineIds(): List<Long> = imageDao.favoriteTimelineIds()

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
        radius: Int = TIMELINE_WINDOW_RADIUS,
    ): TimelineWindow {
        val rank = when (sort) {
            GallerySort.DATE_DESC -> imageDao.timelineRankDateDesc(anchor.mtime, anchor.id)
            GallerySort.DATE_ASC -> imageDao.timelineRankDateAsc(anchor.mtime, anchor.id)
            GallerySort.SIZE_DESC -> imageDao.timelineRankSizeDesc(anchor.size, anchor.id)
        }
        val total = imageDao.countActiveTimeline()
        val before = timelineIdsBefore(anchor, sort, radius)
        val after = timelineIdsAfter(anchor, sort, radius)
        val ids = ArrayList<Long>(before.size + 1 + after.size).apply {
            addAll(before)
            add(anchor.id)
            addAll(after)
        }
        return TimelineWindow(total = total, rank = rank, ids = ids, anchorIndex = before.size)
    }

    /** [sort] 序上 [anchor] 之后（时间线后方）的一页 id，不含锚点；返回序即时间线顺序，可直接接到窗口尾部 */
    suspend fun timelineIdsAfter(anchor: ImageEntity, sort: GallerySort, limit: Int): List<Long> = when (sort) {
        GallerySort.DATE_DESC -> imageDao.timelineIdsAfterDateDesc(anchor.mtime, anchor.id, limit)
        // ASC 与 DESC 互为镜像：asc 的「后方」即 desc 的「前方」，该 DAO 返回序恰为 mtime ASC
        GallerySort.DATE_ASC -> imageDao.timelineIdsBeforeDateDesc(anchor.mtime, anchor.id, limit)
        GallerySort.SIZE_DESC -> imageDao.timelineIdsAfterSizeDesc(anchor.size, anchor.id, limit)
    }

    /** [sort] 序上 [anchor] 之前（时间线前方）的一页 id，不含锚点；DAO 返回序离键近→远，翻转后即时间线顺序，可直接接到窗口头部 */
    suspend fun timelineIdsBefore(anchor: ImageEntity, sort: GallerySort, limit: Int): List<Long> = when (sort) {
        GallerySort.DATE_DESC -> imageDao.timelineIdsBeforeDateDesc(anchor.mtime, anchor.id, limit).reversed()
        GallerySort.DATE_ASC -> imageDao.timelineIdsAfterDateDesc(anchor.mtime, anchor.id, limit).reversed()
        GallerySort.SIZE_DESC -> imageDao.timelineIdsBeforeSizeDesc(anchor.size, anchor.id, limit).reversed()
    }

    // ---- 详情 ----

    suspend fun getById(id: Long): ImageEntity? = imageDao.getById(id)

    /** 按 id 取图；分块查询，避免 id 过多触及 SQLite 变量上限（调用方均按 id 重映，不依赖顺序）。 */
    suspend fun getByIds(ids: List<Long>): List<ImageEntity> =
        if (ids.isEmpty()) emptyList()
        else if (ids.size <= SQL_IDS_CHUNK) imageDao.getByIds(ids)
        else ids.chunked(SQL_IDS_CHUNK).flatMap { imageDao.getByIds(it) }

    /**
     * 批量取多个重复组的 active 成员，返回 groupId → 成员列表。
     * 分块走 IN 查询替代逐组 N+1，几千组也只需个位数次查询；组内顺序保留图优先。
     */
    suspend fun getActiveByDuplicateGroups(groupIds: List<Long>): Map<Long, List<ImageEntity>> {
        if (groupIds.isEmpty()) return emptyMap()
        val byGroup = HashMap<Long, MutableList<ImageEntity>>()
        groupIds.chunked(SQL_IDS_CHUNK).forEach { chunk ->
            imageDao.getActiveByDuplicateGroupIds(chunk).forEach { img ->
                val gid = img.duplicateGroupId ?: return@forEach
                byGroup.getOrPut(gid) { ArrayList() }.add(img)
            }
        }
        return byGroup
    }

    /** 取若干图的视觉特征（反馈学习用） */
    suspend fun featuresByIds(ids: List<Long>): List<ImageFeatureRow> =
        if (ids.isEmpty()) emptyList() else imageDao.featuresByIds(ids)

    /** 选中图片的原始字节总和；分块查询，避免 id 过多触及 SQLite 变量上限 */
    suspend fun sumSizeByIds(ids: List<Long>): Long {
        if (ids.isEmpty()) return 0L
        return ids.chunked(SQL_IDS_CHUNK).sumOf { imageDao.sumSizeByIds(it) }
    }

    suspend fun getByDuplicateGroup(groupId: Long): List<ImageEntity> =
        imageDao.getByDuplicateGroup(groupId)

    /**
     * 拆分重复组：用户判定该组「差异过大」非真重复。整组成员写入一个新 cohort（重新查重时禁止再聚到一起），
     * 并清除该组的重复分配。返回受影响成员数。
     *
     * 整体包进事务：maxCohortId 读取与 insertAll 在单写锁下原子，并发拆分不会共用同一 cohort
     * 把无关组并进一个排斥集；读组、写排斥、清分配三步也不会被查重管线的 clear/assign 交错撕开。
     */
    suspend fun splitDuplicateGroup(groupId: Long): Int = db.withTransaction {
        val ids = imageDao.imageIdsInDuplicateGroup(groupId)
        if (ids.size >= 2) {
            val cohortId = dedupExclusionDao.maxCohortId() + 1
            dedupExclusionDao.insertAll(ids.map { DedupExclusionEntity(cohortId = cohortId, imageId = it) })
        }
        imageDao.clearDuplicateGroup(groupId)
        ids.size
    }

    suspend fun getOcr(imageId: Long): OcrTextEntity? = ocrDao.getByImageId(imageId)

    suspend fun setUserKeep(imageId: Long, keep: Boolean?) = imageDao.setUserKeep(imageId, keep)

    /**
     * 批量设置/清除用户保留：分块 IN 更新并包进单个事务，整批只触发一次表失效——
     * 逐张独立事务在万级选中时是分钟级 fsync 风暴（同 [setFavoriteBatch]，守「禁全表逐行写」约束）。
     */
    suspend fun setUserKeepBatch(ids: List<Long>, keep: Boolean?) {
        if (ids.isEmpty()) return
        db.withTransaction {
            ids.chunked(SQL_IDS_CHUNK).forEach { imageDao.setUserKeepByIds(it, keep) }
        }
    }

    /**
     * 永久删除路径的派生数据级联清理：ocr_text / ocr_fts（FTS4 独立表无 CASCADE）/
     * image_tags / image_embeddings / dedup_exclusions 按 ≤[DERIVED_PURGE_CHUNK] 分块、
     * 单事务删除。images 行本身保留（status='deleted' 是「已清理」统计的纯净数据源），
     * 但其派生数据必须清掉——死向量会占据语义搜索 TopK 名额，孤儿 OCR/FTS 行只增不减。
     * 幂等，调用方传入已确认删除成功的 imageId（页级 ≤ 数百个）。
     */
    suspend fun purgeDerivedData(ids: List<Long>) {
        if (ids.isEmpty()) return
        db.withTransaction {
            ids.chunked(DERIVED_PURGE_CHUNK).forEach { chunk ->
                ocrDao.deleteFtsByImageIds(chunk)
                ocrDao.deleteTextByImageIds(chunk)
                tagDao.unlinkAllByImageIds(chunk)
                embeddingDao.deleteByImageIds(chunk)
                dedupExclusionDao.deleteByImageIds(chunk)
            }
        }
    }

    /** keyset 分页取 status='deleted' 行 id（历史派生数据一次性清理用，禁止全表装载）。 */
    suspend fun deletedIdsPaged(afterId: Long, limit: Int): List<Long> =
        imageDao.deletedIdsPaged(afterId, limit)

    suspend fun setFavorite(imageId: Long, isFavorite: Boolean) =
        imageDao.setFavorite(imageId, isFavorite)

    /**
     * 批量收藏 / 取消收藏：分块 IN 更新并包进单个事务，整批只触发一次表失效——
     * 逐行提交会让 200w 行的收藏计数 / 分桶聚合观察流每行各重跑一遍。
     */
    suspend fun setFavoriteBatch(ids: List<Long>, isFavorite: Boolean) {
        if (ids.isEmpty()) return
        db.withTransaction {
            ids.chunked(SQL_IDS_CHUNK).forEach { imageDao.setFavoriteByIds(it, isFavorite) }
        }
    }

    // ---- 搜索（Phase 3 起） ----

    suspend fun search(query: String, limit: Int = 50, offset: Int = 0): List<OcrDao.SearchHit> =
        ocrDao.searchWithSnippet(query, limit, offset)

    suspend fun searchCount(query: String): Int = ocrDao.countMatches(query)

    // ---- 仪表盘统计 ----

    fun observeImageCount(): Flow<Int> = imageDao.observeImageCount().throttleLatest()
    fun observeTotalSize(): Flow<Long> = imageDao.observeTotalSize().throttleLatest()
    fun observeCleanedStats(): Flow<ImageDao.CleanedStatsRow> = imageDao.observeCleanedStats()
    fun observeCleanedStatsSince(since: Long): Flow<ImageDao.CleanedStatsRow> = imageDao.observeCleanedStatsSince(since)
    fun observeSourceBreakdown(): Flow<List<ImageDao.SourceBreakdownRow>> = imageDao.observeSourceBreakdown().throttleLatest()
    fun observeAnalysisProgress(): Flow<ImageDao.AnalysisProgressRow> = imageDao.observeAnalysisProgress().throttleLatest()

    /** 「已分析」= 完成全部当前需要阶段的图片数（与图库完成角标同口径） */
    fun observeFullyAnalyzedCount(ocrRequired: Boolean, aiRequired: Boolean, embRequired: Boolean): Flow<Int> =
        imageDao.observeFullyAnalyzedCount(ocrRequired, aiRequired, embRequired).throttleLatest()
    fun observeImageHealth(): Flow<ImageDao.ImageHealthRow> = imageDao.observeImageHealth().throttleLatest()
    fun observeFavoriteCount(): Flow<Int> = imageDao.observeFavoriteCount().throttleLatest()
    fun observeThumbnailTrashSummary(): Flow<ImageDao.ThumbnailTrashSummary> =
        imageDao.observeThumbnailTrashSummary()

    companion object {
        /** 单条 IN 查询里 id 的安全上限（远低于 SQLite 999/32766 变量限制） */
        private const val SQL_IDS_CHUNK = 900

        /** 详情页时间线窗口默认半径（锚点前后各取的 id 数），滑动耗尽再续页 */
        const val TIMELINE_WINDOW_RADIUS = 500

        /** 派生数据级联清理的分块大小（< SQLite 变量上限） */
        private const val DERIVED_PURGE_CHUNK = 500
    }
}

private fun List<ImageDao.BucketCountRow>.toBucketCountMap(): Map<Long, Int> =
    associate { row -> row.bucketKey to row.count }

/** 聚合观察流节流周期：扫描 / 分析批量提交期间把 200w 行 COUNT/SUM/GROUP BY 重查压到每周期至多一次 */
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
    }
