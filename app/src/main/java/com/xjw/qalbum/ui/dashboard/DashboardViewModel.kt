package com.xjw.qalbum.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xjw.qalbum.data.db.dao.CleanupDao
import com.xjw.qalbum.data.db.dao.ImageDao
import com.xjw.qalbum.data.repository.ImageRepository
import com.xjw.qalbum.domain.model.ScanProgress
import com.xjw.qalbum.service.indexer.ScanController
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class DashboardViewModel(
    private val imageRepository: ImageRepository,
    private val scanController: ScanController,
    private val cleanupDao: CleanupDao,
) : ViewModel() {

    private data class CoreStats(val imageCount: Int, val totalSize: Long)

    val uiState: StateFlow<DashboardUiState> = combine(
        combine(
            imageRepository.observeImageCount(),
            imageRepository.observeTotalSize(),
        ) { imageCount, totalSize -> CoreStats(imageCount, totalSize) },
        combine(
            imageRepository.observeSourceBreakdown(),
            imageRepository.observeAnalysisProgress(),
        ) { breakdown, analysis -> breakdown to analysis },
        imageRepository.observeImageHealth(),
        cleanupDao.observeQuarantineStats(),
        imageRepository.observeThumbnailTrashSummary(),
    ) { core, (breakdown, analysis), health, quarantine, thumbnailTrash ->
        DashboardUiState(
            imageCount = core.imageCount,
            totalSizeBytes = core.totalSize,
            breakdown = breakdown,
            analysisProgress = analysis,
            health = health,
            quarantineCount = quarantine.count,
            quarantineBytes = quarantine.bytes,
            thumbnailTrashCount = thumbnailTrash.count,
            thumbnailTrashBytes = thumbnailTrash.bytes,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DashboardUiState(),
    )

    val scanRunning: StateFlow<Boolean> = scanController.running
    val scanProgress: StateFlow<ScanProgress?> = scanController.progress

    fun startScan() = scanController.startIncrementalScan()
    fun startFullScan() = scanController.startFullScan()
    fun cancelScan() = scanController.cancel()
}

data class DashboardUiState(
    val imageCount: Int = 0,
    val totalSizeBytes: Long = 0L,
    val breakdown: List<ImageDao.SourceBreakdownRow> = emptyList(),
    val analysisProgress: ImageDao.AnalysisProgressRow = ImageDao.AnalysisProgressRow(0, 0, 0, 0, 0),
    val health: ImageDao.ImageHealthRow = ImageDao.ImageHealthRow(0, 0, 0, 0, 0, 0L),
    val quarantineCount: Int = 0,
    val quarantineBytes: Long = 0L,
    val thumbnailTrashCount: Int = 0,
    val thumbnailTrashBytes: Long = 0L,
)
