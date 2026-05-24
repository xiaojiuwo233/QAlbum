package com.xjw.qalbum.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
) : ViewModel() {

    private data class CoreStats(val physical: Int, val logical: Int, val totalSize: Long)

    val uiState: StateFlow<DashboardUiState> = combine(
        combine(
            imageRepository.observePhysicalCount(),
            imageRepository.observeLogicalCount(),
            imageRepository.observeTotalSize(),
        ) { physical, logical, totalSize -> CoreStats(physical, logical, totalSize) },
        imageRepository.observeSourceBreakdown(),
        imageRepository.observeAnalysisProgress(),
        imageRepository.observeImageHealth(),
    ) { core, breakdown, analysis, health ->
        DashboardUiState(
            physicalCount = core.physical,
            logicalCount = core.logical,
            totalSizeBytes = core.totalSize,
            breakdown = breakdown,
            analysisProgress = analysis,
            health = health,
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
    val physicalCount: Int = 0,
    val logicalCount: Int = 0,
    val totalSizeBytes: Long = 0L,
    val breakdown: List<ImageDao.SourceBreakdownRow> = emptyList(),
    val analysisProgress: ImageDao.AnalysisProgressRow = ImageDao.AnalysisProgressRow(0, 0, 0, 0, 0, 0),
    val health: ImageDao.ImageHealthRow = ImageDao.ImageHealthRow(0, 0, 0, 0, 0, 0L),
)
