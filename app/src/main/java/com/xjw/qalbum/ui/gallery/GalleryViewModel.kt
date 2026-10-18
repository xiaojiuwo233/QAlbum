package com.xjw.qalbum.ui.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.insertSeparators
import androidx.paging.map as pagingMap
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.data.paging.RefreshGate
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.data.repository.ImageRepository
import com.xjw.qalbum.domain.model.GallerySort
import com.xjw.qalbum.domain.model.TimelineSection
import com.xjw.qalbum.service.cleanup.ArchiveManager
import com.xjw.qalbum.ui.components.ScrollDirectory
import com.xjw.qalbum.service.cleanup.ImageFileManager
import com.xjw.qalbum.service.cleanup.QuarantineManager
import com.xjw.qalbum.ui.cleanup.PurgeProgress
import com.xjw.qalbum.service.cleanup.PictureDeleteOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
import com.xjw.qalbum.service.work.BackgroundTasks

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
    private val archive: ArchiveManager,
    private val settings: SettingsRepository,
    private val appScope: CoroutineScope,
    private val tasks: BackgroundTasks,
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

    /** 合并同一张 QQ 图片的原图 / 预览图 / 缩略图：只显示最大的那张，左下角标出版本数（开关在设置里） */
    val stackVariants: StateFlow<Boolean> = settings.galleryStackVariants
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** 快速滚动条（开关在设置里） */
    val fastScroll: StateFlow<Boolean> = settings.fastScrollEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    // ---- 时间线 ----

    /** 批量删除期间关上：网格保持原样，删完统一刷新一次（不逐批重排、不跳位置） */
    private val refreshGate = RefreshGate()

    // 分桶计数固化为热流缓存：withDateHeaders/withSizeHeaders 每个 PagingData generation
    // 都要读一次计数，若每次冷订阅 Room Flow，扫描/分析期间的高频失效会让 200 万行聚合
    // 反复重跑并拖住新 generation 的下发；stateIn 后 generation 读的是最新缓存值（首次
    // 之后即时返回），刷新频率由仓库层 throttleLatest 统一约束。按是否合并显示各备一份。
    private val timelineDateBucketCounts: Map<Boolean, StateFlow<Map<Long, Int>?>> =
        listOf(false, true).associateWith { stacked ->
            imageRepository.observeTimelineDateBucketCounts(stacked)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
        }

    private val timelineSizeBucketCounts: Map<Boolean, StateFlow<Map<Long, Int>?>> =
        listOf(false, true).associateWith { stacked ->
            imageRepository.observeTimelineSizeBucketCounts(stacked)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
        }

    private val sortAndStack = combine(settings.gallerySort, settings.galleryStackVariants) { sort, stacked -> sort to stacked }
        // DataStore 任意 key 写入都会重发；等值重发若不去重会经 flatMapLatest
        // 重建 Pager（滚动位置回顶 + 全量重载），改列数/阈值等无关设置都会触发
        .distinctUntilChanged()

    /** 归档页查询用的状态：随「归档的图片也在图库中显示」变化（见 ArchiveManager.statusesOf） */
    private val archiveStatuses = archive.pageStatuses.distinctUntilChanged()

    private val archiveDateBucketCounts: StateFlow<Map<Long, Int>?> =
        archiveStatuses.flatMapLatest { imageRepository.observeArchiveDateBucketCounts(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val timeline: Flow<PagingData<GalleryTimelineItem>> =
        sortAndStack
            .flatMapLatest { (sort, stacked) ->
                when (sort) {
                    // 合并显示时按整组合计排序，分组也按它（与排序一致，标题才不会重复出现）
                    GallerySort.SIZE_DESC -> imageRepository
                        .timelinePager(sort, stacked, refreshGate)
                        .withSizeHeaders(timelineSizeBucketCounts.getValue(stacked), if (stacked) StackedSize else FileSize)

                    GallerySort.DATE_DESC, GallerySort.DATE_ASC -> imageRepository
                        .timelinePager(sort, stacked, refreshGate)
                        .withDateHeaders(timelineDateBucketCounts.getValue(stacked))
                }
            }.cachedIn(viewModelScope)

    /** 快速滚动条的目录：按当前排序的日期 / 大小分组 */
    val timelineDirectory: StateFlow<ScrollDirectory?> =
        sortAndStack
            .flatMapLatest { (sort, stacked) ->
                when (sort) {
                    GallerySort.SIZE_DESC -> timelineSizeBucketCounts.getValue(stacked).flowMap { counts -> counts?.let(::sizeDirectory) }
                    GallerySort.DATE_DESC, GallerySort.DATE_ASC -> timelineDateBucketCounts.getValue(stacked)
                        .flowMap { counts -> counts?.let { dateDirectory(it, ascending = sort == GallerySort.DATE_ASC) } }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 点分组标题全选：这一组（某天 / 某个大小区间）的全部图片，合并显示时只取代表图 */
    suspend fun sectionIds(section: TimelineSection): List<Long> {
        val stacked = settings.galleryStackVariants.first()
        return when (section) {
            is TimelineSection.Day -> imageRepository.timelineIdsBetween(section.from, section.until, stacked)
            is TimelineSection.Size -> imageRepository.timelineIdsBySize(section.low, section.high, stacked)
        }
    }

    // ---- 归档页 ----

    suspend fun archiveSectionIds(section: TimelineSection): List<Long> = when (section) {
        is TimelineSection.Day -> imageRepository.archiveIdsBetween(archive.pageStatuses.first(), section.from, section.until)
        is TimelineSection.Size -> emptyList()
    }

    val archiveDirectory: StateFlow<ScrollDirectory?> = archiveDateBucketCounts
        .flowMap { counts -> counts?.let { dateDirectory(it, ascending = false) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val archiveTimeline: Flow<PagingData<GalleryTimelineItem>> =
        archiveStatuses
            .flatMapLatest { statuses -> imageRepository.archiveTimelinePager(statuses, refreshGate).withDateHeaders(archiveDateBucketCounts) }
            .cachedIn(viewModelScope)

    /** 归档的图片也在图库中显示（关闭时只在归档页出现） */
    val archiveVisible: StateFlow<Boolean> = settings.archiveVisible
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

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
    fun saveImages(
        ids: Set<Long>, subdir: String?, alsoTrash: Boolean, includeArchived: Boolean = false,
        onDone: (saved: Int, failed: Int) -> Unit = { _, _ -> },
    ) {
        appScope.launch {
            val savedIds = ids.filter { imageFileManager.exportToGallery(it, subdir) }
            if (alsoTrash && savedIds.isNotEmpty()) {
                refreshGate.hold {
                    quarantineManager.quarantineBatch(
                        savedIds.map { it to "保存后移入回收站" },
                        options = PictureDeleteOptions(includeArchived = includeArchived),
                    )
                }
            }
            withContext(Dispatchers.Main) { onDone(savedIds.size, ids.size - savedIds.size) }
        }
    }

    /** 归档（连同同一张图的其他版本）；回调处理的张数 */
    fun archiveImages(ids: Set<Long>, onDone: (Int) -> Unit = {}) {
        viewModelScope.launch { onDone(archive.archive(ids)) }
    }

    fun unarchiveImages(ids: Set<Long>, onDone: (Int) -> Unit = {}) {
        viewModelScope.launch { onDone(archive.unarchive(ids)) }
    }

    // 批量「文件系统 + DB」不可逆操作跑在 App 级 appScope：分钟级批量若挂在 viewModelScope，
    // 用户确认后返回上一页（压栈路由弹出）会把协程连同 QuarantineManager 的滚动推进一起取消，
    // 留下「文件已移动但 DB 未写」的孤儿块；appScope 随进程存活，完成后回主线程通知 UI。

    /** 批量删除的进度（删除期间网格保持不动，靠它告诉用户还在进行） */
    private val _deleteProgress = MutableStateFlow<PurgeProgress?>(null)
    val deleteProgress: StateFlow<PurgeProgress?> = _deleteProgress.asStateFlow()

    fun moveImagesToTrash(ids: Set<Long>, options: PictureDeleteOptions = PictureDeleteOptions(), onDone: (Int) -> Unit = {}) {
        appScope.launch {
            val count = withDeleteProgress("正在移入回收站…") { progress ->
                quarantineManager.quarantinePictures(ids, options, "用户批量删除", onProgress = progress)
            }.selectedProcessed
            withContext(Dispatchers.Main) { onDone(count) }
        }
    }

    fun deleteImagesPermanently(ids: Set<Long>, options: PictureDeleteOptions = PictureDeleteOptions(), onDone: (Int) -> Unit = {}) {
        appScope.launch {
            val count = withDeleteProgress("正在永久删除…") { progress ->
                quarantineManager.deletePicturesPermanently(ids, options, onProgress = progress)
            }.selectedProcessed
            withContext(Dispatchers.Main) { onDone(count) }
        }
    }

    /** 批量操作：页面上显示进度对话框，同时登记为后台任务（通知栏进度，切到后台不中断） */
    private suspend fun <T> withDeleteProgress(label: String, block: suspend (onProgress: (Int, Int) -> Unit) -> T): T =
        tasks.track(label.removePrefix("正在").removeSuffix("…"), cancellable = false) { handle ->
            try {
                refreshGate.hold {
                    block { done, total ->
                        _deleteProgress.value = PurgeProgress(done, total, label)
                        handle.update("已处理 $done / $total 张", done.toLong(), total.toLong())
                    }
                }
            } finally {
                _deleteProgress.value = null
            }
        }
}

sealed class GalleryTimelineItem {
    /** 分类标题：[key] 唯一且稳定（供 Paging/LazyGrid 复用判定），[label] 为展示文案，[section] 为它覆盖的范围 */
    data class Header(val key: String, val label: String, val section: TimelineSection) : GalleryTimelineItem()

    /** [tag] 由建列表的页面附带（如回收站里这组图的信息），网格本身不读它 */
    data class Photo(val image: ImageEntity, val tag: Any? = null) : GalleryTimelineItem()
}

/** 按大小分组时取哪个大小：文件自身，或合并显示时的整组合计 */
internal val FileSize: (ImageEntity) -> Long = { it.size }
internal val StackedSize: (ImageEntity) -> Long = { it.variantBytes }

/**
 * 参数接受可空 map 流：调用方可传 stateIn(初值 null) 固化的热流，首个 generation 等到
 * 首次查询结果后，后续 generation 即时拿缓存值，不再逐 generation 冷跑全表聚合。
 * （Flow 协变，直接传非空 map 流也兼容。）
 */
internal fun Flow<PagingData<ImageEntity>>.withDateHeaders(
    bucketCounts: Flow<Map<Long, Int>?>,
): Flow<PagingData<GalleryTimelineItem>> =
    flowMap { data -> data.pagingMap { GalleryTimelineItem.Photo(it) } }.withDayHeaders(bucketCounts) { it.image.mtime }

internal fun Flow<PagingData<ImageEntity>>.withSizeHeaders(
    bucketCounts: Flow<Map<Long, Int>?>,
    sizeOf: (ImageEntity) -> Long = FileSize,
): Flow<PagingData<GalleryTimelineItem>> =
    flowMap { data -> data.pagingMap { GalleryTimelineItem.Photo(it) } }.withSizeBuckets(bucketCounts) { sizeOf(it.image) }

/** 按 [timeOf] 所在的本地日期插入分组标题（带当天张数，[note] 可在后面附一句，如回收站的到期天数） */
internal fun Flow<PagingData<GalleryTimelineItem.Photo>>.withDayHeaders(
    bucketCounts: Flow<Map<Long, Int>?>,
    note: (GalleryTimelineItem.Photo) -> String? = { null },
    timeOf: (GalleryTimelineItem.Photo) -> Long,
): Flow<PagingData<GalleryTimelineItem>> =
    flowMap { pagingData: PagingData<GalleryTimelineItem.Photo> ->
        val counts = HeaderCounts(bucketCounts)
        pagingData.insertSeparators<GalleryTimelineItem.Photo, GalleryTimelineItem> { before, after ->
            if (after == null) return@insertSeparators null
            val time = timeOf(after)
            val key = dayKey(time)
            if (before != null && dayKey(timeOf(before)) == key) return@insertSeparators null
            val label = headerLabel(dayLabel(time), counts.get()[key])
            GalleryTimelineItem.Header(
                key = "d-$key",
                // 全角括号自带留白，后面的「·」不再空格
                label = label + note(after)?.let { if (label.endsWith("）")) "· $it" else " · $it" }.orEmpty(),
                section = daySection(time),
            )
        }
    }

/** 按 [sizeOf] 的大小区间插入分组标题（分组见 [buckets]，默认与 [sizeBucketId] 同一套） */
internal fun Flow<PagingData<GalleryTimelineItem.Photo>>.withSizeBuckets(
    bucketCounts: Flow<Map<Long, Int>?>,
    buckets: SizeBucketing = ImageSizeBuckets,
    sizeOf: (GalleryTimelineItem.Photo) -> Long,
): Flow<PagingData<GalleryTimelineItem>> =
    flowMap { pagingData: PagingData<GalleryTimelineItem.Photo> ->
        val counts = HeaderCounts(bucketCounts)
        pagingData.insertSeparators<GalleryTimelineItem.Photo, GalleryTimelineItem> { before, after ->
            if (after == null) return@insertSeparators null
            val key = buckets.bucketOf(sizeOf(after))
            if (before != null && buckets.bucketOf(sizeOf(before)) == key) return@insertSeparators null
            GalleryTimelineItem.Header(key = "s-$key", label = headerLabel(buckets.labelOf(key), counts.get()[key]), section = buckets.sectionOf(key))
        }
    }

/** 按大小分组的规则：图片用 [ImageSizeBuckets]；视频大多在 10 MB 以上，另有一套（见视频页） */
internal interface SizeBucketing {
    fun bucketOf(size: Long): Long
    fun labelOf(bucket: Long): String
    fun sectionOf(bucket: Long): TimelineSection.Size
}

internal object ImageSizeBuckets : SizeBucketing {
    override fun bucketOf(size: Long): Long = sizeBucketId(size)
    override fun labelOf(bucket: Long): String = sizeBucketLabel(sizeBucketSection(bucket).low)
    override fun sectionOf(bucket: Long): TimelineSection.Size = sizeBucketSection(bucket)
}

/**
 * 一代分页数据的分组计数：插第一个标题时才取。批量操作期间网格关着闸门（见 RefreshGate），新一代要等闸门打开、
 * 数据真正加载时才插标题，这时再取计数，标题上的张数才与网格一致。
 */
private class HeaderCounts(private val source: Flow<Map<Long, Int>?>) {
    private var counts: Map<Long, Int>? = null

    suspend fun get(): Map<Long, Int> = counts ?: source.filterNotNull().first().also { counts = it }
}

private fun headerLabel(label: String, count: Int?): String =
    if (count == null) label else "$label（$count）"

// ---- 日期分桶 ----

internal fun dayKey(timestampMs: Long): Long {
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

/** 时间戳所在的本地自然日 [0 点, 次日 0 点) */
internal fun daySection(timestampMs: Long): TimelineSection.Day {
    val start = Calendar.getInstance().apply {
        timeInMillis = timestampMs
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val from = start.timeInMillis
    start.add(Calendar.DAY_OF_YEAR, 1)
    return TimelineSection.Day(from, start.timeInMillis)
}

/**
 * 快速滚动条的日期目录：按显示顺序列出每天的数量，气泡显示具体日期，落点对齐到当天的标题；
 * 刻度跨年时标年份，只有一年时标月份。[counts] 的键与 date_bucket 相同（year*1000 + dayOfYear）。
 */
internal fun dateDirectory(counts: Map<Long, Int>, ascending: Boolean): ScrollDirectory {
    val keys = counts.keys.sorted().let { if (ascending) it else it.reversed() }
    val severalYears = keys.map { it / 1000 }.distinct().size > 1
    val calendar = Calendar.getInstance()
    var previousMark: String? = null
    val entries = keys.map { key ->
        val year = (key / 1000).toInt()
        calendar.clear()
        calendar.set(Calendar.YEAR, year)
        calendar.set(Calendar.DAY_OF_YEAR, (key % 1000).toInt())
        val month = calendar.get(Calendar.MONTH) + 1
        val day = calendar.get(Calendar.DAY_OF_MONTH)
        val mark = if (severalYears) "$year" else "${month}月"
        val entry = ScrollDirectory.Entry(counts.getValue(key), "${year}年${month}月${day}日", mark.takeIf { it != previousMark }, key = "d-$key")
        previousMark = mark
        entry
    }
    return ScrollDirectory(entries)
}

/** 快速滚动条的大小目录：按显示顺序（默认大到小），MB 级的组和「1 MB 以下」作为刻度 */
internal fun sizeDirectory(counts: Map<Long, Int>, ascending: Boolean = false, buckets: SizeBucketing = ImageSizeBuckets): ScrollDirectory {
    var belowMegabyte = false
    val entries = counts.keys.sortedBy { buckets.sectionOf(it).low }.let { if (ascending) it else it.reversed() }.map { bucket ->
        val section = buckets.sectionOf(bucket)
        val mark = when {
            section.low >= BYTES_MB -> buckets.labelOf(bucket)
            !belowMegabyte -> "1 MB 以下".also { belowMegabyte = true }
            else -> null
        }
        ScrollDirectory.Entry(counts.getValue(bucket), buckets.labelOf(bucket), mark, key = "s-$bucket")
    }
    return ScrollDirectory(entries)
}

// ---- 大小分桶（大到小排序时使用）----
// ≥10MB 合并为一桶；1~9MB 每 1MB；100KB~1MB 每 100KB；10KB~100KB 每 10KB；<10KB 合并为一桶。
// id 用互不重叠的数值区间，保证相邻桶判定唯一。

private const val BYTES_MB = 1024L * 1024L
private const val BYTES_100KB = 100L * 1024L
private const val BYTES_10KB = 10L * 1024L

internal fun sizeBucketId(size: Long): Long = when {
    size >= 10 * BYTES_MB -> 100000L                       // 10MB 以上
    size >= BYTES_MB -> 10000L + (size / BYTES_MB)         // 1~9MB → 10001..10009
    size >= BYTES_100KB -> 1000L + (size / BYTES_100KB)    // 100KB~1MB → 1001..1010
    size >= BYTES_10KB -> 100L + (size / BYTES_10KB)       // 10KB~100KB → 101..109
    else -> 0L                                             // <10KB
}

/** 大小分组的区间 [low, high)，与 [sizeBucketId] 一一对应 */
internal fun sizeBucketSection(bucket: Long): TimelineSection.Size = when {
    bucket >= 100000L -> TimelineSection.Size(10 * BYTES_MB, Long.MAX_VALUE)
    bucket >= 10000L -> (bucket - 10000L).let { TimelineSection.Size(it * BYTES_MB, (it + 1) * BYTES_MB) }
    bucket >= 1000L -> (bucket - 1000L).let { TimelineSection.Size(it * BYTES_100KB, minOf((it + 1) * BYTES_100KB, BYTES_MB)) }
    bucket >= 100L -> (bucket - 100L).let { TimelineSection.Size(it * BYTES_10KB, (it + 1) * BYTES_10KB) }
    else -> TimelineSection.Size(0, BYTES_10KB)
}

internal fun sizeBucketLabel(size: Long): String = when {
    size >= 10 * BYTES_MB -> "10 MB 以上"
    size >= BYTES_MB -> "${size / BYTES_MB} MB"
    size >= BYTES_100KB -> "${(size / BYTES_100KB) * 100} KB"   // 100 KB ~ 1000 KB
    size >= BYTES_10KB -> "${(size / BYTES_10KB) * 10} KB"      // 10 KB ~ 90 KB
    else -> "10 KB 以下"
}
