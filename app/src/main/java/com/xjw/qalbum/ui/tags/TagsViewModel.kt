package com.xjw.qalbum.ui.tags

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.xjw.qalbum.data.db.dao.TagDao
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.data.repository.ImageRepository
import com.xjw.qalbum.service.cleanup.CleanupController
import com.xjw.qalbum.ui.cleanup.PurgeProgress
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
 * - [tags]：所有「AI 打的标签」(ai/risk) + 当前活跃图片数，按数量降序。
 * - [pagerFor]：某标签下活跃图片的分页流（按需构建，缓存于 viewModelScope）。
 * - [moveToTrash]：把某标签下的图片移入回收站——选中子集（[selectedIds] 非空）按 id 删，
 *   否则按 tag 分块全删（不一次性装载整组 id），全程上报 [progress] 进度条。
 */
class TagsViewModel(
    private val tagDao: TagDao,
    private val imageRepository: ImageRepository,
    private val cleanupController: CleanupController,
) : ViewModel() {

    val tags: StateFlow<List<TagDao.TagWithCount>> =
        tagDao.observeAiTagsWithCounts()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _progress = MutableStateFlow<PurgeProgress?>(null)
    val progress: StateFlow<PurgeProgress?> = _progress.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun pagerFor(tagId: Long): Flow<PagingData<ImageEntity>> =
        imageRepository.imagesByTagPager(tagId).cachedIn(viewModelScope)

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
