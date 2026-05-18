package com.xjw.qalbum.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.xjw.qalbum.data.db.dao.ImageDao
import com.xjw.qalbum.data.db.entity.ImageEntity
import kotlinx.coroutines.flow.Flow

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

    // ---- 详情 ----

    suspend fun getById(id: Long): ImageEntity? = imageDao.getById(id)

    suspend fun getVariants(groupId: Long): List<ImageEntity> = imageDao.getByVariantGroup(groupId)

    // ---- 仪表盘统计 ----

    fun observePhysicalCount(): Flow<Int> = imageDao.observeActiveCount()
    fun observeLogicalCount(): Flow<Int> = imageDao.observeLogicalCount()
    fun observeTotalSize(): Flow<Long> = imageDao.observeTotalSize()
    fun observeSourceBreakdown(): Flow<List<ImageDao.SourceBreakdownRow>> = imageDao.observeSourceBreakdown()
}
