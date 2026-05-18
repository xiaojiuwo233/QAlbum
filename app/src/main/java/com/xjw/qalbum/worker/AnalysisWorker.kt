package com.xjw.qalbum.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.xjw.qalbum.service.analysis.AnalysisPipeline
import com.xjw.qalbum.service.analysis.AnalysisState
import kotlinx.coroutines.flow.first
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber

/**
 * 后台分析 Worker
 *
 * 把 hash + quality + duplicate + score 一次跑完，
 * 等 [AnalysisPipeline.state] 变成 Done 或 Failed 时退出。
 */
class AnalysisWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {

    private val pipeline: AnalysisPipeline by inject()

    override suspend fun doWork(): Result {
        return try {
            pipeline.start()
            // 等到 pipeline 进入终态
            val terminal = pipeline.state.first { it == AnalysisState.Done || it == AnalysisState.Failed }
            Timber.i("AnalysisWorker finished with state=$terminal")
            if (terminal == AnalysisState.Done) Result.success() else Result.retry()
        } catch (t: Throwable) {
            Timber.e(t, "AnalysisWorker failed")
            Result.retry()
        }
    }

    companion object {
        const val NAME_UNIQUE = "qalbum_analysis"
    }
}
