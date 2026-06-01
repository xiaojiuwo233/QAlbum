package com.xjw.qalbum.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.data.db.entity.OcrTextEntity
import com.xjw.qalbum.data.repository.ImageRepository
import com.xjw.qalbum.service.cleanup.ImageFileManager
import com.xjw.qalbum.service.cleanup.QuarantineManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 图片详情页 ViewModel
 *
 * 时间线左右滑动只持有**id 列表**（轻量），当前图的完整实体按 index 用 [ImageRepository.getById]
 * 按需加载 —— 避免一次性 SELECT * 全表装载导致详情页卡死数分钟。
 */
class ImageDetailViewModel(
    private val imageRepository: ImageRepository,
    private val imageFileManager: ImageFileManager,
    private val quarantineManager: QuarantineManager,
) : ViewModel() {

    private val _state = MutableStateFlow<ImageDetailState>(ImageDetailState.Loading)
    val state: StateFlow<ImageDetailState> = _state.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()
    private var favoritesOnly = false

    fun load(imageId: Long, favoritesOnly: Boolean = false) {
        this.favoritesOnly = favoritesOnly
        viewModelScope.launch {
            val ids = if (favoritesOnly) {
                imageRepository.favoriteTimelineIds()
            } else {
                imageRepository.timelineIds()
            }
            val initialIndex = ids.indexOf(imageId)
            if (initialIndex < 0) {
                _state.value = ImageDetailState.NotFound
                return@launch
            }
            val image = imageRepository.getById(imageId)
            if (image == null) {
                _state.value = ImageDetailState.NotFound
                return@launch
            }
            val ocr = imageRepository.getOcr(imageId)
            _state.value = ImageDetailState.Ready(
                ids = ids,
                currentIndex = initialIndex,
                current = image,
                ocr = ocr,
            )
        }
    }

    fun selectIndex(index: Int) {
        val ready = _state.value as? ImageDetailState.Ready ?: return
        if (index == ready.currentIndex || index !in ready.ids.indices) return
        viewModelScope.launch {
            val id = ready.ids[index]
            val image = imageRepository.getById(id) ?: return@launch
            val ocr = imageRepository.getOcr(id)
            // 以最新 state 为基准（挂起期间可能已被其他翻页更新）
            val latest = _state.value as? ImageDetailState.Ready ?: return@launch
            _state.value = latest.copy(currentIndex = index, current = image, ocr = ocr)
        }
    }

    /** 供 HorizontalPager 每页按 id 异步加载图片实体（path/mime 等） */
    suspend fun entityFor(id: Long): ImageEntity? = imageRepository.getById(id)

    fun toggleFavorite() {
        val image = (_state.value as? ImageDetailState.Ready)?.current ?: return
        viewModelScope.launch {
            val next = !image.isFavorite
            imageRepository.setFavorite(image.id, next)
            _toast.value = if (next) "已收藏" else "已取消收藏"
            load(image.id, favoritesOnly)
        }
    }

    fun exportToGallery() {
        val image = (_state.value as? ImageDetailState.Ready)?.current ?: return
        viewModelScope.launch {
            val ok = imageFileManager.exportToGallery(image.id)
            _toast.value = if (ok) "已导出到系统相册（Pictures/QAlbum）" else "导出失败"
        }
    }

    fun prepareOpenExternal(onReady: (ExternalOpenTarget?) -> Unit) {
        val image = (_state.value as? ImageDetailState.Ready)?.current ?: return
        viewModelScope.launch {
            val target = imageFileManager.prepareExternalView(image.id)
            if (target == null) {
                _toast.value = "无法打开第三方应用"
                onReady(null)
            } else {
                onReady(ExternalOpenTarget(target.uri, target.mimeType))
            }
        }
    }

    fun moveCurrentToTrash(onExhausted: () -> Unit = {}) {
        val ready = _state.value as? ImageDetailState.Ready ?: return
        val image = ready.current
        viewModelScope.launch {
            val ok = quarantineManager.quarantine(image.id, "用户手动删除")
            if (ok) {
                _toast.value = "已移到回收站"
                advanceAfterRemoval(ready, image.id, onExhausted)
            } else {
                _toast.value = "移动到回收站失败"
            }
        }
    }

    fun deleteCurrentPermanently(onExhausted: () -> Unit = {}) {
        val ready = _state.value as? ImageDetailState.Ready ?: return
        val image = ready.current
        viewModelScope.launch {
            val ok = quarantineManager.deletePermanently(image.id)
            if (ok) {
                _toast.value = "已永久删除"
                advanceAfterRemoval(ready, image.id, onExhausted)
            } else {
                _toast.value = "永久删除失败"
            }
        }
    }

    /**
     * 删除 / 移入回收站成功后：把当前图从时间线移除，跳到**上一张**
     * （删的是首张则停在新的首张）。若已无图可显示，回调 [onExhausted]（由 UI 返回图库）。
     */
    private suspend fun advanceAfterRemoval(
        ready: ImageDetailState.Ready,
        removedId: Long,
        onExhausted: () -> Unit,
    ) {
        val newIds = ready.ids.filterNot { it == removedId }
        if (newIds.isEmpty()) {
            onExhausted()
            return
        }
        val newIndex = (ready.currentIndex - 1).coerceIn(0, newIds.size - 1)
        val image = imageRepository.getById(newIds[newIndex])
        if (image == null) {
            onExhausted()
            return
        }
        val ocr = imageRepository.getOcr(image.id)
        _state.value = ImageDetailState.Ready(
            ids = newIds,
            currentIndex = newIndex,
            current = image,
            ocr = ocr,
        )
    }

    fun consumeToast() {
        _toast.value = null
    }
}

sealed class ImageDetailState {
    data object Loading : ImageDetailState()
    data object NotFound : ImageDetailState()
    data class Ready(
        val ids: List<Long>,
        val currentIndex: Int,
        val current: ImageEntity,
        val ocr: OcrTextEntity? = null,
    ) : ImageDetailState()
}

data class ExternalOpenTarget(
    val uri: Uri,
    val mimeType: String,
)
