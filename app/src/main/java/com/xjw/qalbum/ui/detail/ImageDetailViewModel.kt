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
 * 加载当前图片 + OCR 文本；
 * 提供"收藏 / 取消收藏 / 导出到相册 / 删除"几个动作。
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
            val timeline = if (favoritesOnly) {
                imageRepository.favoriteTimelineSnapshot()
            } else {
                imageRepository.timelineSnapshot()
            }
            val initialIndex = timeline.indexOfFirst { it.id == imageId }
            if (initialIndex < 0) {
                _state.value = ImageDetailState.NotFound
                return@launch
            }
            loadAtIndex(timeline, initialIndex)
        }
    }

    fun selectIndex(index: Int) {
        val ready = _state.value as? ImageDetailState.Ready ?: return
        if (index == ready.currentIndex || index !in ready.timeline.indices) return
        viewModelScope.launch {
            loadAtIndex(ready.timeline, index)
        }
    }

    private suspend fun loadAtIndex(timeline: List<ImageEntity>, index: Int) {
        val timelineImage = timeline[index]
        val image = imageRepository.getById(timelineImage.id) ?: timelineImage
        val ocr = imageRepository.getOcr(image.id)
        _state.value = ImageDetailState.Ready(
            image = image,
            timeline = timeline,
            currentIndex = index,
            ocr = ocr,
        )
    }

    fun toggleFavorite() {
        val image = (_state.value as? ImageDetailState.Ready)?.image ?: return
        viewModelScope.launch {
            val next = !image.isFavorite
            imageRepository.setFavorite(image.id, next)
            _toast.value = if (next) "已收藏" else "已取消收藏"
            load(image.id, favoritesOnly)
        }
    }

    fun exportToGallery() {
        val image = (_state.value as? ImageDetailState.Ready)?.image ?: return
        viewModelScope.launch {
            val ok = imageFileManager.exportToGallery(image.id)
            _toast.value = if (ok) "已导出到系统相册（Pictures/QAlbum）" else "导出失败"
        }
    }

    fun prepareOpenExternal(onReady: (ExternalOpenTarget?) -> Unit) {
        val image = (_state.value as? ImageDetailState.Ready)?.image ?: return
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

    fun moveCurrentToTrash(onDone: (Boolean) -> Unit = {}) {
        val image = (_state.value as? ImageDetailState.Ready)?.image ?: return
        viewModelScope.launch {
            val ok = quarantineManager.quarantine(image.id, "用户手动删除")
            _toast.value = if (ok) "已移到回收站" else "移动到回收站失败"
            onDone(ok)
        }
    }

    fun deleteCurrentPermanently(onDone: (Boolean) -> Unit = {}) {
        val image = (_state.value as? ImageDetailState.Ready)?.image ?: return
        viewModelScope.launch {
            val ok = quarantineManager.deletePermanently(image.id)
            _toast.value = if (ok) "已永久删除" else "永久删除失败"
            onDone(ok)
        }
    }

    fun consumeToast() {
        _toast.value = null
    }
}

sealed class ImageDetailState {
    data object Loading : ImageDetailState()
    data object NotFound : ImageDetailState()
    data class Ready(
        val image: ImageEntity,
        val timeline: List<ImageEntity>,
        val currentIndex: Int,
        val ocr: OcrTextEntity? = null,
    ) : ImageDetailState()
}

data class ExternalOpenTarget(
    val uri: Uri,
    val mimeType: String,
)
