package com.xjw.qalbum.ui.video

import android.os.ParcelFileDescriptor
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map as pagingMap
import com.xjw.qalbum.data.db.AppDatabase
import com.xjw.qalbum.data.db.VideoSql
import com.xjw.qalbum.data.db.dao.VideoDao
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.data.db.entity.VideoEntity
import com.xjw.qalbum.data.paging.RefreshGate
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.data.repository.ImageRepository
import com.xjw.qalbum.domain.model.SourceSort
import com.xjw.qalbum.domain.model.TimelineSection
import com.xjw.qalbum.service.demo.DemoMode
import com.xjw.qalbum.service.video.VideoFiles
import com.xjw.qalbum.service.video.VideoLibrary
import com.xjw.qalbum.service.work.BackgroundTasks
import com.xjw.qalbum.ui.cleanup.CleanupOpState
import com.xjw.qalbum.ui.cleanup.PurgeProgress
import com.xjw.qalbum.ui.cleanup.launchCleanupOp
import com.xjw.qalbum.ui.common.formatBytes
import com.xjw.qalbum.ui.common.formatCountExact
import com.xjw.qalbum.ui.components.ScrollDirectory
import com.xjw.qalbum.ui.gallery.GalleryTimelineItem
import com.xjw.qalbum.ui.gallery.SizeBucketing
import com.xjw.qalbum.ui.gallery.dateDirectory
import com.xjw.qalbum.ui.gallery.sizeDirectory
import com.xjw.qalbum.ui.gallery.withDayHeaders
import com.xjw.qalbum.ui.gallery.withSizeBuckets
import com.xjw.qalbum.ui.imageloading.VideoFrameModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.Locale

/** 视频页的筛选：全部、聊天里的视频、收到的文件里的视频 */
enum class VideoFilter(val label: String, val source: String?) {
    ALL("全部", null),
    CHAT("聊天视频", VideoEntity.SOURCE_CHAT),
    FILE("文件", VideoEntity.SOURCE_FILE),
}

/** 视频按大小分组（与 VideoSql.sizeKey 同一套） */
internal object VideoSizeBuckets : SizeBucketing {
    override fun bucketOf(size: Long): Long = VideoSql.sizeBucket(size)
    override fun labelOf(bucket: Long): String = VideoSql.sizeLabel(bucket)
    override fun sectionOf(bucket: Long): TimelineSection.Size = VideoSql.sizeSection(bucket)
}

/**
 * 视频页：QQ 聊天里的视频与收到的视频文件。网格与图库一致（日期 / 大小分组、长按拖选、点标题选整组、捏合改列数），
 * 进入时扫描一遍，之后在后台补上时长；删除是永久删除，跑在 App 级作用域（进度与结果由清理页根部显示）。
 */
