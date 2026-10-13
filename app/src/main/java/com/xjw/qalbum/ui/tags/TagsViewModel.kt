package com.xjw.qalbum.ui.tags

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.xjw.qalbum.data.db.dao.TagDao
import com.xjw.qalbum.data.db.entity.OcrTextEntity
import com.xjw.qalbum.data.db.entity.TAG_USER_KEEP
import com.xjw.qalbum.data.paging.RefreshGate
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.data.repository.ImageRepository
import com.xjw.qalbum.domain.model.TimelineSection
import com.xjw.qalbum.service.cleanup.ArchiveManager
import com.xjw.qalbum.service.cleanup.CleanupController
import com.xjw.qalbum.service.cleanup.PictureDeleteOptions
import com.xjw.qalbum.service.cleanup.ExternalViewTarget
import com.xjw.qalbum.service.cleanup.ImageFileManager
import com.xjw.qalbum.ui.cleanup.PurgeProgress
import com.xjw.qalbum.ui.components.ScrollDirectory
import com.xjw.qalbum.ui.gallery.GalleryTimelineItem
import com.xjw.qalbum.ui.gallery.dateDirectory
import com.xjw.qalbum.ui.gallery.withDateHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 标签管理页 ViewModel
 *
 * - [tags]：所有「AI 标签」(ai/risk) + 用户手动「用户保留」(user) + 当前活跃图片数，按数量降序。
 * - [timelineFor]：某标签下活跃图片的分页流，**按时间分组**（复用图库的 [withDateHeaders]，与图库页一致）。
 * - [moveToTrash]：选中子集（[selectedIds] 非空）按 id 删，否则按 tag 分块全删，全程上报 [progress]。
 * - [removeTag]/[removeTagBulk]：从图片上取消该标签；若是「用户保留」标签，同时清除 user_keep（重新可进审核）。
 * - 分钟级批量操作（整标签回收站 / 批量取消标签）跑在 App 级 [appScope]：退出页面 / VM 销毁不中断，
 *   避免取消落在管线「文件已移、DB 未写」的中间窗口。
 * - 预览所需的归档 / 分享 / 保存 / OCR 与图库 / 审核页同一套实现。
 */
