package com.xjw.qalbum.ui.cleanup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xjw.qalbum.data.db.dao.ImageDao
import com.xjw.qalbum.data.db.entity.CleanupBatchEntity
import com.xjw.qalbum.data.db.entity.CleanupCandidateEntity
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.data.db.entity.QuarantineEntity
import com.xjw.qalbum.data.repository.ImageRepository
import com.xjw.qalbum.service.analysis.AnalysisPipeline
import com.xjw.qalbum.service.analysis.AnalysisProgress
import com.xjw.qalbum.service.analysis.AnalysisState
import com.xjw.qalbum.service.cleanup.CleanupBatch
import com.xjw.qalbum.service.cleanup.CleanupController
import com.xjw.qalbum.service.cleanup.CleanupStrategy
import com.xjw.qalbum.service.cleanup.ThumbnailTrashManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CleanupViewModel(
    private val cleanupController: CleanupController,
    private val analysisPipeline: AnalysisPipeline,
    private val thumbnailTrashManager: ThumbnailTrashManager,
    private val imageRepository: ImageRepository,
) : ViewModel() {

    val analysisState: StateFlow<AnalysisState> = analysisPipeline.state
    val analysisProgress: StateFlow<AnalysisProgress?> = analysisPipeline.progress

    val recentBatches: StateFlow<List<CleanupBatchEntity>> = cleanupController.observeRecentBatches()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val quarantine: StateFlow<List<QuarantineEntity>> = cleanupController.observeQuarantine()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val thumbnailTrashSummary: StateFlow<ImageDao.ThumbnailTrashSummary> =
        thumbnailTrashManager.observeSummary()
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                ImageDao.ThumbnailTrashSummary(0, 0L),
            )

    val thumbnailTrash: StateFlow<List<ImageEntity>> = thumbnailTrashManager.observeItems()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _lastBatch = MutableStateFlow<CleanupBatch?>(null)
    val lastBatch: StateFlow<CleanupBatch?> = _lastBatch.asStateFlow()

    private val _reviewBatch = MutableStateFlow<CleanupReviewBatch?>(null)
    val reviewBatch: StateFlow<CleanupReviewBatch?> = _reviewBatch.asStateFlow()

    private val _selectedReviewDeleteIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedReviewDeleteIds: StateFlow<Set<Long>> = _selectedReviewDeleteIds.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _strategy = MutableStateFlow<CleanupStrategy>(CleanupStrategy.Balanced)
    val strategy: StateFlow<CleanupStrategy> = _strategy.asStateFlow()

    fun setStrategy(s: CleanupStrategy) {
        _strategy.value = s
    }

    fun startAnalysis() = analysisPipeline.start()
    fun cancelAnalysis() = analysisPipeline.cancel()

    fun generateBatch() {
        viewModelScope.launch {
            _busy.value = true
            try {
                val batch = cleanupController.generateBatch(_strategy.value)
                _lastBatch.value = batch
                loadReviewBatch(batch)
            } finally {
                _busy.value = false
            }
        }
    }

    fun applyBatch(batchId: Long, onDone: (Int) -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            try {
                val moved = cleanupController.applyBatch(batchId)
                onDone(moved)
            } finally {
                _busy.value = false
            }
        }
    }

    fun restore(quarantineId: Long) {
        viewModelScope.launch { cleanupController.restore(quarantineId) }
    }

    fun purge(quarantineId: Long) {
        viewModelScope.launch { cleanupController.purge(quarantineId) }
    }

    fun purgeAllRecycleBin(onDone: (Int) -> Unit = {}) {
        viewModelScope.launch {
            _busy.value = true
            try {
                val purgedImages = quarantine.value.count { cleanupController.purge(it.id) }
                val purgedThumbnails = thumbnailTrashManager.purgeAll()
                onDone(purgedImages + purgedThumbnails)
            } finally {
                _busy.value = false
            }
        }
    }

    fun purgeThumbnail(imageId: Long) {
        viewModelScope.launch { thumbnailTrashManager.purge(imageId) }
    }

    fun purgeAllThumbnails(onDone: (Int) -> Unit = {}) {
        viewModelScope.launch {
            _busy.value = true
            try {
                onDone(thumbnailTrashManager.purgeAll())
            } finally {
                _busy.value = false
            }
        }
    }

    fun setReviewItemSelected(imageId: Long, selected: Boolean) {
        _selectedReviewDeleteIds.value = if (selected) {
            _selectedReviewDeleteIds.value + imageId
        } else {
            _selectedReviewDeleteIds.value - imageId
        }
    }

    fun selectDuplicateDelete(deleteId: Long, pairedId: Long) {
        _selectedReviewDeleteIds.value = (_selectedReviewDeleteIds.value - pairedId - deleteId) + deleteId
    }

    fun applyReviewSelection(onDone: (Int) -> Unit = {}) {
        viewModelScope.launch {
            val review = _reviewBatch.value ?: return@launch
            val selectedIds = _selectedReviewDeleteIds.value
            _busy.value = true
            try {
                val reasonByImageId = review.candidates.associate { it.imageId to it.reason }
                val moved = cleanupController.moveToTrash(
                    selectedIds.map { imageId ->
                        imageId to (reasonByImageId[imageId] ?: "重复")
                    }
                )
                _reviewBatch.value = null
                _selectedReviewDeleteIds.value = emptySet()
                _lastBatch.value = null
                onDone(moved)
            } finally {
                _busy.value = false
            }
        }
    }

    fun closeReview() {
        _reviewBatch.value = null
        _selectedReviewDeleteIds.value = emptySet()
    }

    private suspend fun loadReviewBatch(batch: CleanupBatch) {
        val candidates = cleanupController.listCandidates(batch.id).ifEmpty { batch.candidates }
        val candidateImages = imageRepository.getByIds(candidates.map { it.imageId })
            .associateBy { it.id }
        val duplicateGroupCache = mutableMapOf<Long, List<ImageEntity>>()
        val duplicatePairs = mutableListOf<DuplicateReviewPair>()
        val disposableItems = mutableListOf<CleanupReviewItem>()

        candidates.forEach { candidate ->
            val image = candidateImages[candidate.imageId] ?: return@forEach
            val duplicateGroupId = image.duplicateGroupId
            if (candidate.reason == "重复" && duplicateGroupId != null) {
                val groupImages = duplicateGroupCache.getOrPut(duplicateGroupId) {
                    imageRepository.getByDuplicateGroup(duplicateGroupId)
                }
                val recommendedKeep = recommendedKeepImage(groupImages, image.id) ?: return@forEach
                duplicatePairs += DuplicateReviewPair(
                    groupId = duplicateGroupId,
                    keepImage = recommendedKeep,
                    deleteImage = image,
                    candidate = candidate,
                )
            } else {
                disposableItems += CleanupReviewItem(candidate, image)
            }
        }

        _reviewBatch.value = CleanupReviewBatch(
            batch = batch,
            candidates = candidates,
            duplicatePairs = duplicatePairs,
            disposableItems = disposableItems,
        )
        _selectedReviewDeleteIds.value = candidates.map { it.imageId }.toSet()
    }

    private fun recommendedKeepImage(images: List<ImageEntity>, excludingId: Long): ImageEntity? {
        return images.firstOrNull { it.isDuplicateBest && it.id != excludingId }
            ?: images.filter { it.id != excludingId }.maxByOrNull { qualityScore(it) }
            ?: images.maxByOrNull { qualityScore(it) }
    }

    private fun qualityScore(image: ImageEntity): Float {
        val pixels = (image.width.toLong() * image.height.toLong()).coerceAtLeast(0L) / 1_000_000f
        val clarity = image.blurScore ?: 0f
        val healthPenalty = if (image.isCorrupt || image.isBlank) 10_000f else 0f
        return clarity + pixels * 100f + image.infoScore * 500f - image.deleteScore * 200f - healthPenalty
    }
}

data class CleanupReviewBatch(
    val batch: CleanupBatch,
    val candidates: List<CleanupCandidateEntity>,
    val duplicatePairs: List<DuplicateReviewPair>,
    val disposableItems: List<CleanupReviewItem>,
)

data class DuplicateReviewPair(
    val groupId: Long,
    val keepImage: ImageEntity,
    val deleteImage: ImageEntity,
    val candidate: CleanupCandidateEntity,
)

data class CleanupReviewItem(
    val candidate: CleanupCandidateEntity,
    val image: ImageEntity,
)
