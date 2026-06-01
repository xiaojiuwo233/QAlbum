package com.xjw.qalbum.ui.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.insertSeparators
import androidx.paging.map as pagingMap
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.data.repository.ImageRepository
import com.xjw.qalbum.domain.model.GallerySort
import com.xjw.qalbum.service.cleanup.ImageFileManager
import com.xjw.qalbum.service.cleanup.QuarantineManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map as flowMap
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Date
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 时间线页 ViewModel
 *
 * Paging 3 流通过 [cachedIn] 缓存在 viewModelScope 中，
 * 这样配置变更（横竖屏）时不会重新发起查询。
 *
 * 图库偏好（列数 / 排序 / 大图阈值）持久化在 [SettingsRepository]；
 * 排序变化时用 [flatMapLatest] 切换到对应排序的 PagingSource，并按排序插入日期 / 大小分类标题。
 */
class GalleryViewModel(
    private val imageRepository: ImageRepository,
    private val imageFileManager: ImageFileManager,
    private val quarantineManager: QuarantineManager,
    private val settings: SettingsRepository,
) : ViewModel() {

    // ---- 图库偏好 ----

    val galleryColumns: StateFlow<Int> = settings.galleryColumns
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.DEFAULT_GALLERY_COLUMNS)

    val gallerySort: StateFlow<GallerySort> = settings.gallerySort
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GallerySort.DATE_DESC)

    val largeThresholdBytes: StateFlow<Long> = settings.galleryLargeThresholdBytes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.DEFAULT_LARGE_THRESHOLD_BYTES)

    fun setColumns(columns: Int) {
        viewModelScope.launch { settings.setGalleryColumns(columns) }
    }

    fun setSort(sort: GallerySort) {
        viewModelScope.launch { settings.setGallerySort(sort) }
    }

    fun setLargeThreshold(bytes: Long) {
        viewModelScope.launch { settings.setGalleryLargeThresholdBytes(bytes) }
    }

    // ---- 时间线 ----

    val timeline: Flow<PagingData<GalleryTimelineItem>> =
        settings.gallerySort.flatMapLatest { sort ->
            imageRepository.timelinePager(sort).withHeaders(sort)
        }.cachedIn(viewModelScope)

    val favoriteTimeline: Flow<PagingData<GalleryTimelineItem>> =
        imageRepository.favoriteTimelinePager().withDateHeaders()
            .cachedIn(viewModelScope)

    val favoriteCount: Flow<Int> = imageRepository.observeFavoriteCount()

    fun exportImages(ids: Set<Long>, onDone: (Int) -> Unit = {}) {
        viewModelScope.launch {
            val count = ids.count { imageFileManager.exportToGallery(it) }
            onDone(count)
        }
    }

    fun addFavorites(ids: Set<Long>, onDone: (Int) -> Unit = {}) {
        viewModelScope.launch {
            onDone(setFavoriteState(ids, true))
        }
    }

    fun removeFavorites(ids: Set<Long>, onDone: (Int) -> Unit = {}) {
        viewModelScope.launch {
            onDone(setFavoriteState(ids, false))
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

    private suspend fun setFavoriteState(ids: Set<Long>, favorite: Boolean): Int {
        val images = imageRepository.getByIds(ids.toList()).associateBy { it.id }
        var changed = 0
        ids.forEach { id ->
            val image = images[id] ?: return@forEach
            if (image.isFavorite != favorite) {
                imageRepository.setFavorite(id, favorite)
                changed++
            }
        }
        return changed
    }
}

sealed class GalleryTimelineItem {
    /** 分类标题：[key] 唯一且稳定（供 Paging/LazyGrid 复用判定），[label] 为展示文案 */
    data class Header(val key: String, val label: String) : GalleryTimelineItem()
    data class Photo(val image: ImageEntity) : GalleryTimelineItem()
}

private fun Flow<PagingData<ImageEntity>>.withHeaders(sort: GallerySort): Flow<PagingData<GalleryTimelineItem>> =
    when (sort) {
        GallerySort.SIZE_DESC -> withSizeHeaders()
        GallerySort.DATE_DESC, GallerySort.DATE_ASC -> withDateHeaders()
    }

private fun Flow<PagingData<ImageEntity>>.withDateHeaders(): Flow<PagingData<GalleryTimelineItem>> =
    flowMap { pagingData: PagingData<ImageEntity> ->
        pagingData
            .pagingMap { image: ImageEntity -> GalleryTimelineItem.Photo(image) as GalleryTimelineItem }
            .insertSeparators { before: GalleryTimelineItem?, after: GalleryTimelineItem? ->
                val afterPhoto = after as? GalleryTimelineItem.Photo ?: return@insertSeparators null
                val beforePhoto = before as? GalleryTimelineItem.Photo
                val afterKey = dayKey(afterPhoto.image.mtime)
                if (beforePhoto == null || dayKey(beforePhoto.image.mtime) != afterKey) {
                    GalleryTimelineItem.Header(key = "d-$afterKey", label = dayLabel(afterPhoto.image.mtime))
                } else {
                    null
                }
            }
    }

private fun Flow<PagingData<ImageEntity>>.withSizeHeaders(): Flow<PagingData<GalleryTimelineItem>> =
    flowMap { pagingData: PagingData<ImageEntity> ->
        pagingData
            .pagingMap { image: ImageEntity -> GalleryTimelineItem.Photo(image) as GalleryTimelineItem }
            .insertSeparators { before: GalleryTimelineItem?, after: GalleryTimelineItem? ->
                val afterPhoto = after as? GalleryTimelineItem.Photo ?: return@insertSeparators null
                val beforePhoto = before as? GalleryTimelineItem.Photo
                val afterKey = sizeBucketId(afterPhoto.image.size)
                if (beforePhoto == null || sizeBucketId(beforePhoto.image.size) != afterKey) {
                    GalleryTimelineItem.Header(key = "s-$afterKey", label = sizeBucketLabel(afterPhoto.image.size))
                } else {
                    null
                }
            }
    }

// ---- 日期分桶 ----

private fun dayKey(timestampMs: Long): Int {
    val calendar = Calendar.getInstance()
    calendar.timeInMillis = timestampMs
    val year = calendar.get(Calendar.YEAR)
    val day = calendar.get(Calendar.DAY_OF_YEAR)
    return year * 1000 + day
}

private val dateFormatter = SimpleDateFormat("yyyy年M月d日", Locale.CHINA)

private fun dayLabel(timestampMs: Long): String {
    val target = Calendar.getInstance().apply { timeInMillis = timestampMs }
    val today = Calendar.getInstance()
    val yesterday = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
    return when {
        sameDay(target, today) -> "今天"
        sameDay(target, yesterday) -> "昨天"
        else -> dateFormatter.format(Date(timestampMs))
    }
}

private fun sameDay(a: Calendar, b: Calendar): Boolean =
    a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
        a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

// ---- 大小分桶（大到小排序时使用）：≥10MB 合并，1~9MB 每 1MB 一桶，<1MB 每 100KB 一桶 ----

private const val BYTES_MB = 1024L * 1024L
private const val BYTES_100KB = 100L * 1024L

private fun sizeBucketId(size: Long): Long = when {
    size >= 10 * BYTES_MB -> 1000L                 // 10MB 以上合并为一桶
    size >= BYTES_MB -> 100L + (size / BYTES_MB)   // 1~9MB → 101..109
    else -> size / BYTES_100KB                      // <1MB → 0..10（每 100KB）
}

private fun sizeBucketLabel(size: Long): String = when {
    size >= 10 * BYTES_MB -> "10 MB 以上"
    size >= BYTES_MB -> "${size / BYTES_MB} MB"
    size < BYTES_100KB -> "100 KB 以下"
    else -> "${(size / BYTES_100KB) * 100} KB"
}
