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
import com.xjw.qalbum.service.cleanup.PictureDeleteOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map as flowMap
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private val appScope: CoroutineScope,
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

    // 分桶计数固化为热流缓存：withDateHeaders/withSizeHeaders 每个 PagingData generation
    // 都要读一次计数，若每次冷订阅 Room Flow，扫描/分析期间的高频失效会让 200 万行聚合
    // 反复重跑并拖住新 generation 的下发；stateIn 后 generation 读的是最新缓存值（首次
    // 之后即时返回），刷新频率由仓库层 throttleLatest 统一约束。
    private val timelineDateBucketCounts: StateFlow<Map<Long, Int>?> =
        imageRepository.observeTimelineDateBucketCounts()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val timelineSizeBucketCounts: StateFlow<Map<Long, Int>?> =
        imageRepository.observeTimelineSizeBucketCounts()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val favoriteDateBucketCounts: StateFlow<Map<Long, Int>?> =
        imageRepository.observeFavoriteDateBucketCounts()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val timeline: Flow<PagingData<GalleryTimelineItem>> =
        settings.gallerySort
            // DataStore 任意 key 写入都会重发 gallerySort；等值重发若不去重会经 flatMapLatest
            // 重建 Pager（滚动位置回顶 + 全量重载），改列数/阈值等无关设置都会触发
            .distinctUntilChanged()
            .flatMapLatest { sort ->
                when (sort) {
                    GallerySort.SIZE_DESC -> imageRepository
                        .timelinePager(sort)
                        .withSizeHeaders(timelineSizeBucketCounts)

                    GallerySort.DATE_DESC, GallerySort.DATE_ASC -> imageRepository
                        .timelinePager(sort)
                        .withDateHeaders(timelineDateBucketCounts)
                }
            }.cachedIn(viewModelScope)

    val favoriteTimeline: Flow<PagingData<GalleryTimelineItem>> =
        imageRepository
            .favoriteTimelinePager()
            .withDateHeaders(favoriteDateBucketCounts)
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
     * 多选「保存」：导出到 Pictures/QAlbum[/子目录]；[alsoTrash] 为真则**仅对导出成功的图**
     * 移入回收站——导出失败的原图绝不进回收站（否则到期自动清理会把从未保存成功的图永久删掉）。
     * 回调返回成功/失败张数，供 UI 分别告知。
     */
    fun saveImages(ids: Set<Long>, subdir: String?, alsoTrash: Boolean, onDone: (saved: Int, failed: Int) -> Unit = { _, _ -> }) {
        appScope.launch {
            val savedIds = ids.filter { imageFileManager.exportToGallery(it, subdir) }
            if (alsoTrash && savedIds.isNotEmpty()) {
                quarantineManager.quarantineBatch(savedIds.map { it to "保存后移入回收站" })
            }
            withContext(Dispatchers.Main) { onDone(savedIds.size, ids.size - savedIds.size) }
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

    // 批量「文件系统 + DB」不可逆操作跑在 App 级 appScope：分钟级批量若挂在 viewModelScope，
    // 用户确认后返回上一页（压栈路由弹出）会把协程连同 QuarantineManager 的滚动推进一起取消，
    // 留下「文件已移动但 DB 未写」的孤儿块；appScope 随进程存活，完成后回主线程通知 UI。

    fun moveImagesToTrash(ids: Set<Long>, options: PictureDeleteOptions = PictureDeleteOptions(), onDone: (Int) -> Unit = {}) {
        appScope.launch {
            val count = quarantineManager.quarantinePictures(ids,options,"用户批量删除").selectedProcessed
            withContext(Dispatchers.Main) { onDone(count) }
        }
    }

    fun deleteImagesPermanently(ids: Set<Long>, options: PictureDeleteOptions = PictureDeleteOptions(), onDone: (Int) -> Unit = {}) {
        appScope.launch {
            val count = quarantineManager.deletePicturesPermanently(ids,options).selectedProcessed
            withContext(Dispatchers.Main) { onDone(count) }
        }
    }

    private suspend fun setFavoriteState(ids: Set<Long>, favorite: Boolean): Int {
        val targetIds = imageRepository.getByIds(ids.toList())
            .filter { it.isFavorite != favorite }
            .map { it.id }
        imageRepository.setFavoriteBatch(targetIds, favorite)
        return targetIds.size
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
        ((image.blockHash != null && image.featureGrid != null) || image.isCorrupt) &&
        (!phases.ocr || image.ocrDone || image.isCorrupt || image.width < 64 || image.height < 64) &&
        (!phases.aiTag || image.aiDone || image.isCorrupt) &&
        (!phases.embedding || image.embeddingDone || image.isCorrupt)

/**
 * 参数接受可空 map 流：调用方可传 stateIn(初值 null) 固化的热流，首个 generation 等到
 * 首次查询结果后，后续 generation 即时拿缓存值，不再逐 generation 冷跑全表聚合。
 * （Flow 协变，直接传非空 map 流也兼容。）
 */
internal fun Flow<PagingData<ImageEntity>>.withDateHeaders(
    bucketCounts: Flow<Map<Long, Int>?>,
): Flow<PagingData<GalleryTimelineItem>> =
    flowMap { pagingData: PagingData<ImageEntity> ->
        val counts = bucketCounts.filterNotNull().first()
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

internal fun Flow<PagingData<ImageEntity>>.withSizeHeaders(
    bucketCounts: Flow<Map<Long, Int>?>,
): Flow<PagingData<GalleryTimelineItem>> =
    flowMap { pagingData: PagingData<ImageEntity> ->
        val counts = bucketCounts.filterNotNull().first()
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
