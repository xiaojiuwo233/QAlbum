package com.xjw.qalbum.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import timber.log.Timber

/**
 * 增量扫描 Worker（Phase 1 实装）
 *
 * Phase 0 仅占位，便于 Koin WorkerFactory 与 WorkManager 初始化能跑通；
 * Phase 1 将实装：拉取 chatpic 目录文件列表 ↔ 数据库 diff ↔ 增量写入。
 */
class IncrementalScanWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        Timber.i("IncrementalScanWorker placeholder (Phase 0) — Phase 1 will implement scan logic")
        return Result.success()
    }

    companion object {
        const val NAME_UNIQUE = "qalbum_incremental_scan"
    }
}
