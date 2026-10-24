package com.xjw.qalbum.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.domain.model.ScanProgress
import com.xjw.qalbum.service.indexer.ScanController
import com.xjw.qalbum.ui.common.formatDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * 自动扫描 Worker：每 [INTERVAL_HOURS] 小时增量扫描一次新图片（设置 → 扫描 →「自动扫描」可关）。
 *
 * 经 [ScanController] 跑，打开着 App 时概览和扫描页能看到进度；结果记在设置里，扫描页显示「上次自动扫描」。
 * 系统常在打开 App 时才补跑到期的任务，所以看起来像「一打开就扫描」。
 */
class IncrementalScanWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {

    private val settings: SettingsRepository by inject()
    private val scans: ScanController by inject()

    override suspend fun doWork(): Result {
        if (!settings.autoScan.first()) return Result.success()
        return try {
            // 已有扫描在跑（用户手动点的）就不再扫
            val last = scans.runAutomatic() ?: return Result.success()
            Timber.i("IncrementalScanWorker finished with progress=$last")
            settings.setAutoScanLast("${formatDateTime(System.currentTimeMillis())} · ${summary(last)}")
            if (last is ScanProgress.Failed) Result.retry() else Result.success()
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Timber.e(t, "IncrementalScanWorker failed")
            Result.retry()
        }
    }

    private fun summary(progress: ScanProgress?): String = when (progress) {
        is ScanProgress.Done -> if (progress.total > 0) "新增或更新 ${progress.total} 张" else "没有新图片"
        is ScanProgress.Failed -> "失败：${progress.reason}"
        ScanProgress.Cancelled -> "已取消"
        else -> "没有完成"
    }

    companion object {
        const val NAME_UNIQUE = "qalbum_incremental_scan"

        private const val INTERVAL_HOURS = 6L

        /**
         * 打开「自动扫描」就排一个每 [INTERVAL_HOURS] 小时的任务（已有则保持），关掉就撤掉。
         * KEEP 策略：重复调用幂等；WorkManager 会在满足约束时顺带补上「排上后不久跑一次」的首次执行。
         */
        fun schedule(context: Context, enabled: Boolean) {
            val work = WorkManager.getInstance(context)
            if (!enabled) {
                work.cancelUniqueWork(NAME_UNIQUE)
                return
            }
            val request = PeriodicWorkRequestBuilder<IncrementalScanWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            work.enqueueUniquePeriodicWork(NAME_UNIQUE, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
