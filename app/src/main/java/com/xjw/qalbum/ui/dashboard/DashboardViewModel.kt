package com.xjw.qalbum.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xjw.qalbum.data.db.dao.CleanupDao
import com.xjw.qalbum.data.db.dao.ImageDao
import com.xjw.qalbum.data.db.dao.OriginalBackupDao
import com.xjw.qalbum.data.repository.ImageRepository
import com.xjw.qalbum.domain.model.ScanProgress
import com.xjw.qalbum.service.indexer.ScanController
import com.xjw.qalbum.data.paging.calmDuringBulkWrites
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
    private val backups: OriginalBackupDao,
) : ViewModel() {

    private data class CoreStats(val imageCount: Int, val totalSize: Long)

    val uiState: StateFlow<DashboardUiState> = combine(
        combine(
            imageRepository.observeImageCount(),
            imageRepository.observeTotalSize(),
        ) { imageCount, totalSize -> CoreStats(imageCount, totalSize) },
        imageRepository.observeSourceBreakdown(),
        cleanupDao.observeQuarantineStats().calmDuringBulkWrites(),
        imageRepository.observeThumbnailTrashSummary(),
    ) { core, breakdown, quarantine, thumbnailTrash ->
        DashboardUiState(
            loaded = true,
            imageCount = core.imageCount,
            totalSizeBytes = core.totalSize,
            breakdown = breakdown,
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

    /** 「已清理」累计（独立 stateIn） */
    val cleanedStats: StateFlow<CleanedStats> =
        combine(imageRepository.observeCleanedStats(), backups.observeFreedStats().calmDuringBulkWrites(), ::CleanedStats).stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = CleanedStats(),
        )

    /**
     * 「今日已清理」：顶栏图标长按切到的今日维度。
     * 用 flow{} 包一层，使每次重新订阅（WhileSubscribed 超时后重启）都重算当日 0 点起点，
     * 从而跨零点回到页面也能刷新到新的一天，无需常驻定时器。
     */
    val todayCleanedStats: StateFlow<CleanedStats> =
        flow {
            val since = startOfTodayMillis()
            emitAll(combine(imageRepository.observeCleanedStatsSince(since), backups.observeFreedStatsSince(since).calmDuringBulkWrites(), ::CleanedStats))
        }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = CleanedStats(),
            )

    val scanRunning: StateFlow<Boolean> = scanController.running
    val scanProgress: StateFlow<ScanProgress?> = scanController.progress

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

/**
 * 顶栏「已清理」：永久删除的图片，加上压缩省下的空间。压缩保留了原图时，原图备份删掉（手动或到期）才算省下，
 * 和删除的图片离开回收站才算一样。
 */
data class CleanedStats(
    val deleted: ImageDao.CleanedStatsRow = ImageDao.CleanedStatsRow(0, 0L),
    val compressed: ImageDao.CleanedStatsRow = ImageDao.CleanedStatsRow(0, 0L),
) {
    val bytes: Long get() = deleted.bytes + compressed.bytes
    val isEmpty: Boolean get() = deleted.count <= 0 && compressed.count <= 0
}

data class DashboardUiState(
    /** 首批 DB 统计是否已到达：区分「真 0 张」与查询未返回的初始占位 0（首启引导卡靠它防闪现） */
    val loaded: Boolean = false,
    val imageCount: Int = 0,
    val totalSizeBytes: Long = 0L,
    val breakdown: List<ImageDao.SourceBreakdownRow> = emptyList(),
    val quarantineCount: Int = 0,
    val quarantineBytes: Long = 0L,
    val thumbnailTrashCount: Int = 0,
    val thumbnailTrashBytes: Long = 0L,
)
