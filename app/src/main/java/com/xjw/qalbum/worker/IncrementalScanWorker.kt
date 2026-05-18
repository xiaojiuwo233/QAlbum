package com.xjw.qalbum.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.domain.model.ScanProgress
import com.xjw.qalbum.service.indexer.IncrementalScanner
import kotlinx.coroutines.flow.first
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber

/**
 * 增量扫描 Worker
 *
 * 调度策略：在 App 启动 / 定时（每 6 小时） / 用户手动触发 时入队。
 * Worker 内部把扫描进度直接吃掉，不传回 UI（UI 需要进度时用 [com.xjw.qalbum.service.indexer.ScanController]）。
 */
class IncrementalScanWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {

    private val settings: SettingsRepository by inject()
    private val scanner: IncrementalScanner by inject()

    override suspend fun doWork(): Result {
        return try {
            val root = settings.chatpicPath.first()
            var lastProgress: ScanProgress? = null
            scanner.scan(root).collect { lastProgress = it }
            Timber.i("IncrementalScanWorker finished with progress=$lastProgress")
            when (lastProgress) {
                is ScanProgress.Failed -> Result.retry()
                else -> Result.success()
            }
        } catch (t: Throwable) {
            Timber.e(t, "IncrementalScanWorker failed")
            Result.retry()
        }
    }

    companion object {
        const val NAME_UNIQUE = "qalbum_incremental_scan"
    }
}