class VideoViewModel(
    private val db: AppDatabase,
    private val library: VideoLibrary,
    private val settings: SettingsRepository,
    private val tasks: BackgroundTasks,
    private val appScope: CoroutineScope,
    private val demo: DemoMode,
) : ViewModel() {
    private val dao = db.videoDao()

    val sort: StateFlow<SourceSort> = settings.videoSort
        .stateIn(viewModelScope, SharingStarted.Eagerly, SourceSort.DATE_DESC)

    private val _filter = MutableStateFlow(VideoFilter.ALL)
    val filter: StateFlow<VideoFilter> = _filter.asStateFlow()

    val columns: StateFlow<Int> = settings.galleryColumns
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.DEFAULT_GALLERY_COLUMNS)

    val fastScroll: StateFlow<Boolean> = settings.fastScrollEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val exportSubdirs: StateFlow<List<String>> = settings.exportSubdirs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 包含收到的文件（QQfile_recv，多是手动下载的群文件），默认不包含 */
    val includeFiles: StateFlow<Boolean> = settings.videoIncludeFiles
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** 页面上显示的各来源的个数与大小（不包含收到的文件时只有聊天视频） */
    val sources: StateFlow<List<VideoDao.SourceStats>?> = combine(dao.observeSources(), settings.videoIncludeFiles) { rows, include ->
        rows.filter { include || it.source == VideoEntity.SOURCE_CHAT }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    /** 文件访问没就绪（没连 Root / Shizuku） */
    private val _noAccess = MutableStateFlow(false)
    val noAccess: StateFlow<Boolean> = _noAccess.asStateFlow()

    val busy: StateFlow<Boolean> = CleanupOpState.busy.asStateFlow()

    init {
        refresh()
        // 筛选的那类视频删光了（筛选条随之隐藏）就回到全部
        viewModelScope.launch {
            dao.observeSources().collect { rows ->
                val source = _filter.value.source
                if (source != null && rows.none { it.source == source && it.count > 0 }) _filter.value = VideoFilter.ALL
            }
        }
    }

    /** 扫描进行中又要求扫描（比如打开了「包含收到的文件」）：这一遍结束后再扫一遍 */
    @Volatile
    private var rescan = false

    /** 扫描一遍，再在后台补上还没读过的时长与尺寸 */
    fun refresh() {
        if (_scanning.value) {
            rescan = true
            return
        }
        _scanning.value = true
        viewModelScope.launch {
            try {
                library.refresh(settings.videoIncludeFiles.first()) { report ->
                    _noAccess.value = report.noAccess
                    _scanning.value = false
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "扫描视频失败")
                if (_scanning.value) CleanupOpState.message.value = "扫描视频失败：${e.message ?: e.javaClass.simpleName}"
            } finally {
                _scanning.value = false
            }
            if (rescan) {
                rescan = false
                refresh()
            }
        }
    }

    fun setFilter(next: VideoFilter) {
        _filter.value = next
    }

    /** 打开时扫一遍收到的文件；关掉后那些视频不再显示 */
    fun setIncludeFiles(include: Boolean) {
        _filter.value = VideoFilter.ALL
        viewModelScope.launch {
            settings.setVideoIncludeFiles(include)
            if (include) refresh()
        }
    }

    fun setSort(next: SourceSort) {
        appScope.launch { settings.setVideoSort(next) }
    }

    fun setColumns(next: Int) {
        viewModelScope.launch { settings.setGalleryColumns(next) }
    }

    fun addExportSubdir(name: String) = settings.addExportSubdir(name)

    private data class Key(val sort: SourceSort, val source: String?)

    private val key = combine(settings.videoSort, _filter, settings.videoIncludeFiles) { order, filter, include ->
        Key(order, if (include) filter.source else VideoEntity.SOURCE_CHAT)
    }
        .distinctUntilChanged()
        .shareIn(viewModelScope, SharingStarted.Eagerly, replay = 1)

    /** 删除期间关上：网格保持原样，删完统一刷新 */
    private val refreshGate = RefreshGate()

    private class Groups(val counts: StateFlow<Map<Long, Int>?>, val sort: SourceSort)

    private val groups = MutableStateFlow<Groups?>(null)

    val videos: Flow<PagingData<GalleryTimelineItem>> = key
        .flatMapLatest { k ->
            val buckets = VideoSql.buckets(k.sort.byDate, k.source)
            groups.value = Groups(
                dao.observeBuckets(buckets).map { rows -> rows.associate { it.bucketKey to it.count } }
                    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null),
                k.sort,
            )
            val counts = flow { emit(dao.buckets(buckets).associate { it.bucketKey to it.count }) }
            val pages: Flow<PagingData<GalleryTimelineItem.Photo>> =
                Pager(PAGING, pagingSourceFactory = refreshGate.gated { dao.paging(VideoSql.page(k.sort, k.source)) })
                    .flow.map { data -> data.pagingMap { video -> GalleryTimelineItem.Photo(video.asImage(), video) } }
            if (k.sort.byDate) pages.withDayHeaders(counts) { it.image.mtime }
            else pages.withSizeBuckets(counts, VideoSizeBuckets) { it.image.size }
        }
        .cachedIn(viewModelScope)

    val directory: StateFlow<ScrollDirectory?> = groups
        .flatMapLatest { source ->
            source?.counts?.map { counts ->
                counts?.let {
                    if (source.sort.byDate) dateDirectory(it, ascending = !source.sort.descending)
                    else sizeDirectory(it, ascending = !source.sort.descending, buckets = VideoSizeBuckets)
                }
            } ?: flowOf(null)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 点分组标题全选：这一天 / 这个大小区间的全部 */
    suspend fun sectionIds(section: TimelineSection): List<Long> = dao.ids(VideoSql.ids(key.first().source, section))

    /** 右上角全选：当前筛选下的全部 */
    suspend fun allIds(): Set<Long> = dao.ids(VideoSql.ids(key.first().source)).toSet()

    /** 选中视频的个数与大小（确认删除时显示） */
    suspend fun selectionBytes(ids: Collection<Long>): Long =
        ids.distinct().chunked(SQL_CHUNK).flatMap { dao.byIds(it) }.sumOf { it.size }

    fun tileModel(photo: GalleryTimelineItem.Photo): Any = (photo.tag as? VideoEntity)?.frameModel() ?: VideoFrameModel(photo.image.path, photo.image.size, photo.image.mtime, photo.image.width, photo.image.height)

    /** 播放：只读打开视频 */
    suspend fun open(video: VideoEntity): ParcelFileDescriptor? = library.open(video.path)

    // ---- 操作 ----

    /** 永久删除；没有执行（演示模式）时返回 false */
    fun delete(ids: Collection<Long>, onDone: () -> Unit = {}): Boolean {
        if (ids.isEmpty() || demo.blocks()) return false
        val label = "删除视频"
        appScope.launchCleanupOp(label, demo) {
            val total = ids.size
            CleanupOpState.purgeProgress.value = PurgeProgress(0, total, "正在删除视频…")
            val report = try {
                tasks.track(label, cancellable = false) { handle ->
                    refreshGate.hold {
                        library.delete(ids) { done, all ->
                            CleanupOpState.purgeProgress.value = PurgeProgress(done, all, "正在删除视频…")
                            handle.update("已处理 $done / $all 个", done.toLong(), all.toLong())
                        }
                    }
                }
            } finally {
                CleanupOpState.purgeProgress.value = null
            }
            CleanupOpState.message.value = when {
                report.noAccess -> "文件访问未授权，先在「设置 → 文件访问」里授权"
                report.failed > 0 -> "已删除 ${report.deleted} 个视频（${formatBytes(report.bytes)}），${report.failed} 个没删掉：文件已变化或无法访问"
                else -> "已删除 ${report.deleted + report.gone} 个视频（${formatBytes(report.bytes)}）"
            }
            withContext(Dispatchers.Main) { onDone() }
        }
        return true
    }

    /** 保存到相册 */
    fun export(id: Long, subdir: String?) {
        appScope.launchCleanupOp("保存视频", demo) {
            CleanupOpState.message.value = if (library.export(id, subdir)) {
                "已保存到相册" + (subdir?.let { "「$it」" } ?: "")
            } else {
                "保存失败"
            }
        }
    }

    companion object {
        private const val SQL_CHUNK = 500
        private val PAGING = PagingConfig(
            pageSize = 60, prefetchDistance = 120, initialLoadSize = 180,
            enablePlaceholders = true, jumpThreshold = ImageRepository.JUMP_THRESHOLD,
        )

        /** 各来源的统计合成筛选条上的文案 */
        fun filterLabel(filter: VideoFilter, sources: List<VideoDao.SourceStats>): String {
            val rows = if (filter.source == null) sources else sources.filter { it.source == filter.source }
            return "${filter.label} · ${formatCountExact(rows.sumOf { it.count })}"
        }
    }
}

/** 网格与看图要的图片行：视频没有图片表里的那些状态，只借用文件信息 */
internal fun VideoEntity.asImage(): ImageEntity = ImageEntity(
    id = id,
    path = path,
    filename = filename,
    parentDir = path.substringBeforeLast('/'),
    sourceType = "video",
    cacheKey = "",
    size = size,
    mtime = mtime,
    dateBucket = dateBucket,
    firstSeen = seenAt,
    lastSeen = seenAt,
    mime = VideoFiles.mimeOf(path),
    width = width,
    height = height,
)

internal fun VideoEntity.frameModel(): VideoFrameModel = VideoFrameModel(path, size, mtime, width, height)

/** 时长：1:05、1:02:03；还没读出来为 null */
internal fun formatDuration(ms: Long): String? {
    if (ms <= 0) return null
    val total = (ms + 500) / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
}
