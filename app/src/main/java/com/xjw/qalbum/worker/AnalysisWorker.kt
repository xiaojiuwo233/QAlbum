package com.xjw.qalbum.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.xjw.qalbum.service.analysis.AnalysisPipeline
import com.xjw.qalbum.service.analysis.AnalysisState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * 后台分析 Worker：分析的**保活载体**。
 *
 * 200w 图的 HASH / OCR 是数小时级任务，普通进程内协程在退后台后会被 cached-app freezer 冻结、
 * doze / LMK 杀死且无人拉起。本 Worker 以 dataSync 前台通知承载（长时任务，不受普通 Worker
 * 约 10 分钟执行上限约束），并**直接 suspend 驱动**管线（[AnalysisPipeline.runOnce]）：
 * WorkManager 停止 Worker 时取消沿调用链传导进各阶段，不会出现「Worker 停了、分析还在裸跑」。
 * 各阶段 *_done 标记按块落库，进程被杀后由 WorkManager 按退避重试自动续跑剩余待办。
 *
 * UI 触发统一走 [enqueue]（唯一工作，重复触发 KEEP 合并），取消走 [cancel]。
 */
class AnalysisWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {

    private val pipeline: AnalysisPipeline by inject()

    override suspend fun doWork(): Result {
        // Android 12+ 应用在后台时禁止拉起前台服务：降级为普通 Worker 继续跑
        // （届时受约 10 分钟上限，但分块落库保证已完成进度不丢，靠重试续跑）
        try {
            setForeground(getForegroundInfo())
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Timber.w(t, "AnalysisWorker: setForeground failed, run without foreground")
        }
        return try {
            // runOnce 返回 null = 已有一轮在跑（如 UI 旧路径触发）：等它到终态即可，不再起第二条管线
            val terminal = pipeline.runOnce()
                ?: pipeline.state.first { it != AnalysisState.Running }
            Timber.i("AnalysisWorker finished with state=$terminal")
            when (terminal) {
                AnalysisState.Failed ->
                    if (runAttemptCount >= MAX_RETRIES) Result.failure() else Result.retry()
                else -> Result.success() // Done；Idle = 用户主动取消，不重试
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Timber.e(t, "AnalysisWorker failed")
            if (runAttemptCount >= MAX_RETRIES) Result.failure() else Result.retry()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "后台分析", NotificationManager.IMPORTANCE_LOW),
        )
        val notification: Notification = Notification.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("正在分析图片")
            .setContentText("哈希 / 查重 / 评分 / 文字识别在后台进行")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val NAME_UNIQUE = "qalbum_analysis"
        private const val CHANNEL_ID = "analysis"
        private const val NOTIFICATION_ID = 1001

        /** Failed 状态的最大自动重试次数（配置性失败没必要无限退避重试）。 */
        private const val MAX_RETRIES = 3

        /** UI 触发入口：唯一工作防重复入队，已在跑则保留现有（KEEP）。 */
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                NAME_UNIQUE,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<AnalysisWorker>()
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build(),
            )
        }

        /** 取消后台分析（与 [AnalysisPipeline.cancel] 配合：Worker 取消会沿 runOnce 传导进各阶段）。 */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME_UNIQUE)
        }
    }
}
