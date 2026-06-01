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

    suspend fun timelineSnapshot(): List<ImageEntity> = imageDao.listActiveTimeline()
    suspend fun favoriteTimelineSnapshot(): List<ImageEntity> = imageDao.listFavoriteTimeline()

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
    fun observeSourceBreakdown(): Flow<List<ImageDao.SourceBreakdownRow>> = imageDao.observeSourceBreakdown()
    fun observeAnalysisProgress(): Flow<ImageDao.AnalysisProgressRow> = imageDao.observeAnalysisProgress()
    fun observeImageHealth(): Flow<ImageDao.ImageHealthRow> = imageDao.observeImageHealth()
    fun observeFavoriteCount(): Flow<Int> = imageDao.observeFavoriteCount()
    fun observeThumbnailTrashSummary(): Flow<ImageDao.ThumbnailTrashSummary> =
        imageDao.observeThumbnailTrashSummary()
}
