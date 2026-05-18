package com.xjw.qalbum.service.analysis

import com.xjw.qalbum.access.AccessManager
import com.xjw.qalbum.data.db.dao.ImageDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 后台分析 pipeline
 *
 * 阶段顺序（每阶段处理 N 条后再切下一阶段，避免一阶段卡死整体进度）：
 * 1. HASH      —— 所有活跃图片：xxHash + pHash
 * 2. QUALITY   —— 主图：宽高 + 模糊度 + 纯色 + 损坏
 * 3. DUPLICATE —— 全库：xxHash 完全重复 + pHash 视觉相似
 * 4. SCORE     —— 每张主图：computeDeleteScore / computeInfoScore
 *
 * Phase 3+ 会在 SCORE 之前增加 OCR 阶段；Phase 4 增加 AI_TAG / EMBEDDING。
 */
class AnalysisPipeline(
    private val accessManager: AccessManager,
    private val imageDao: ImageDao,
    private val duplicateDetector: DuplicateDetector,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(AnalysisState.Idle)
    val state: StateFlow<AnalysisState> = _state.asStateFlow()

    private val _progress = MutableStateFlow<AnalysisProgress?>(null)
    val progress: StateFlow<AnalysisProgress?> = _progress.asStateFlow()

    private var job: Job? = null

    fun start() {
        if (_state.value == AnalysisState.Running) {
            Timber.w("AnalysisPipeline: already running")
            return
        }
        job = scope.launch {
            _state.value = AnalysisState.Running
            try {
                runHash()
                runQuality()
                runDuplicate()
                runScore()
                _state.value = AnalysisState.Done
            } catch (t: Throwable) {
                Timber.e(t, "AnalysisPipeline failed")
                _state.value = AnalysisState.Failed
                _progress.value = AnalysisProgress(AnalysisPhase.SCORE, 0, 0, t.message ?: "未知错误")
            }
        }
    }

    fun cancel() {
        job?.cancel(); job = null
        _state.value = AnalysisState.Idle
    }

    // ---- 各阶段 ----

    private suspend fun runHash() {
        val provider = accessManager.current()
        if (!provider.isAvailable()) {
            Timber.w("Hash phase skipped: access provider not ready")
            return
        }

        var processed = 0
        while (true) {
            val batch = imageDao.pendingHash(HASH_BATCH)
            if (batch.isEmpty()) break
            for (img in batch) {
                val xx = HashEngine.computeXxHash(provider, img.path)
                val ph = HashEngine.computePHash(provider, img.path)
                imageDao.saveHash(img.id, xx, ph)
                processed++
                if (processed % 20 == 0) {
                    _progress.value = AnalysisProgress(AnalysisPhase.HASH, processed, processed)
                }
            }
        }
        _progress.value = AnalysisProgress(AnalysisPhase.HASH, processed, processed, msg = "完成")
        Timber.i("AnalysisPipeline.runHash: hashed $processed images")
    }

    private suspend fun runQuality() {
        val provider = accessManager.current()
        if (!provider.isAvailable()) return

        var processed = 0
        while (true) {
            val batch = imageDao.pendingQuality(QUALITY_BATCH)
            if (batch.isEmpty()) break
            for (img in batch) {
                val r = QualityAnalyzer.analyze(provider, img.path)
                imageDao.saveQuality(img.id, r.width, r.height, r.blurScore, r.isBlank, r.isCorrupt)
                processed++
                if (processed % 10 == 0) {
                    _progress.value = AnalysisProgress(AnalysisPhase.QUALITY, processed, processed)
                }
            }
        }
        _progress.value = AnalysisProgress(AnalysisPhase.QUALITY, processed, processed, msg = "完成")
        Timber.i("AnalysisPipeline.runQuality: analyzed $processed images")
    }

    private suspend fun runDuplicate() {
        _progress.value = AnalysisProgress(AnalysisPhase.DUPLICATE, 0, 0, msg = "聚类中…")
        duplicateDetector.clusterExact()
        duplicateDetector.clusterVisual()
        _progress.value = AnalysisProgress(AnalysisPhase.DUPLICATE, 1, 1, msg = "完成")
    }

    private suspend fun runScore() {
        // 全库遍历主图，重新计算 info_score / delete_score 并写回
        var processed = 0
        var afterId = 0L
        while (true) {
            val page = imageDao.pagePrimaryHashed(afterId, SCORE_BATCH)
            if (page.isEmpty()) break
            for (img in page) {
                val info = ScoringEngine.computeInfoScore(img)
                val del = ScoringEngine.computeDeleteScore(img)
                imageDao.updateInfoScore(img.id, info)
                imageDao.updateDeleteScore(img.id, del)
                processed++
                afterId = img.id
            }
            _progress.value = AnalysisProgress(AnalysisPhase.SCORE, processed, processed)
        }
        _progress.value = AnalysisProgress(AnalysisPhase.SCORE, processed, processed, msg = "完成")
        Timber.i("AnalysisPipeline.runScore: scored $processed primary images")
    }

    companion object {
        private const val HASH_BATCH = 50
        private const val QUALITY_BATCH = 30
        private const val SCORE_BATCH = 500
    }
}

enum class AnalysisState { Idle, Running, Done, Failed }
enum class AnalysisPhase { HASH, QUALITY, DUPLICATE, SCORE }

data class AnalysisProgress(
    val phase: AnalysisPhase,
    val processed: Int,
    val total: Int,
    val msg: String? = null,
)
