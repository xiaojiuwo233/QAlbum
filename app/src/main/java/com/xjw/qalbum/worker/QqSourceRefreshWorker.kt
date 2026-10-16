package com.xjw.qalbum.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.domain.model.AccessMode
import com.xjw.qalbum.qq.QqSourceRepository
import kotlinx.coroutines.flow.first
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * 每天在充电时读取聊天来源里的新消息（增量，超过一周的表会从头读一次）。只在 Root 模式、
 * 且手动读取过一次之后进行；Root 没授权等失败只记日志，第二天再试。
 */
class QqSourceRefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {

    private val settings: SettingsRepository by inject()
    private val sources: QqSourceRepository by inject()

    override suspend fun doWork(): Result {
        if (settings.accessMode.first() != AccessMode.ROOT || !settings.qqAutoRefresh.first()) return Result.success()
        if (sources.dao.markedAccounts() == 0) return Result.success()
        return try {
            sources.refreshAndWait()
            Timber.i("QqSourceRefreshWorker: %s", sources.report.value ?: "")
            Result.success()
        } catch (t: Throwable) {
            Timber.e(t, "QqSourceRefreshWorker failed")
            Result.success()
        }
    }

    companion object {
        const val NAME_UNIQUE = "qalbum_qq_source_refresh"

        /** 打开时排一个每天的任务（充电、电量不低时），关闭时撤掉 */
        fun schedule(context: Context, enabled: Boolean) {
            val work = WorkManager.getInstance(context)
            if (!enabled) {
                work.cancelUniqueWork(NAME_UNIQUE)
                return
            }
            val request = PeriodicWorkRequestBuilder<QqSourceRefreshWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiresCharging(true).setRequiresBatteryNotLow(true).build())
                .build()
            work.enqueueUniquePeriodicWork(NAME_UNIQUE, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
