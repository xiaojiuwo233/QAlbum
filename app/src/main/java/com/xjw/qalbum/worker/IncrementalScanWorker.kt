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
import com.xjw.qalbum.service.indexer.ScanMode
import com.xjw.qalbum.service.indexer.ScanPipeline
import kotlinx.coroutines.flow.first
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * 增量扫描 Worker
 *
 * 调度入口是 [schedulePeriodic]（App 启动时调用，KEEP 幂等）。
 * 目录剪枝后增量常态秒级完成，定时任务的电量/IO 开销可忽略。
 * Worker 内部把扫描进度直接吃掉，不传回 UI（UI 需要进度时用 [com.xjw.qalbum.service.indexer.ScanController]）。
 */
class IncrementalScanWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {

    private val settings: SettingsRepository by inject()
    private val pipeline: ScanPipeline by inject()

    override suspend fun doWork(): Result {
        return try {
            val root = settings.chatpicPath.first()
            var lastProgress: ScanProgress? = null
            pipeline.scan(root, ScanMode.INCREMENTAL).collect { lastProgress = it }
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

        private const val INTERVAL_HOURS = 6L

        /**
         * 注册每 [INTERVAL_HOURS] 小时的后台增量扫描（App 启动时调用一次）。
         * KEEP 策略：已有排程保持不变，重复调用幂等；WorkManager 会在满足
         * 约束时顺带补上「启动后不久跑一次」的首次执行。
         */
        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<IncrementalScanWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME_UNIQUE,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
