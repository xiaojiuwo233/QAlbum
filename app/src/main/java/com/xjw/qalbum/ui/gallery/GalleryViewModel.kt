package com.xjw.qalbum.ui.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.data.repository.ImageRepository
import kotlinx.coroutines.flow.Flow

/**
 * 时间线页 ViewModel
 *
 * Paging 3 流通过 [cachedIn] 缓存在 viewModelScope 中，
 * 这样配置变更（横竖屏）时不会重新发起查询。
 */
class GalleryViewModel(
    imageRepository: ImageRepository,
) : ViewModel() {

    val images: Flow<PagingData<ImageEntity>> =
        imageRepository.timelinePager().cachedIn(viewModelScope)
}