class TagsViewModel(
    private val tagDao: TagDao,
    private val imageRepository: ImageRepository,
    private val cleanupController: CleanupController,
    private val imageFileManager: ImageFileManager,
    private val archive: ArchiveManager,
    private val settings: SettingsRepository,
    private val appScope: CoroutineScope,
) : ViewModel() {

    val tags: StateFlow<List<TagDao.TagWithCount>> =
        tagDao.observeAiTagsWithCounts()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 快速滚动条（开关在设置里） */
    val fastScroll: StateFlow<Boolean> = settings.fastScrollEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** 每行图片数：与图库共用同一设置，保证两页布局一致。 */
    val galleryColumns: StateFlow<Int> = settings.galleryColumns
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.DEFAULT_GALLERY_COLUMNS)

    /** 导出子目录列表（预览「保存」弹窗用） */
    val exportSubdirs: StateFlow<List<String>> = settings.exportSubdirs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _progress = MutableStateFlow<PurgeProgress?>(null)
    val progress: StateFlow<PurgeProgress?> = _progress.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** 单条活跃分页流：cachedIn 在给定 scope 内常驻，不主动取消不会释放页缓存与 Room 失效监听 */
    private var timelineScope: CoroutineScope? = null
    private var timelineTagId: Long? = null
    private var timelineFlow: Flow<PagingData<GalleryTimelineItem>>? = null
    private var timelineCounts: StateFlow<Map<Long, Int>?> = MutableStateFlow(null)

    /** 批量移入回收站期间关上：网格保持原样，结束后统一刷新一次 */
    private val refreshGate = RefreshGate()

    /**
     * 某标签下活跃图片，按时间（新→旧）分页并插入日期分组标题，与图库时间线同一渲染。
     * 同一标签重进复用同一条流；切换标签时取消旧流的缓存 scope ——否则反复浏览 N 个标签会
     * 挂着 N 条活跃 pager，images 表每次写入都让它们全部后台重查（200 万行库上代价显著）。
     */
    fun timelineFor(tagId: Long): Flow<PagingData<GalleryTimelineItem>> {
        timelineFlow?.let { if (timelineTagId == tagId) return it }
        timelineScope?.cancel()
        // 子 scope 挂在 viewModelScope 的 Job 下：VM 清理时随之取消，切换标签时单独取消
        val scope = CoroutineScope(
            viewModelScope.coroutineContext + SupervisorJob(viewModelScope.coroutineContext[Job]),
        )
        timelineScope = scope
        timelineTagId = tagId
        // 计数流固化为 StateFlow：每个 PagingData generation 冷订阅时即时读缓存值，
        // 不再各自触发一次 Room 聚合冷查询（与 GalleryViewModel 同范式）
        val bucketCounts = imageRepository.observeTagDateBucketCounts(tagId)
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)
        timelineCounts = bucketCounts
        return imageRepository.imagesByTagPager(tagId, refreshGate)
            .withDateHeaders(bucketCounts)
            .cachedIn(scope)
            .also { timelineFlow = it }
    }

    /** 快速滚动条的目录：与 [timelineFor] 同一份日期计数 */
    fun directoryFor(tagId: Long): Flow<ScrollDirectory?> {
        timelineFor(tagId)
        return timelineCounts.map { counts -> counts?.let { dateDirectory(it, ascending = false) } }
    }

    /** 点日期标题全选：这一天里带该标签的全部图片 */
    suspend fun sectionIds(tagId: Long, section: TimelineSection): List<Long> = when (section) {
        is TimelineSection.Day -> imageRepository.tagIdsBetween(tagId, section.from, section.until)
        is TimelineSection.Size -> emptyList()
    }

    fun setColumns(columns: Int) {
        viewModelScope.launch { settings.setGalleryColumns(columns) }
    }

    fun createSubdir(name: String) = settings.addExportSubdir(name)

    /** 归档 / 取消归档（连同同一张图的其他版本）；[onDone] 回调归档后它是否还在图库里显示 */
    fun setArchived(imageId: Long, archived: Boolean, onDone: (visible: Boolean) -> Unit = {}) {
        viewModelScope.launch {
            if (archived) archive.archive(listOf(imageId)) else archive.unarchive(listOf(imageId))
            onDone(!archived || settings.archiveVisible.first())
        }
    }

    /** 预览「分享」：缓存 + 补后缀，回调拿到可分享的 content Uri */
    fun shareImage(imageId: Long, onReady: (ExternalViewTarget) -> Unit) {
        viewModelScope.launch { imageFileManager.prepareShare(imageId)?.let(onReady) }
    }

    /** 预览「保存」：导出到 Pictures/QAlbum[/子目录]；[alsoTrash] 为真则保存后把该图移入回收站。 */
    fun saveImage(imageId: Long, subdir: String?, alsoTrash: Boolean, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = imageFileManager.exportToGallery(imageId, subdir)
            if (ok && alsoTrash) {
                cleanupController.moveToTrash(listOf(imageId to "保存后移入回收站"))
            }
            onDone(ok)
        }
    }

    suspend fun getOcr(imageId: Long): OcrTextEntity? = imageRepository.getOcr(imageId)

    /**
     * 取消单张图片的该标签。若是「用户保留」标签，同时把 user_keep 清掉——
     * 等价于「撤销保留」，该图之后可重新进入审核（见 [TAG_USER_KEEP] 语义）；
     * 摘标签与清 user_keep 在 DAO 层同一事务内完成，不会出现半完成状态。
     */
    fun removeTag(tag: TagDao.TagWithCount, imageId: Long, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            tagDao.removeTagBatch(tag.id, listOf(imageId), clearUserKeep = tag.name == TAG_USER_KEEP)
            onDone()
        }
    }

    /**
     * 批量取消选中图片的该标签（同 [removeTag] 语义）。DAO 层分块 IN 删除并包进单事务：
     * 整批只触发一次表失效，且不会留下「一半摘了标签、一半没摘」的中间态。
     * [onDone] 回调实际处理张数，失败回调 0。
     */
    fun removeTagBulk(tag: TagDao.TagWithCount, ids: Set<Long>, onDone: (Int) -> Unit = {}) {
        if (ids.isEmpty() || _busy.value) return
        _busy.value = true
        appScope.launch {
            try {
                tagDao.removeTagBatch(tag.id, ids.toList(), clearUserKeep = tag.name == TAG_USER_KEEP)
                withContext(Dispatchers.Main.immediate) { onDone(ids.size) }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Timber.e(t, "批量取消标签失败: tag=${tag.name}, count=${ids.size}")
                withContext(Dispatchers.Main.immediate) { onDone(0) }
            } finally {
                _busy.value = false
            }
        }
    }

    /**
     * 移入回收站：[selectedIds] 非空时删这些选中图，为空时删该标签下全部图片。
     * [onDone] 回调实际移动成功的张数。
     */
    fun moveToTrash(tag: TagDao.TagWithCount, selectedIds: Set<Long>, options: PictureDeleteOptions = PictureDeleteOptions(), onDone: (Int) -> Unit = {}) {
        if (_busy.value) return
        _busy.value = true
        appScope.launch {
            try {
                val moved = refreshGate.hold {
                    if (selectedIds.isNotEmpty()) {
                        _progress.value = PurgeProgress(0, selectedIds.size, PROGRESS_LABEL)
                        cleanupController.moveToTrash(
                            selectedIds.map { it to reasonFor(tag) },
                            options,
                        ) { done, total -> _progress.value = PurgeProgress(done, total, PROGRESS_LABEL) }
                    } else {
                        deleteWholeTag(tag)
                    }
                }
                withContext(Dispatchers.Main.immediate) { onDone(moved) }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Timber.e(t, "按标签移入回收站失败: tag=${tag.name}")
                withContext(Dispatchers.Main.immediate) { onDone(0) }
            } finally {
                _progress.value = null
                _busy.value = false
            }
        }
    }

    /** 看图页「删除」：把这一张移入回收站（归档 / 保留的图片会被跳过），[onDone] 回调是否成功 */
    fun trashOne(tag: TagDao.TagWithCount, imageId: Long, onDone: (Boolean) -> Unit = {}) {
        appScope.launch {
            val moved = try {
                cleanupController.moveToTrash(listOf(imageId to reasonFor(tag))) == 1
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Timber.e(t, "移入回收站失败: tag=${tag.name}")
                false
            }
            withContext(Dispatchers.Main.immediate) { onDone(moved) }
        }
    }

    /** 整标签全删：按 id 升序 keyset 分块，逐块移入回收站；已隔离的图自然退出活跃集，不会重复。 */
    private suspend fun deleteWholeTag(tag: TagDao.TagWithCount): Int {
        val total = imageRepository.countImagesByTag(tag.id)
        _progress.value = PurgeProgress(0, total, PROGRESS_LABEL)
        var afterId = 0L
        var done = 0
        var moved = 0
        while (true) {
            val ids = imageRepository.imageIdsByTagChunk(tag.id, afterId, TAG_DELETE_CHUNK)
            if (ids.isEmpty()) break
            moved += cleanupController.moveToTrash(ids.map { it to reasonFor(tag) }) { d, _ ->
                _progress.value = PurgeProgress((done + d).coerceAtMost(total), total, PROGRESS_LABEL)
            }
            done += ids.size
            afterId = ids.last()
            _progress.value = PurgeProgress(done.coerceAtMost(total), total, PROGRESS_LABEL)
        }
        return moved
    }

    private fun reasonFor(tag: TagDao.TagWithCount): String = "标签：${tag.name}"

    companion object {
        private const val TAG_DELETE_CHUNK = 200
        private const val PROGRESS_LABEL = "正在移入回收站…"
    }
}
