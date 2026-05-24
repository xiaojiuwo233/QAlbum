package com.xjw.qalbum.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.xjw.qalbum.data.db.dao.ImageDao
import com.xjw.qalbum.data.db.dao.OcrDao
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.data.db.entity.OcrTextEntity
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

    /** 时间线 Paging 流：默认每页 50 条，预加载 4 页 */
    fun timelinePager(): Flow<PagingData<ImageEntity>> {
        return Pager(
            config = PagingConfig(
                pageSize = 50,
                prefetchDistance = 200,
                initialLoadSize = 50,
                enablePlaceholders = false,
            ),
            pagingSourceFactory = { imageDao.pagingActiveTimeline() },
        ).flow
    }

    suspend fun timelineSnapshot(): List<ImageEntity> = imageDao.listActiveTimeline()

    // ---- 详情 ----

    suspend fun getById(id: Long): ImageEntity? = imageDao.getById(id)

    suspend fun getByIds(ids: List<Long>): List<ImageEntity> =
        if (ids.isEmpty()) emptyList() else imageDao.getByIds(ids)

    suspend fun getVariants(groupId: Long): List<ImageEntity> = imageDao.getByVariantGroup(groupId)

    suspend fun getOcr(imageId: Long): OcrTextEntity? = ocrDao.getByImageId(imageId)

    suspend fun setUserKeep(imageId: Long, keep: Boolean?) = imageDao.setUserKeep(imageId, keep)

    suspend fun setProtected(imageId: Long, isProtected: Boolean) =
        imageDao.setProtected(imageId, isProtected)

    // ---- 搜索（Phase 3 起） ----

    suspend fun search(query: String, limit: Int = 50, offset: Int = 0): List<OcrDao.SearchHit> =
        ocrDao.searchWithSnippet(query, limit, offset)

    suspend fun searchCount(query: String): Int = ocrDao.countMatches(query)

    // ---- 仪表盘统计 ----

    fun observePhysicalCount(): Flow<Int> = imageDao.observeActiveCount()
    fun observeLogicalCount(): Flow<Int> = imageDao.observeLogicalCount()
    fun observeTotalSize(): Flow<Long> = imageDao.observeTotalSize()
    fun observeSourceBreakdown(): Flow<List<ImageDao.SourceBreakdownRow>> = imageDao.observeSourceBreakdown()
    fun observeAnalysisProgress(): Flow<ImageDao.AnalysisProgressRow> = imageDao.observeAnalysisProgress()
    fun observeImageHealth(): Flow<ImageDao.ImageHealthRow> = imageDao.observeImageHealth()
}
