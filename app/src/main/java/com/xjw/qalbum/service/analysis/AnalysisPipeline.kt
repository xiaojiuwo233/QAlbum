package com.xjw.qalbum.service.analysis

import com.xjw.qalbum.access.AccessManager
import com.xjw.qalbum.data.db.dao.ImageDao
import com.xjw.qalbum.data.db.dao.OcrDao
import com.xjw.qalbum.data.db.entity.OcrTextEntity
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.data.remote.OpenAiCompatClient
import com.xjw.qalbum.service.ai.AiTaggingService
import com.xjw.qalbum.service.ai.AiTaggingShared
import com.xjw.qalbum.service.ai.SemanticIndex
import com.xjw.qalbum.service.ocr.LocalOcrEngine
import com.xjw.qalbum.service.ocr.OcrResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger

/**
 * 后台分析 pipeline
 *
 * 阶段顺序（每阶段处理 N 条后再切下一阶段，避免一阶段卡死整体进度）：
 * 1. HASH      —— 所有活跃图片：xxHash + pHash
 * 2. QUALITY   —— 图片宽高 + 模糊度 + 纯色 + 损坏
 * 3. DUPLICATE —— 全库：xxHash 完全重复 + pHash 视觉相似
 * 4. SCORE     —— 每张图片：computeDeleteScore / computeInfoScore
 *
 * Phase 3+ 会在 SCORE 之前增加 OCR 阶段；Phase 4 增加 AI_TAG / EMBEDDING。
 */
