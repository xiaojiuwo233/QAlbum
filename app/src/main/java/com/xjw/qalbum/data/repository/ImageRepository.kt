package com.xjw.qalbum.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.xjw.qalbum.data.db.dao.ImageDao
import com.xjw.qalbum.data.db.dao.OcrDao
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.data.db.entity.OcrTextEntity
import com.xjw.qalbum.domain.model.GallerySort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 图片仓库
 *
 * 对 UI 层（ViewModel / Screen）暴露干净的、与具体存储无关的查询接口。
 * 不直接操作文件系统 —— 扫描相关逻辑由 service.indexer 模块负责。
 */
class ImageRepository(
    private val imageDao: ImageDao,
    private val ocrDao: OcrDao,
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

    suspend fun timelineSnapshot(): List<ImageEntity> = imageDao.listActiveTimeline()
    suspend fun favoriteTimelineSnapshot(): List<ImageEntity> = imageDao.listFavoriteTimeline()

    fun observeTimelineDateBucketCounts(): Flow<Map<Long, Int>> =
        imageDao.observeTimelineDateBucketCounts().map { rows -> rows.toBucketCountMap() }

    fun observeTimelineSizeBucketCounts(): Flow<Map<Long, Int>> =
        imageDao.observeTimelineSizeBucketCounts().map { rows -> rows.toBucketCountMap() }

    fun observeFavoriteDateBucketCounts(): Flow<Map<Long, Int>> =
        imageDao.observeFavoriteDateBucketCounts().map { rows -> rows.toBucketCountMap() }

    /** 详情页时间线 id 列表（轻量，支撑左右滑动按需加载）；与图库网格使用同一 [sort] */
    suspend fun timelineIds(sort: GallerySort): List<Long> = when (sort) {
        GallerySort.DATE_DESC -> imageDao.timelineIds()
        GallerySort.DATE_ASC -> imageDao.timelineIdsByDateAsc()
        GallerySort.SIZE_DESC -> imageDao.timelineIdsBySize()
    }
    suspend fun favoriteTimelineIds(): List<Long> = imageDao.favoriteTimelineIds()

    // ---- 详情 ----

    suspend fun getById(id: Long): ImageEntity? = imageDao.getById(id)

    suspend fun getByIds(ids: List<Long>): List<ImageEntity> =
        if (ids.isEmpty()) emptyList() else imageDao.getByIds(ids)

    /** 选中图片的原始字节总和；分块查询，避免 id 过多触及 SQLite 变量上限 */
    suspend fun sumSizeByIds(ids: List<Long>): Long {
        if (ids.isEmpty()) return 0L
        return ids.chunked(SQL_IDS_CHUNK).sumOf { imageDao.sumSizeByIds(it) }
    }

    suspend fun getByDuplicateGroup(groupId: Long): List<ImageEntity> =
        imageDao.getByDuplicateGroup(groupId)

    suspend fun getOcr(imageId: Long): OcrTextEntity? = ocrDao.getByImageId(imageId)

    suspend fun setUserKeep(imageId: Long, keep: Boolean?) = imageDao.setUserKeep(imageId, keep)

    suspend fun setFavorite(imageId: Long, isFavorite: Boolean) =
        imageDao.setFavorite(imageId, isFavorite)

    // ---- 搜索（Phase 3 起） ----

    suspend fun search(query: String, limit: Int = 50, offset: Int = 0): List<OcrDao.SearchHit> =
        ocrDao.searchWithSnippet(query, limit, offset)

    suspend fun searchCount(query: String): Int = ocrDao.countMatches(query)

    // ---- 仪表盘统计 ----

    fun observeImageCount(): Flow<Int> = imageDao.observeImageCount()
    fun observeTotalSize(): Flow<Long> = imageDao.observeTotalSize()
    fun observeCleanedStats(): Flow<ImageDao.CleanedStatsRow> = imageDao.observeCleanedStats()
    fun observeCleanedStatsSince(since: Long): Flow<ImageDao.CleanedStatsRow> = imageDao.observeCleanedStatsSince(since)
    fun observeSourceBreakdown(): Flow<List<ImageDao.SourceBreakdownRow>> = imageDao.observeSourceBreakdown()
    fun observeAnalysisProgress(): Flow<ImageDao.AnalysisProgressRow> = imageDao.observeAnalysisProgress()

    /** 「已分析」= 完成全部当前需要阶段的图片数（与图库完成角标同口径） */
    fun observeFullyAnalyzedCount(ocrRequired: Boolean, aiRequired: Boolean, embRequired: Boolean): Flow<Int> =
        imageDao.observeFullyAnalyzedCount(ocrRequired, aiRequired, embRequired)
    fun observeImageHealth(): Flow<ImageDao.ImageHealthRow> = imageDao.observeImageHealth()
    fun observeFavoriteCount(): Flow<Int> = imageDao.observeFavoriteCount()
    fun observeThumbnailTrashSummary(): Flow<ImageDao.ThumbnailTrashSummary> =
        imageDao.observeThumbnailTrashSummary()

    companion object {
        /** 单条 IN 查询里 id 的安全上限（远低于 SQLite 999/32766 变量限制） */
        private const val SQL_IDS_CHUNK = 900
    }
}

private fun List<ImageDao.BucketCountRow>.toBucketCountMap(): Map<Long, Int> =
    associate { row -> row.bucketKey to row.count }
