package com.xjw.qalbum.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.xjw.qalbum.analytics.Analytics
import com.xjw.qalbum.analytics.elapsedMs
import com.xjw.qalbum.analytics.originalsDeleted
import com.xjw.qalbum.analytics.trashPurged
import com.xjw.qalbum.service.cleanup.OriginalCompressor
import com.xjw.qalbum.service.cleanup.QuarantineManager
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber

/**
 * 回收站过期清理 Worker
 *
 * 每日一次（App 启动时 enqueueUniquePeriodicWork）把 expires_at 已到期的回收站记录逐页
 * 真正删除（[QuarantineManager.purgeExpired]），兑现 UI 承诺的「N 天后自动删除」——
 * 否则批量清理产生的数 GB 隔离文件会在专属目录只增不减。到期粒度是天级，无需更密调度。
 */
class QuarantinePurgeWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {

    private val quarantineManager: QuarantineManager by inject()
    private val compressor: OriginalCompressor by inject()
    private val analytics: Analytics by inject()

    override suspend fun doWork(): Result {
        return try {
            val reconciled = quarantineManager.reconcilePending()
            val started = System.nanoTime()
            val purged = quarantineManager.purgeExpired()
            if (purged > 0) analytics.trashPurged("expired", purged, purged, null, elapsedMs(started))
            val originals = compressor.purgeExpired()
            if (originals > 0) analytics.originalsDeleted("expired", originals)
            Timber.i("QuarantinePurgeWorker finished, reconciled=$reconciled, purged=$purged, originals=$originals")
            Result.success()
        } catch (t: Throwable) {
            Timber.e(t, "QuarantinePurgeWorker failed")
            Result.retry()
        }
    }

    companion object {
        const val NAME_UNIQUE = "qalbum_quarantine_purge"

        /** 调度周期（小时）：每日一次 */
        const val INTERVAL_HOURS = 24L
    }
}
