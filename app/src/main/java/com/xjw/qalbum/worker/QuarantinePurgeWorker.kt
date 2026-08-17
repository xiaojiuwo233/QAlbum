package com.xjw.qalbum.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.data.repository.ImageRepository
import com.xjw.qalbum.service.cleanup.QuarantineManager
import kotlinx.coroutines.flow.first
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber

/**
 * 回收站过期清理 Worker
 *
 * 每日一次（App 启动时 enqueueUniquePeriodicWork）把 expires_at 已到期的回收站记录逐页
 * 真正删除（[QuarantineManager.purgeExpired]），兑现 UI 承诺的「N 天后自动删除」——
 * 否则批量清理产生的数 GB 隔离文件会在专属目录只增不减。到期粒度是天级，无需更密调度。
 *
 * 顺带承载一次性迁移任务 [purgeLegacyDerivedData]：清理旧版本永久删除留下的
 * status='deleted' 行的派生数据残留（现行删除路径已内联级联清理）。
 */
class QuarantinePurgeWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {

    private val quarantineManager: QuarantineManager by inject()
    private val settings: SettingsRepository by inject()
    private val imageRepository: ImageRepository by inject()

    override suspend fun doWork(): Result {
        return try {
            val reconciled = quarantineManager.reconcilePending()
            val purged = quarantineManager.purgeExpired()
            purgeLegacyDerivedData()
            Timber.i("QuarantinePurgeWorker finished, reconciled=$reconciled, purged=$purged")
            Result.success()
        } catch (t: Throwable) {
            Timber.e(t, "QuarantinePurgeWorker failed")
            Result.retry()
        }
    }

    /**
     * 一次性清理存量 status='deleted' 行的历史派生数据（OCR/FTS/标签/向量/排斥）。
     * keyset 逐页推进（禁止全表逐行/全量装载），全部完成后置持久化标记不再执行；
     * 中途被杀下次从头再跑（purgeDerivedData 幂等，重复删是空操作）。
     */
    private suspend fun purgeLegacyDerivedData() {
        if (settings.legacyDerivedDataPurged.first()) return
        var afterId = 0L
        var cleaned = 0
        while (true) {
            val ids = imageRepository.deletedIdsPaged(afterId, LEGACY_PURGE_PAGE)
            if (ids.isEmpty()) break
            imageRepository.purgeDerivedData(ids)
            afterId = ids.last()
            cleaned += ids.size
        }
        settings.setLegacyDerivedDataPurged()
        Timber.i("QuarantinePurgeWorker: legacy derived data purged for $cleaned deleted rows")
    }

    companion object {
        const val NAME_UNIQUE = "qalbum_quarantine_purge"

        /** 调度周期（小时）：每日一次 */
        const val INTERVAL_HOURS = 24L

        /** 存量派生数据清理的分页大小 */
        private const val LEGACY_PURGE_PAGE = 500
    }
}
