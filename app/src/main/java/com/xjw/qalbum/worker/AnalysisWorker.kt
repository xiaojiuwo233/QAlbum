package com.xjw.qalbum.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import timber.log.Timber

/**
 * 分析任务 Worker（Phase 2 起实装）
 *
 * 后续会按 [com.xjw.qalbum.domain.model.ScanProgress] 依赖关系
 * 串行执行 Hash → Quality → Duplicate → OCR → AI 等阶段。
 */
class AnalysisWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        Timber.i("AnalysisWorker placeholder (Phase 0) — Phase 2+ will implement analysis pipeline")
        return Result.success()
    }

    companion object {
        const val NAME_UNIQUE = "qalbum_analysis"
    }
}
