package com.xjw.qalbum.ui.cleanup

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.xjw.qalbum.data.db.dao.ImageDao
import com.xjw.qalbum.data.db.entity.OcrTextEntity
import com.xjw.qalbum.data.paging.RefreshGate
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.data.repository.ImageRepository
import com.xjw.qalbum.domain.model.TimelineSection
import com.xjw.qalbum.service.cleanup.ExternalViewTarget
import com.xjw.qalbum.service.cleanup.ImageFileManager
import com.xjw.qalbum.service.cleanup.PictureDeleteOptions
import com.xjw.qalbum.service.cleanup.QuarantineManager
import com.xjw.qalbum.ui.components.ScrollDirectory
import com.xjw.qalbum.ui.gallery.GalleryTimelineItem
import com.xjw.qalbum.ui.gallery.dateDirectory
import com.xjw.qalbum.ui.gallery.withDateHeaders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 批量清理进度：已处理 / 总数 */
data class ThumbnailProgress(val done: Int, val total: Int)

/**
 * QQ 缩略图页：按日期分组浏览全部缩略图，可多选、全选或「选择 N 天前」后移入回收站。
 * 批量清理分块执行并汇报进度，跑在 App 级作用域，离开本页也不中断。
 */
class ThumbnailCacheViewModel(
    private val imageDao: ImageDao,
    private val images: ImageRepository,
    private val imageFiles: ImageFileManager,
    private val quarantine: QuarantineManager,
    private val settings: SettingsRepository,
    private val appScope: CoroutineScope,
) : ViewModel() {

    val summary: StateFlow<ImageDao.ThumbnailTrashSummary?> = imageDao.observeThumbnailTrashSummary()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 快速滚动条（开关在设置里） */
    val fastScroll: StateFlow<Boolean> = settings.fastScrollEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val columns: StateFlow<Int> = settings.galleryColumns
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.DEFAULT_GALLERY_COLUMNS)

    val exportSubdirs: StateFlow<List<String>> = settings.exportSubdirs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val bucketCounts: StateFlow<Map<Long, Int>?> = imageDao.observeThumbnailBuckets()
        .map { rows -> rows.associate { it.bucketKey to it.count } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 批量清理期间关上：网格保持原样，清理完统一刷新一次 */
    private val refreshGate = RefreshGate()

    val photos: Flow<PagingData<GalleryTimelineItem>> = Pager(
        // 占位：快速滚动条可直接拖到任意位置
        PagingConfig(
            pageSize = 60, prefetchDistance = 120, initialLoadSize = 180,
            enablePlaceholders = true, jumpThreshold = ImageRepository.JUMP_THRESHOLD,
        ),
        pagingSourceFactory = refreshGate.gated { imageDao.pagingThumbnailTrash() },
    ).flow
        .withDateHeaders(bucketCounts)
        .cachedIn(viewModelScope)

    /** 快速滚动条的目录（新到旧） */
    val directory: StateFlow<ScrollDirectory?> = bucketCounts
        .map { counts -> counts?.let { dateDirectory(it, ascending = false) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 点日期标题全选：这一天的全部缩略图 */
    suspend fun sectionIds(section: TimelineSection): List<Long> = when (section) {
        is TimelineSection.Day -> imageDao.thumbnailIdsBetween(section.from, section.until)
        is TimelineSection.Size -> emptyList()
    }

    private val _progress = MutableStateFlow<ThumbnailProgress?>(null)
    val progress: StateFlow<ThumbnailProgress?> = _progress.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun consumeMessage() {
        _message.value = null
    }

    fun setColumns(next: Int) {
        viewModelScope.launch { settings.setGalleryColumns(next) }
    }

    fun createSubdir(name: String) = settings.addExportSubdir(name)

    suspend fun allIds(): Set<Long> = imageDao.thumbnailIdsBefore(Long.MAX_VALUE).toSet()

    suspend fun idsOlderThan(days: Int): Set<Long> =
        imageDao.thumbnailIdsBefore(System.currentTimeMillis() - days * DAY_MILLIS).toSet()

    suspend fun ocr(id: Long): OcrTextEntity? = images.getOcr(id)

    fun share(id: Long, onReady: (ExternalViewTarget) -> Unit) {
        viewModelScope.launch { imageFiles.prepareShare(id)?.let(onReady) }
    }

    fun save(id: Long, subdir: String?, alsoTrash: Boolean, onDone: (Boolean) -> Unit) {
        appScope.launch {
            val ok = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q || imageFiles.canExportPreQ()) &&
                imageFiles.exportToGallery(id, subdir)
            val trashed = ok && alsoTrash && quarantine.quarantine(id, "保存后移入回收站")
            _message.value = when {
                !ok -> "保存失败"
                alsoTrash -> if (trashed) "已保存并移入回收站" else "已保存（移入回收站失败）"
                else -> "已保存到相册"
            }
            withContext(Dispatchers.Main) { onDone(trashed) }
        }
    }

    /** 分块移入回收站 / 永久删除并汇报进度；同时只跑一个批量任务 */
    fun remove(ids: Collection<Long>, options: PictureDeleteOptions, permanent: Boolean, onDone: (Int) -> Unit = {}) {
        if (ids.isEmpty() || _progress.value != null) return
        val list = ids.toList()
        _progress.value = ThumbnailProgress(0, list.size)
        appScope.launch {
            var removed = 0
            var processed = 0
            try {
                refreshGate.hold {
                    for (chunk in list.chunked(CHUNK)) {
                        val result = if (permanent) {
                            quarantine.deletePicturesPermanently(chunk, options)
                        } else {
                            quarantine.quarantinePictures(chunk, options, "清理 QQ 缩略图")
                        }
                        removed += result.selectedProcessed
                        processed += chunk.size
                        _progress.value = ThumbnailProgress(processed, list.size)
                    }
                }
            } finally {
                _progress.value = null
                val action = if (permanent) "已永久删除" else "已移入回收站"
                _message.value = if (removed < list.size) {
                    "$action $removed 张；${list.size - removed} 张因共用、保护或文件变化未处理"
                } else {
                    "$action $removed 张"
                }
                withContext(Dispatchers.Main) { onDone(removed) }
            }
        }
    }

    private companion object {
        const val CHUNK = 200
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}
