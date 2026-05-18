package com.xjw.qalbum.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.data.db.entity.OcrTextEntity
import com.xjw.qalbum.data.repository.ImageRepository
import com.xjw.qalbum.service.cleanup.VaultManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 图片详情页 ViewModel
 *
 * 加载主图 + 变体组成员 + OCR 文本；
 * 提供"加入保险箱 / 解除保护 / 导出到相册 / 标记保留" 几个动作。
 */
class ImageDetailViewModel(
    private val imageRepository: ImageRepository,
    private val vaultManager: VaultManager,
) : ViewModel() {

    private val _state = MutableStateFlow<ImageDetailState>(ImageDetailState.Loading)
    val state: StateFlow<ImageDetailState> = _state.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    fun load(imageId: Long) {
        viewModelScope.launch {
            val image = imageRepository.getById(imageId)
            if (image == null) {
                _state.value = ImageDetailState.NotFound
                return@launch
            }
            val variants = image.variantGroup?.let { imageRepository.getVariants(it) } ?: listOf(image)
            val ocr = imageRepository.getOcr(image.id)
            _state.value = ImageDetailState.Ready(image, variants, ocr)
        }
    }

    fun addToVault() {
        val image = (_state.value as? ImageDetailState.Ready)?.image ?: return
        viewModelScope.launch {
            val ok = vaultManager.addToVault(image.id)
            _toast.value = if (ok) "已加入保险箱" else "加入保险箱失败"
            load(image.id)
        }
    }

    fun toggleProtection() {
        val image = (_state.value as? ImageDetailState.Ready)?.image ?: return
        viewModelScope.launch {
            imageRepository.setProtected(image.id, !image.isProtected)
            load(image.id)
        }
    }

    fun exportToGallery() {
        val image = (_state.value as? ImageDetailState.Ready)?.image ?: return
        viewModelScope.launch {
            val ok = vaultManager.exportToGallery(image.id)
            _toast.value = if (ok) "已导出到系统相册（Pictures/QAlbum）" else "导出失败"
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
        val variants: List<ImageEntity>,
        val ocr: OcrTextEntity? = null,
    ) : ImageDetailState()
}
