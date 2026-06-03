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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map as flowMap
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
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
            when (sort) {
                GallerySort.SIZE_DESC -> imageRepository
                    .timelinePager(sort)
                    .withSizeHeaders(imageRepository.observeTimelineSizeBucketCounts())

                GallerySort.DATE_DESC, GallerySort.DATE_ASC -> imageRepository
                    .timelinePager(sort)
                    .withDateHeaders(imageRepository.observeTimelineDateBucketCounts())
            }
        }.cachedIn(viewModelScope)

    val favoriteTimeline: Flow<PagingData<GalleryTimelineItem>> =
        imageRepository
            .favoriteTimelinePager()
            .withDateHeaders(imageRepository.observeFavoriteDateBucketCounts())
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

private fun Flow<PagingData<ImageEntity>>.withDateHeaders(
    bucketCounts: Flow<Map<Long, Int>>,
): Flow<PagingData<GalleryTimelineItem>> =
    flowMap { pagingData: PagingData<ImageEntity> ->
        val counts = bucketCounts.first()
        pagingData
            .pagingMap { image: ImageEntity -> GalleryTimelineItem.Photo(image) as GalleryTimelineItem }
            .insertSeparators { before: GalleryTimelineItem?, after: GalleryTimelineItem? ->
                val afterPhoto = after as? GalleryTimelineItem.Photo ?: return@insertSeparators null
                val beforePhoto = before as? GalleryTimelineItem.Photo
                val afterKey = dayKey(afterPhoto.image.mtime)
                if (beforePhoto == null || dayKey(beforePhoto.image.mtime) != afterKey) {
                    GalleryTimelineItem.Header(
                        key = "d-$afterKey",
                        label = headerLabel(dayLabel(afterPhoto.image.mtime), counts[afterKey]),
                    )
                } else {
                    null
                }
            }
    }

private fun Flow<PagingData<ImageEntity>>.withSizeHeaders(
    bucketCounts: Flow<Map<Long, Int>>,
): Flow<PagingData<GalleryTimelineItem>> =
    flowMap { pagingData: PagingData<ImageEntity> ->
        val counts = bucketCounts.first()
        pagingData
            .pagingMap { image: ImageEntity -> GalleryTimelineItem.Photo(image) as GalleryTimelineItem }
            .insertSeparators { before: GalleryTimelineItem?, after: GalleryTimelineItem? ->
                val afterPhoto = after as? GalleryTimelineItem.Photo ?: return@insertSeparators null
                val beforePhoto = before as? GalleryTimelineItem.Photo
                val afterKey = sizeBucketId(afterPhoto.image.size)
                if (beforePhoto == null || sizeBucketId(beforePhoto.image.size) != afterKey) {
                    GalleryTimelineItem.Header(
                        key = "s-$afterKey",
                        label = headerLabel(sizeBucketLabel(afterPhoto.image.size), counts[afterKey]),
                    )
                } else {
                    null
                }
            }
    }

private fun headerLabel(label: String, count: Int?): String =
    if (count == null) label else "$label（$count）"

// ---- 日期分桶 ----

private fun dayKey(timestampMs: Long): Long {
    val calendar = Calendar.getInstance()
    calendar.timeInMillis = timestampMs
    val year = calendar.get(Calendar.YEAR)
    val day = calendar.get(Calendar.DAY_OF_YEAR)
    return year * 1000L + day
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

// ---- 大小分桶（大到小排序时使用）----
// ≥10MB 合并为一桶；1~9MB 每 1MB；100KB~1MB 每 100KB；10KB~100KB 每 10KB；<10KB 合并为一桶。
// id 用互不重叠的数值区间，保证相邻桶判定唯一。

private const val BYTES_MB = 1024L * 1024L
private const val BYTES_100KB = 100L * 1024L
private const val BYTES_10KB = 10L * 1024L

private fun sizeBucketId(size: Long): Long = when {
    size >= 10 * BYTES_MB -> 100000L                       // 10MB 以上
    size >= BYTES_MB -> 10000L + (size / BYTES_MB)         // 1~9MB → 10001..10009
    size >= BYTES_100KB -> 1000L + (size / BYTES_100KB)    // 100KB~1MB → 1001..1010
    size >= BYTES_10KB -> 100L + (size / BYTES_10KB)       // 10KB~100KB → 101..109
    else -> 0L                                             // <10KB
}

private fun sizeBucketLabel(size: Long): String = when {
    size >= 10 * BYTES_MB -> "10 MB 以上"
    size >= BYTES_MB -> "${size / BYTES_MB} MB"
    size >= BYTES_100KB -> "${(size / BYTES_100KB) * 100} KB"   // 100 KB ~ 1000 KB
    size >= BYTES_10KB -> "${(size / BYTES_10KB) * 10} KB"      // 10 KB ~ 90 KB
    else -> "10 KB 以下"
}
