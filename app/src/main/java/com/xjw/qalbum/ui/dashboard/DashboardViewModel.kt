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

/**
 * 概览页 ViewModel
 *
 * 实时聚合：物理文件数、逻辑图片数、占用空间、各目录占比、当前扫描状态。
 */
class DashboardViewModel(
    private val imageRepository: ImageRepository,
    private val scanController: ScanController,
) : ViewModel() {

    val uiState: StateFlow<DashboardUiState> = combine(
        imageRepository.observePhysicalCount(),
        imageRepository.observeLogicalCount(),
        imageRepository.observeTotalSize(),
        imageRepository.observeSourceBreakdown(),
    ) { physical, logical, totalSize, breakdown ->
        DashboardUiState(
            physicalCount = physical,
            logicalCount = logical,
            totalSizeBytes = totalSize,
            breakdown = breakdown,
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
)