class AnalysisPipeline(
    private val accessManager: AccessManager,
    private val imageDao: ImageDao,
    private val ocrDao: OcrDao,
    private val duplicateDetector: DuplicateDetector,
    private val ocrEngine: LocalOcrEngine,
    private val settings: SettingsRepository,
    private val aiClient: OpenAiCompatClient,
    private val aiTagging: AiTaggingService,
    private val semanticIndex: SemanticIndex,
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
                // 本次分析的上限与排序（各逐图阶段共用）：每阶段只取「未完成」的前 limit 张
                val limit = settings.analysisLimit.first()
                val oldestFirst = settings.analysisOldestFirst.first()
                runHashAndQuality(limit, oldestFirst)
                runDuplicate()
                runOcr(limit, oldestFirst)
                // AI 阶段仅在用户启用并填好 endpoint 时执行；网络阶段失败不影响整体
                if (isAiReady()) {
                    runAiTag(limit, oldestFirst)
                    runEmbedding(limit, oldestFirst)
                }
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

    /**
     * HASH + QUALITY 合并阶段：每张图**只读取一次**文件字节，随后在内存里同时算出
     * xxHash / pHash / 宽高 / 模糊度 / 纯色 / 损坏（原实现是每图 3 次独立读取）。
     * 批内并发处理（[CONCURRENCY]），让跨进程 IO 与解码 / DCT 的 CPU 开销相互重叠。
     */
    private suspend fun runHashAndQuality(limit: Int, oldestFirst: Boolean) {
        val provider = accessManager.current()
        if (!provider.isAvailable()) {
            Timber.w("Hash/Quality phase skipped: access provider not ready")
            return
        }

        // 一次只取「未完成、按所选排序」的前 limit 张，处理完即止（下次分析再取下一批）
        val batch = imageDao.pendingHash(limit, oldestFirst)
        if (batch.isEmpty()) {
            _progress.value = AnalysisProgress(AnalysisPhase.HASH, 0, 0, msg = "完成")
            return
        }
        val total = batch.size
        val processed = AtomicInteger(0)
        coroutineScope {
            val semaphore = Semaphore(CONCURRENCY)
            batch.map { img ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        val r = analyzeOne(provider, img.path)
                        imageDao.saveHash(img.id, r.xxhash, r.phash)
                        imageDao.saveQuality(img.id, r.width, r.height, r.blur, r.blank, r.corrupt)
                        val done = processed.incrementAndGet()
                        if (done % PROGRESS_STEP == 0 || done == total) {
                            // StateFlow.value 写入线程安全，可在子协程直接更新
                            _progress.value = AnalysisProgress(AnalysisPhase.HASH, done, total)
                        }
                    }
                }
            }.awaitAll()
        }
        _progress.value = AnalysisProgress(AnalysisPhase.HASH, total, total, msg = "完成")
        Timber.i("AnalysisPipeline.runHashAndQuality: analyzed $total images (limit=$limit)")
    }

    /** 一次读取字节 → xxHash + pHash + 质量，避免对同一文件重复跨进程读取。 */
    private suspend fun analyzeOne(
        provider: com.xjw.qalbum.access.FileAccessProvider,
        path: String,
    ): HashQualityResult {
        val bytes = HashEngine.readImageBytes(provider, path)
            ?: return HashQualityResult(null, null, 0, 0, null, false, true)
        val xxhash = HashEngine.xxHashOf(bytes)
        val bitmap = HashEngine.decodeScaledBytes(bytes, QUALITY_DECODE_SIDE)
            ?: return HashQualityResult(xxhash, null, 0, 0, null, false, true)
        val phash = HashEngine.computePHashFromBitmap(bitmap)
        val q = QualityAnalyzer.analyzeBitmap(bitmap)
        bitmap.recycle()
        return HashQualityResult(xxhash, phash, q.width, q.height, q.blurScore, q.isBlank, q.isCorrupt)
    }

    private data class HashQualityResult(
        val xxhash: String?,
        val phash: Long?,
        val width: Int,
        val height: Int,
        val blur: Float?,
        val blank: Boolean,
        val corrupt: Boolean,
    )

    private suspend fun runDuplicate() {
        _progress.value = AnalysisProgress(AnalysisPhase.DUPLICATE, 0, 0, msg = "聚类中…")
        imageDao.clearDuplicateAssignments()
        duplicateDetector.clusterExact()
        duplicateDetector.clusterVisual()
        _progress.value = AnalysisProgress(AnalysisPhase.DUPLICATE, 1, 1, msg = "完成")
    }

    private suspend fun runOcr(limit: Int, oldestFirst: Boolean) {
        val provider = accessManager.current()
        if (!provider.isAvailable()) return

        val batch = imageDao.pendingOcr(limit, oldestFirst)
        if (batch.isEmpty()) {
            _progress.value = AnalysisProgress(AnalysisPhase.OCR, 0, 0, msg = "完成")
            return
        }
        val total = batch.size
        var processed = 0
        run {
            for (img in batch) {
                val result = ocrEngine.recognize(provider, img.path)
                val now = System.currentTimeMillis()
                when (result) {
                    is OcrResult.Success -> {
                        ocrDao.saveOcrResult(
                            OcrTextEntity(
                                imageId = img.id,
                                text = result.text,
                                confidence = result.confidence,
                                hasPhone = result.hasPhone,
                                hasAmount = result.hasAmount,
                                hasAddress = result.hasAddress,
                                hasIdNumber = result.hasIdNumber,
                                hasOrderNo = result.hasOrderNo,
                                updatedAt = now,
                            )
                        )
                    }
                    OcrResult.Empty -> {
                        // 写一条空文本记录，避免 pendingOcr 反复返回
                        ocrDao.saveOcrResult(
                            OcrTextEntity(
                                imageId = img.id,
                                text = "",
                                confidence = 0f,
                                updatedAt = now,
                            )
                        )
                    }
                    is OcrResult.Failed -> {
                        Timber.w("OCR failed for ${img.path}: ${result.reason}")
                    }
                }
                imageDao.markOcrDone(img.id)
                processed++
                if (processed % 5 == 0 || processed == total) {
                    _progress.value = AnalysisProgress(AnalysisPhase.OCR, processed, total)
                }
            }
        }
        _progress.value = AnalysisProgress(AnalysisPhase.OCR, total, total, msg = "完成")
        Timber.i("AnalysisPipeline.runOcr: OCR-ed $total images (limit=$limit)")
    }

    private suspend fun isAiReady(): Boolean {
        val enabled = settings.aiEnabled.first()
        val endpoint = settings.aiEndpoint.first()
        val key = settings.aiApiKey.first()
        return enabled && endpoint.isNotBlank() && key.isNotBlank()
    }

    private suspend fun runAiTag(limit: Int, oldestFirst: Boolean) {
        val provider = accessManager.current()
        if (!provider.isAvailable()) return

        val batch = imageDao.pendingAiTag(limit, oldestFirst)
        if (batch.isEmpty()) {
            _progress.value = AnalysisProgress(AnalysisPhase.AI_TAG, 0, 0, msg = "完成")
            return
        }
        val total = batch.size
        var processed = 0
        for (img in batch) {
            val result = aiTagging.tagImage(provider, img.id)
            if (result is AiTaggingService.Result.Success) {
                imageDao.update(img.copy(aiDone = true))
            }
            processed++
            _progress.value = AnalysisProgress(AnalysisPhase.AI_TAG, processed, total)
        }
        _progress.value = AnalysisProgress(AnalysisPhase.AI_TAG, total, total, msg = "完成")
        Timber.i("AnalysisPipeline.runAiTag: tagged $processed/$total images (limit=$limit)")
    }

    private suspend fun runEmbedding(limit: Int, oldestFirst: Boolean) {
        val provider = accessManager.current()
        if (!provider.isAvailable()) return

        val batch = imageDao.pendingEmbedding(limit, oldestFirst)
        if (batch.isEmpty()) {
            _progress.value = AnalysisProgress(AnalysisPhase.EMBEDDING, 0, 0, msg = "完成")
            return
        }
        val total = batch.size
        var processed = 0
        val embModel = settings.aiEmbeddingModel.first()
        for (img in batch) {
            if (semanticIndex.hasEmbedding(img.id)) {
                imageDao.update(img.copy(embeddingDone = true))
                processed++
                continue
            }
            // 用 OCR 文本 + 文件名等作为 embedding 输入（兼容更多模型）
            val ocr = ocrDao.getByImageId(img.id)
            val textForEmbedding = buildEmbeddingText(img, ocr)
            if (textForEmbedding.isBlank()) {
                // 无可嵌入文本：标记完成，避免反复占用名额
                imageDao.update(img.copy(embeddingDone = true))
                processed++
                continue
            }
            val vec = aiClient.embedding(textForEmbedding)
            if (vec != null) {
                semanticIndex.upsert(img.id, embModel, vec)
                imageDao.update(img.copy(embeddingDone = true))
            }
            processed++
            _progress.value = AnalysisProgress(AnalysisPhase.EMBEDDING, processed, total)
        }
        _progress.value = AnalysisProgress(AnalysisPhase.EMBEDDING, total, total, msg = "完成")
        Timber.i("AnalysisPipeline.runEmbedding: embedded $processed/$total images (limit=$limit)")
    }

    private fun buildEmbeddingText(
        img: com.xjw.qalbum.data.db.entity.ImageEntity,
        ocr: OcrTextEntity?,
    ): String {
        val parts = mutableListOf<String>()
        parts.add(img.filename)
        parts.add(img.sourceType)
        if (img.isAnimated) parts.add("动图")
        ocr?.text?.takeIf { it.isNotBlank() }?.let { parts.add(it.take(500)) }
        return parts.joinToString(" ")
    }

    private suspend fun runScore() {
        // 全库遍历主图，重新计算 info_score / delete_score 并写回
        // 评分时会读取关联的 OCR 结果（hasPhone / hasAmount 等）作为强信号
        var processed = 0
        var afterId = 0L
        while (true) {
            val page = imageDao.pagePrimaryHashed(afterId, SCORE_BATCH)
            if (page.isEmpty()) break
            for (img in page) {
                val ocr = ocrDao.getByImageId(img.id)
                val info = ScoringEngine.computeInfoScore(img, ocr)
                val del = ScoringEngine.computeDeleteScore(img, ocr)
                imageDao.updateInfoScore(img.id, info)
                imageDao.updateDeleteScore(img.id, del)
                // 含敏感信息（手机/金额/证件/订单）自动加入收藏，避免误删
                if (ocr != null && (ocr.hasIdNumber || ocr.hasOrderNo || ocr.hasAmount)) {
                    imageDao.setFavorite(img.id, true)
                }
                processed++
                afterId = img.id
            }
            _progress.value = AnalysisProgress(AnalysisPhase.SCORE, processed, processed)
        }
        _progress.value = AnalysisProgress(AnalysisPhase.SCORE, processed, processed, msg = "完成")
        Timber.i("AnalysisPipeline.runScore: scored $processed primary images")
    }

    companion object {
        private const val SCORE_BATCH = 500

        /** hash+质量 合并阶段的并发度（IO 等待 + 解码/DCT CPU 混合，取中等并发避免过度争抢） */
        private const val CONCURRENCY = 4

        /** 质量 / pHash 的解码目标边长（pHash 再缩到 32，质量在此尺寸上评估） */
        private const val QUALITY_DECODE_SIDE = 768

        /** 进度上报步长 */
        private const val PROGRESS_STEP = 16
    }
}

enum class AnalysisState { Idle, Running, Done, Failed }
enum class AnalysisPhase(val displayName: String) {
    HASH("哈希/质量"),
    QUALITY("质量"),
    DUPLICATE("查重"),
    OCR("文字识别"),
    AI_TAG("AI 标签"),
    EMBEDDING("向量"),
    SCORE("评分"),
}

data class AnalysisProgress(
    val phase: AnalysisPhase,
    val processed: Int,
    val total: Int,
    val msg: String? = null,
)
