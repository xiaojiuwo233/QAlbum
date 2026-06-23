package com.xjw.qalbum.ui.tags

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.xjw.qalbum.data.db.dao.TagDao
import com.xjw.qalbum.data.db.entity.OcrTextEntity
import com.xjw.qalbum.data.db.entity.TAG_USER_KEEP
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.data.repository.ImageRepository
import com.xjw.qalbum.service.cleanup.CleanupController
import com.xjw.qalbum.service.cleanup.ExternalViewTarget
import com.xjw.qalbum.service.cleanup.ImageFileManager
import com.xjw.qalbum.ui.cleanup.PurgeProgress
import com.xjw.qalbum.ui.gallery.GalleryTimelineItem
import com.xjw.qalbum.ui.gallery.withDateHeaders
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 标签管理页 ViewModel
 *
 * - [tags]：所有「AI 标签」(ai/risk) + 用户手动「用户保留」(user) + 当前活跃图片数，按数量降序。
 * - [timelineFor]：某标签下活跃图片的分页流，**按时间分组**（复用图库的 [withDateHeaders]，与图库页一致）。
 * - [moveToTrash]：选中子集（[selectedIds] 非空）按 id 删，否则按 tag 分块全删，全程上报 [progress]。
 * - [removeTag]/[removeTagBulk]：从图片上取消该标签；若是「用户保留」标签，同时清除 user_keep（重新可进审核）。
 * - 预览所需的收藏 / 分享 / 保存 / OCR 与图库 / 审核页同一套实现。
 */
class TagsViewModel(
    private val tagDao: TagDao,
    private val imageRepository: ImageRepository,
    private val cleanupController: CleanupController,
    private val imageFileManager: ImageFileManager,
    private val settings: SettingsRepository,
) : ViewModel() {

    val tags: StateFlow<List<TagDao.TagWithCount>> =
        tagDao.observeAiTagsWithCounts()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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

    /** 某标签下活跃图片，按时间（新→旧）分页并插入日期分组标题，与图库时间线同一渲染。 */
    fun timelineFor(tagId: Long): Flow<PagingData<GalleryTimelineItem>> =
        imageRepository.imagesByTagPager(tagId)
            .withDateHeaders(imageRepository.observeTagDateBucketCounts(tagId))
            .cachedIn(viewModelScope)

    fun setColumns(columns: Int) {
        viewModelScope.launch { settings.setGalleryColumns(columns) }
    }

    fun createSubdir(name: String) = settings.addExportSubdir(name)

    fun setFavorite(imageId: Long, favorite: Boolean) {
        viewModelScope.launch { imageRepository.setFavorite(imageId, favorite) }
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
     * 等价于「撤销保留」，该图之后可重新进入审核（见 [TAG_USER_KEEP] 语义）。
     */
    fun removeTag(tag: TagDao.TagWithCount, imageId: Long, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            tagDao.unlinkTag(imageId, tag.id)
            if (tag.name == TAG_USER_KEEP) imageRepository.setUserKeep(imageId, false)
            onDone()
        }
    }

    /** 批量取消选中图片的该标签（同 [removeTag] 语义）。 */
    fun removeTagBulk(tag: TagDao.TagWithCount, ids: Set<Long>, onDone: (Int) -> Unit = {}) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            ids.forEach { id ->
                tagDao.unlinkTag(id, tag.id)
                if (tag.name == TAG_USER_KEEP) imageRepository.setUserKeep(id, false)
            }
            onDone(ids.size)
        }
    }

    /**
     * 移入回收站：[selectedIds] 非空时删这些选中图，为空时删该标签下全部图片。
     * [onDone] 回调实际移动成功的张数。
     */
    fun moveToTrash(tag: TagDao.TagWithCount, selectedIds: Set<Long>, onDone: (Int) -> Unit = {}) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                val moved = if (selectedIds.isNotEmpty()) {
                    _progress.value = PurgeProgress(0, selectedIds.size, PROGRESS_LABEL)
                    cleanupController.moveToTrash(
                        selectedIds.map { it to reasonFor(tag) },
                    ) { done, total -> _progress.value = PurgeProgress(done, total, PROGRESS_LABEL) }
                } else {
                    deleteWholeTag(tag)
                }
                onDone(moved)
            } finally {
                _progress.value = null
                _busy.value = false
            }
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
