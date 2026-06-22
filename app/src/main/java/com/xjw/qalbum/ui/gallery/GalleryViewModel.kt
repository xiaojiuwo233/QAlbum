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
import kotlinx.coroutines.flow.combine
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

    /**
     * 当前「会被分析」的阶段集合：受每项开关 + 全局 AI 服务可用性约束。
     * 图库完成角标据此判断「已启用的项是否全部完成」。
     */
    val enabledPhases: StateFlow<EnabledPhases> =
        combine(settings.analysisConfig, settings.aiEnabled, settings.aiEndpoint) { config, aiEnabled, endpoint ->
            val aiReady = aiEnabled && endpoint.isNotBlank()
            EnabledPhases(
                ocr = config.ocrEnabled,
                aiTag = config.aiTagEnabled && aiReady,
                embedding = config.embeddingEnabled && aiReady,
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            EnabledPhases(ocr = true, aiTag = false, embedding = false),
        )

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

    /** 选中图片的原始字节总和（用于删除确认框展示「N 张图片 · X MB」） */
    suspend fun selectedSize(ids: Set<Long>): Long = imageRepository.sumSizeByIds(ids.toList())

    /** 导出子目录列表（保存弹窗用） */
    val exportSubdirs: StateFlow<List<String>> = settings.exportSubdirs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 保存弹窗新建子目录（落盘走 App 级 appScope） */
    fun createSubdir(name: String) = settings.addExportSubdir(name)

    /**
     * 多选「保存」：导出到 Pictures/QAlbum[/子目录]；[alsoTrash] 为真则保存后把这些图一并移入回收站。
     * 回调返回成功保存的张数。
     */
    fun saveImages(ids: Set<Long>, subdir: String?, alsoTrash: Boolean, onDone: (Int) -> Unit = {}) {
        viewModelScope.launch {
            val saved = ids.count { imageFileManager.exportToGallery(it, subdir) }
            if (alsoTrash) {
                ids.forEach { quarantineManager.quarantine(it, "保存后移入回收站") }
            }
            onDone(saved)
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

/** 当前「会被分析」的阶段集合（hash 始终隐含开启，故不在此） */
data class EnabledPhases(
    val ocr: Boolean,
    val aiTag: Boolean,
    val embedding: Boolean,
)

/**
 * 图片是否「已完成全部需要的分析项」：hash 必做；OCR / AI 标签 / 向量 哪项未启用就不要求。
 * 对损坏 / 过小图宽容（这些图本就被对应阶段跳过，见 pendingOcr/AiTag/Embedding 筛选），
 * 否则它们永远拿不到角标、也会拖累清理页「已分析」进度到不了 100%。
 * 与 `ImageDao.observeFullyAnalyzedCount` 的 SQL 判定保持完全一致。
 */
fun isFullyAnalyzed(image: ImageEntity, phases: EnabledPhases): Boolean =
    image.hashDone &&
        (!phases.ocr || image.ocrDone || image.isCorrupt || image.width < 64 || image.height < 64) &&
        (!phases.aiTag || image.aiDone || image.isCorrupt) &&
        (!phases.embedding || image.embeddingDone || image.isCorrupt)

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
