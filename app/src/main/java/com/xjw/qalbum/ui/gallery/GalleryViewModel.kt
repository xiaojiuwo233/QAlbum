package com.xjw.qalbum.ui.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.insertSeparators
import androidx.paging.map as pagingMap
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.data.repository.ImageRepository
import com.xjw.qalbum.service.cleanup.QuarantineManager
import com.xjw.qalbum.service.cleanup.VaultManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map as flowMap
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * 时间线页 ViewModel
 *
 * Paging 3 流通过 [cachedIn] 缓存在 viewModelScope 中，
 * 这样配置变更（横竖屏）时不会重新发起查询。
 */
class GalleryViewModel(
    private val imageRepository: ImageRepository,
    private val vaultManager: VaultManager,
    private val quarantineManager: QuarantineManager,
) : ViewModel() {

    val timeline: Flow<PagingData<GalleryTimelineItem>> =
        imageRepository.timelinePager()
            .flowMap { pagingData: PagingData<ImageEntity> ->
                pagingData
                    .pagingMap { image: ImageEntity -> GalleryTimelineItem.Photo(image) as GalleryTimelineItem }
                    .insertSeparators { before: GalleryTimelineItem?, after: GalleryTimelineItem? ->
                        val afterPhoto = after as? GalleryTimelineItem.Photo ?: return@insertSeparators null
                        val beforePhoto = before as? GalleryTimelineItem.Photo
                        if (beforePhoto == null || dayKey(beforePhoto.image.mtime) != dayKey(afterPhoto.image.mtime)) {
                            GalleryTimelineItem.Header(afterPhoto.image.mtime)
                        } else {
                            null
                        }
                    }
            }
            .cachedIn(viewModelScope)

    fun exportImages(ids: Set<Long>, onDone: (Int) -> Unit = {}) {
        viewModelScope.launch {
            val count = ids.count { vaultManager.exportToGallery(it) }
            onDone(count)
        }
    }

    fun favoriteImages(ids: Set<Long>, onDone: (Int) -> Unit = {}) {
        viewModelScope.launch {
            ids.forEach { imageRepository.setProtected(it, true) }
            onDone(ids.size)
        }
    }

    fun moveImagesToTrash(ids: Set<Long>, onDone: (Int) -> Unit = {}) {
        viewModelScope.launch {
            val count = ids.count { quarantineManager.quarantine(it, "用户批量删除") }
            onDone(count)
        }
    }

    fun deleteImagesPermanently(ids: Set<Long>, onDone: (Int) -> Unit = {}) {
        viewModelScope.launch {
            val count = ids.count { quarantineManager.deletePermanently(it) }
            onDone(count)
        }
    }
}

sealed class GalleryTimelineItem {
    data class Header(val timestampMs: Long) : GalleryTimelineItem()
    data class Photo(val image: ImageEntity) : GalleryTimelineItem()
}

private fun dayKey(timestampMs: Long): Int {
    val calendar = Calendar.getInstance()
    calendar.timeInMillis = timestampMs
    val year = calendar.get(Calendar.YEAR)
    val day = calendar.get(Calendar.DAY_OF_YEAR)
    return year * 1000 + day
}
