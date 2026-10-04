package com.xjw.qalbum.service.cleanup

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.xjw.qalbum.data.db.dao.ImageDao
import com.xjw.qalbum.data.db.entity.ImageEntity
import kotlinx.coroutines.flow.Flow

/** 沿用历史索引名称，实际仅浏览受保护的 QQ 预览缓存。 */
class ThumbnailTrashManager(private val imageDao: ImageDao) {
    fun observeSummary(): Flow<ImageDao.ThumbnailTrashSummary> = imageDao.observeThumbnailTrashSummary()
    fun pagingItems(): Flow<PagingData<ImageEntity>> = Pager(
        PagingConfig(pageSize = 60, prefetchDistance = 120, enablePlaceholders = false),
        pagingSourceFactory = { imageDao.pagingThumbnailTrash() },
    ).flow
}
