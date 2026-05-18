package com.xjw.qalbum.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.data.repository.ImageRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 图片详情页 ViewModel
 *
 * 加载主图 + 变体组成员；Phase 2 起会补充 hash / 模糊度 / OCR 摘要等。
 */
class ImageDetailViewModel(
    private val imageRepository: ImageRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<ImageDetailState>(ImageDetailState.Loading)
    val state: StateFlow<ImageDetailState> = _state.asStateFlow()

    fun load(imageId: Long) {
        viewModelScope.launch {
            val image = imageRepository.getById(imageId)
            if (image == null) {
                _state.value = ImageDetailState.NotFound
                return@launch
            }
            val variants = image.variantGroup?.let { imageRepository.getVariants(it) } ?: listOf(image)
            _state.value = ImageDetailState.Ready(image, variants)
        }
    }
}

sealed class ImageDetailState {
    data object Loading : ImageDetailState()
    data object NotFound : ImageDetailState()
    data class Ready(val image: ImageEntity, val variants: List<ImageEntity>) : ImageDetailState()
}
