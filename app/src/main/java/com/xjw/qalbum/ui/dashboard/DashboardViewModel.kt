package com.xjw.qalbum.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xjw.qalbum.data.db.dao.CleanupDao
import com.xjw.qalbum.data.db.dao.ImageDao
import com.xjw.qalbum.data.repository.ImageRepository
import com.xjw.qalbum.domain.model.ScanProgress
import com.xjw.qalbum.service.analysis.AnalysisPipeline
import com.xjw.qalbum.service.analysis.AnalysisProgress
import com.xjw.qalbum.service.analysis.AnalysisState
import com.xjw.qalbum.service.indexer.ScanController
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import java.util.Calendar

class DashboardViewModel(
    private val imageRepository: ImageRepository,
    private val scanController: ScanController,
    private val cleanupDao: CleanupDao,
    private val analysisPipeline: AnalysisPipeline,
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

    /** 「已清理」累计（独立 stateIn，不挤进已嵌套到上限的 uiState combine） */
    val cleanedStats: StateFlow<ImageDao.CleanedStatsRow> =
        imageRepository.observeCleanedStats().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ImageDao.CleanedStatsRow(0, 0L),
        )

    /**
     * 「今日已清理」：顶栏图标长按切到的今日维度。
     * 用 flow{} 包一层，使每次重新订阅（WhileSubscribed 超时后重启）都重算当日 0 点起点，
     * 从而跨零点回到页面也能刷新到新的一天，无需常驻定时器。
     */
    val todayCleanedStats: StateFlow<ImageDao.CleanedStatsRow> =
        flow { emitAll(imageRepository.observeCleanedStatsSince(startOfTodayMillis())) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = ImageDao.CleanedStatsRow(0, 0L),
            )

    val scanRunning: StateFlow<Boolean> = scanController.running
    val scanProgress: StateFlow<ScanProgress?> = scanController.progress

    /** 与清理页共享同一 AnalysisPipeline 单例：分析运行态用于「分析流水线」卡片联动 */
    val analysisState: StateFlow<AnalysisState> = analysisPipeline.state
    val analysisRunningProgress: StateFlow<AnalysisProgress?> = analysisPipeline.progress

    fun startScan() = scanController.startIncrementalScan()
    fun startFullScan() = scanController.startFullScan()
    fun cancelScan() = scanController.cancel()

    /** 设备本地时区的今日 0 点（毫秒）。 */
    private fun startOfTodayMillis(): Long =
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
}

data class DashboardUiState(
    val imageCount: Int = 0,
    val totalSizeBytes: Long = 0L,
    val breakdown: List<ImageDao.SourceBreakdownRow> = emptyList(),
    val analysisProgress: ImageDao.AnalysisProgressRow = ImageDao.AnalysisProgressRow(0, 0, 0, 0, 0, 0),
    val health: ImageDao.ImageHealthRow = ImageDao.ImageHealthRow(0, 0, 0, 0, 0, 0L),
    val quarantineCount: Int = 0,
    val quarantineBytes: Long = 0L,
    val thumbnailTrashCount: Int = 0,
    val thumbnailTrashBytes: Long = 0L,
)
