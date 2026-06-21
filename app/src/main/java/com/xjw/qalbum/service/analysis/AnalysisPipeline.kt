package com.xjw.qalbum.service.analysis

import com.xjw.qalbum.access.AccessManager
import com.xjw.qalbum.access.ImageBytesReader
import com.xjw.qalbum.data.db.dao.HashQualityWrite
import com.xjw.qalbum.data.db.dao.ImageDao
import com.xjw.qalbum.data.db.dao.OcrDao
import com.xjw.qalbum.data.db.dao.ScoreWrite
import com.xjw.qalbum.data.db.entity.OcrTextEntity
import com.xjw.qalbum.fileops.FileOpsClient
import com.xjw.qalbum.fileops.PrivilegedImageReader
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.data.remote.OpenAiCompatClient
import com.xjw.qalbum.domain.model.AutoFavoriteConfig
import com.xjw.qalbum.service.ai.AiTaggingService
import com.xjw.qalbum.service.ai.AiTaggingShared
import com.xjw.qalbum.service.ai.SemanticIndex
import com.xjw.qalbum.service.ocr.OcrResult
import com.xjw.qalbum.service.ocr.OcrModelStatus
import com.xjw.qalbum.service.ocr.OcrModelStore
import com.xjw.qalbum.service.ocr.PaddleOcrEngine
import kotlinx.coroutines.CancellationException
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
 * 1. HASH      —— 所有活跃图片：xxHash + pHash + 质量（宽高 / 模糊 / 纯色 / 损坏）
 * 2. DUPLICATE —— 全库：xxHash 完全重复 + pHash 视觉相似（仅本轮有新 hash 才重算）
 * 3. SCORE     —— 每张图片：computeDeleteScore / computeInfoScore（读已累积的 OCR 信号）
 * 4. OCR       —— 逐图文字识别（慢）
 * 5. AI_TAG / EMBEDDING —— 需全局 AI 服务可用
 *
 * SCORE 前置于 OCR/AI：评分只依赖 DB 里已累积的结果，故放在慢的 OCR 之前先刷新、不被逐图
 * OCR 阻塞；本轮新产生的 OCR/AI 结果会在下一轮分析的评分中反映（渐进更新，可接受）。
 */
class AnalysisPipeline(
    private val accessManager: AccessManager,
    private val fileOpsClient: FileOpsClient,
    private val imageDao: ImageDao,
    private val ocrDao: OcrDao,
    private val duplicateDetector: DuplicateDetector,
    private val ocrModelStore: OcrModelStore,
    private val ocrEngine: PaddleOcrEngine,
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
            // 整轮分析只绑定一次特权文件服务，HASH/OCR/AI 三阶段读图共用（原生 fd 直读，
            // 绑定失败自动降级 provider 读）。finally 里解绑回收特权进程。
            val reader = PrivilegedImageReader.open(accessManager, fileOpsClient)
            try {
                // 每阶段各自的开关与数量上限；排序方向各阶段共用。每阶段只取「未完成」的前 limit 张，
                // limit<=0 视为无限（分块跑完所有待办）。各 *_done 标记持久化，故天然「从上次增量继续」。
                val config = settings.analysisConfig.first()
                val oldestFirst = settings.analysisOldestFirst.first()
                // 只有本轮确实 hash 了新图，才重算重复聚类（否则跳过，避免「没新 hash 也全量查重」）
                val hashed = runHashAndQuality(config.hashLimit, oldestFirst, reader)
                if (hashed > 0) runDuplicate()
                // 评分前置：只读 DB 里「已累积」的 OCR 信号（hasPhone 等），不依赖本轮 OCR/AI 输出，
                // 放到慢的 OCR 之前先把 info/delete 评分刷新出来，避免被逐图 OCR 阻塞、迟迟不更新。
                runScore()
                if (config.ocrEnabled) runOcr(config.ocrLimit, oldestFirst, reader)
                // AI 标签 / 向量：各自开关 + 全局 AI 服务可用（endpoint 已配）才执行；失败不影响整体
                if (isAiReady()) {
                    if (config.aiTagEnabled) runAiTag(config.aiLimit, oldestFirst, reader)
                    if (config.embeddingEnabled) runEmbedding(config.embeddingLimit, oldestFirst)
                }
                _state.value = AnalysisState.Done
            } catch (ce: CancellationException) {
                // 用户主动取消：交还协程框架，状态由 cancel() 置回 Idle，不展示为失败
                throw ce
            } catch (t: Throwable) {
                Timber.e(t, "AnalysisPipeline failed")
                _state.value = AnalysisState.Failed
                _progress.value = AnalysisProgress(AnalysisPhase.SCORE, 0, 0, t.message ?: "未知错误")
            } finally {
                reader.close()
            }
        }
    }

    fun cancel() {
        job?.cancel(); job = null
        _state.value = AnalysisState.Idle
        _progress.value = null
    }

    /** 仅重新聚类重复（不重新 hash）：重复算法更新或分组异常时手动触发，修复存量分组。 */
    suspend fun reclusterDuplicates() {
        if (_state.value == AnalysisState.Running) return
        _state.value = AnalysisState.Running
        try {
            runDuplicate()
        } catch (t: Throwable) {
            Timber.e(t, "reclusterDuplicates failed")
        } finally {
            _state.value = AnalysisState.Idle
            _progress.value = null
        }
    }

    // ---- 各阶段 ----

    /**
     * HASH + QUALITY 合并阶段：每张图**只读取一次**文件字节（经 [reader] 原生 fd 直读），
     * 随后在内存里同时算出 xxHash / pHash / 宽高 / 模糊度 / 纯色 / 损坏。
     * 块内按 [HASH_CONCURRENCY] 并发，整块结果一次事务写回（[ImageDao.saveHashQualityBatch]）。
     */
    private suspend fun runHashAndQuality(limit: Int, oldestFirst: Boolean, reader: ImageBytesReader): Int {
        val provider = accessManager.current()
        if (!provider.isAvailable()) {
            Timber.w("Hash/Quality phase skipped: access provider not ready")
            return 0
        }

        // 先取待办 id 列表（一次排序、仅 id），再分块装载实体处理：内存恒定、支持「无限」
        val ids = imageDao.pendingHashIds(resolveLimit(limit), oldestFirst)
        if (ids.isEmpty()) {
            _progress.value = AnalysisProgress(AnalysisPhase.HASH, 0, 0, msg = "完成")
            return 0
        }
        val total = ids.size
        val processed = AtomicInteger(0)
        ids.chunked(HASH_CHUNK).forEach { chunkIds ->
            val batch = imageDao.getByIds(chunkIds)
            // 块内并发处理（读图现已是原生 fd 直读，瓶颈转向解码/DCT 的 CPU，故并发按核数）
            val writes = coroutineScope {
                val semaphore = Semaphore(HASH_CONCURRENCY)
                batch.map { img ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            val r = analyzeOne(reader, img.path)
                            val done = processed.incrementAndGet()
                            if (done % PROGRESS_STEP == 0 || done == total) {
                                // StateFlow.value 写入线程安全，可在子协程直接更新
                                _progress.value = AnalysisProgress(AnalysisPhase.HASH, done, total)
                            }
                            HashQualityWrite(
                                img.id, r.xxhash, r.phash, r.blockHash, r.width, r.height, r.blur, r.blank, r.corrupt,
                            )
                        }
                    }
                }.awaitAll()
            }
            // 整块结果一个事务写回（原是每图 2 次独立写 → 40w 事务争抢写锁）
            imageDao.saveHashQualityBatch(writes)
        }
        _progress.value = AnalysisProgress(AnalysisPhase.HASH, processed.get(), total, msg = "完成")
        Timber.i("AnalysisPipeline.runHashAndQuality: analyzed ${processed.get()}/$total images (limit=$limit)")
        return processed.get()
    }

    /** 一次读取字节 → xxHash + pHash + 质量，避免对同一文件重复跨进程读取。 */
    private suspend fun analyzeOne(reader: ImageBytesReader, path: String): HashQualityResult {
        val bytes = reader.read(path, PrivilegedImageReader.MAX_IMAGE_BYTES)
            ?: return HashQualityResult(null, null, null, 0, 0, null, false, true)
        val xxhash = HashEngine.xxHashOf(bytes)
        val bitmap = HashEngine.decodeScaledBytes(bytes, QUALITY_DECODE_SIDE)
            ?: return HashQualityResult(xxhash, null, null, 0, 0, null, false, true)
        val phash = HashEngine.computePHashFromBitmap(bitmap)
        val blockHash = HashEngine.computeBlockHashFromBitmap(bitmap) // 复用已解码 bitmap，零额外解码
        val q = QualityAnalyzer.analyzeBitmap(bitmap)
        bitmap.recycle()
        return HashQualityResult(xxhash, phash, blockHash, q.width, q.height, q.blurScore, q.isBlank, q.isCorrupt)
    }

    private data class HashQualityResult(
        val xxhash: String?,
        val phash: Long?,
        val blockHash: ByteArray?,
        val width: Int,
        val height: Int,
        val blur: Float?,
        val blank: Boolean,
        val corrupt: Boolean,
    )

    private suspend fun runDuplicate() {
        _progress.value = AnalysisProgress(AnalysisPhase.DUPLICATE, 0, 0, msg = "聚类中…")
        imageDao.clearDuplicateAssignments()
        duplicateDetector.recluster(
            settings.maxDuplicateGroupSize.first(),
            settings.duplicateStrictness.first(),
        )
        _progress.value = AnalysisProgress(AnalysisPhase.DUPLICATE, 1, 1, msg = "完成")
    }

    private suspend fun runOcr(limit: Int, oldestFirst: Boolean, reader: ImageBytesReader) {
        if (ocrModelStore.currentStatus() !is OcrModelStatus.Ready) {
            _progress.value = AnalysisProgress(AnalysisPhase.OCR, 0, 0, msg = "未安装 OCR 模型")
            Timber.i("AnalysisPipeline.runOcr: skipped because OCR model is not installed")
            return
        }
        val provider = accessManager.current()
        if (!provider.isAvailable()) return

        val ids = imageDao.pendingOcrIds(resolveLimit(limit), oldestFirst)
        if (ids.isEmpty()) {
            _progress.value = AnalysisProgress(AnalysisPhase.OCR, 0, 0, msg = "完成")
            return
        }
        val total = ids.size
        var processed = 0
        ids.chunked(OCR_CHUNK).forEach { chunkIds ->
            for (img in imageDao.getByIds(chunkIds)) {
                // 走原生 fd 直读拿字节，再交给 OCR 引擎；不可读 → 空字节 → 引擎回 Empty 并标记完成
                val bytes = reader.read(img.path, PrivilegedImageReader.MAX_IMAGE_BYTES) ?: ByteArray(0)
                val result = ocrEngine.recognize(bytes)
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
                    OcrResult.Unavailable -> {
                        Timber.i("OCR skipped: model is not installed")
                        _progress.value = AnalysisProgress(AnalysisPhase.OCR, processed, total, msg = "未安装 OCR 模型")
                        return
                    }
                }
                imageDao.markOcrDone(img.id)
                processed++
                if (processed % 5 == 0 || processed == total) {
                    _progress.value = AnalysisProgress(AnalysisPhase.OCR, processed, total)
                }
            }
        }
        _progress.value = AnalysisProgress(AnalysisPhase.OCR, processed, total, msg = "完成")
        Timber.i("AnalysisPipeline.runOcr: OCR-ed $processed/$total images (limit=$limit)")
    }

    private suspend fun isAiReady(): Boolean {
        val enabled = settings.aiEnabled.first()
        val endpoint = settings.aiEndpoint.first()
        // 本地服务通常免鉴权，故不再强制要求 API Key，仅需启用 + 填好 endpoint
        return enabled && endpoint.isNotBlank()
    }

    private suspend fun runAiTag(limit: Int, oldestFirst: Boolean, reader: ImageBytesReader) {
        val provider = accessManager.current()
        if (!provider.isAvailable()) return

        val ids = imageDao.pendingAiTagIds(resolveLimit(limit), oldestFirst)
        if (ids.isEmpty()) {
            _progress.value = AnalysisProgress(AnalysisPhase.AI_TAG, 0, 0, msg = "完成")
            return
        }
        val total = ids.size
        // 按用户配置的并发数同时发起请求；本地推理服务多为串行队列，过高并发收益有限，由用户自行权衡
        val concurrency = settings.aiConcurrency.first().coerceAtLeast(1)
        val processed = AtomicInteger(0)
        // 用固定 id 列表分块处理：失败图不标记完成、但本次运行内不再重复（下次分析再重试），避免对失败图死循环
        ids.chunked(AI_CHUNK).forEach { chunkIds ->
            coroutineScope {
                val semaphore = Semaphore(concurrency)
                imageDao.getByIds(chunkIds).map { img ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            val result = aiTagging.tagImage(reader, img.id)
                            if (result is AiTaggingService.Result.Success) {
                                imageDao.update(img.copy(aiDone = true))
                            }
                            val done = processed.incrementAndGet()
                            _progress.value = AnalysisProgress(AnalysisPhase.AI_TAG, done, total)
                        }
                    }
                }.awaitAll()
            }
        }
        _progress.value = AnalysisProgress(AnalysisPhase.AI_TAG, processed.get(), total, msg = "完成")
        Timber.i("AnalysisPipeline.runAiTag: tagged ${processed.get()}/$total images (limit=$limit, concurrency=$concurrency)")
    }

    private suspend fun runEmbedding(limit: Int, oldestFirst: Boolean) {
        val provider = accessManager.current()
        if (!provider.isAvailable()) return

        val ids = imageDao.pendingEmbeddingIds(resolveLimit(limit), oldestFirst)
        if (ids.isEmpty()) {
            _progress.value = AnalysisProgress(AnalysisPhase.EMBEDDING, 0, 0, msg = "完成")
            return
        }
        val total = ids.size
        val concurrency = settings.aiConcurrency.first().coerceAtLeast(1)
        val processed = AtomicInteger(0)
        val embModel = settings.aiEmbeddingModel.first()
        ids.chunked(EMBEDDING_CHUNK).forEach { chunkIds ->
            coroutineScope {
                val semaphore = Semaphore(concurrency)
                imageDao.getByIds(chunkIds).map { img ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            embedOne(img, embModel)
                            val done = processed.incrementAndGet()
                            _progress.value = AnalysisProgress(AnalysisPhase.EMBEDDING, done, total)
                        }
                    }
                }.awaitAll()
            }
        }
        _progress.value = AnalysisProgress(AnalysisPhase.EMBEDDING, processed.get(), total, msg = "完成")
        Timber.i("AnalysisPipeline.runEmbedding: embedded ${processed.get()}/$total images (limit=$limit, concurrency=$concurrency)")
    }

    /** 单张图片的向量化：已存在则直接标记完成；用 OCR 文本 + 文件名等作为 embedding 输入 */
    private suspend fun embedOne(img: com.xjw.qalbum.data.db.entity.ImageEntity, embModel: String) {
        if (semanticIndex.hasEmbedding(img.id)) {
            imageDao.update(img.copy(embeddingDone = true))
            return
        }
        val ocr = ocrDao.getByImageId(img.id)
        val textForEmbedding = buildEmbeddingText(img, ocr)
        if (textForEmbedding.isBlank()) {
            // 无可嵌入文本：标记完成，避免反复占用名额
            imageDao.update(img.copy(embeddingDone = true))
            return
        }
        val vec = aiClient.embedding(textForEmbedding)
        if (vec != null) {
            semanticIndex.upsert(img.id, embModel, vec)
            imageDao.update(img.copy(embeddingDone = true))
        }
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
        // 自动收藏改为可选特性：仅当用户在设置中开启后，按其自定义规则命中才收藏
        val autoFav = settings.autoFavoriteConfig.first()
        var processed = 0
        var afterId = 0L
        while (true) {
            val page = imageDao.pagePrimaryHashed(afterId, SCORE_BATCH)
            if (page.isEmpty()) break
            // 整页一次取 OCR（评分要读 hasPhone/hasAmount 等强信号），避免逐图 N+1 单查
            val ocrMap = ocrDao.getByImageIds(page.map { it.id }).associateBy { it.imageId }
            val writes = ArrayList<ScoreWrite>(page.size)
            for (img in page) {
                val ocr = ocrMap[img.id]
                val info = ScoringEngine.computeInfoScore(img, ocr)
                val del = ScoringEngine.computeDeleteScore(img, ocr)
                writes.add(ScoreWrite(img.id, info, del))
                // 自动收藏（默认关闭）：开启且命中用户勾选的敏感信息类型时，加入收藏避免误删
                if (autoFav.enabled && ocr != null && matchesAutoFavorite(autoFav, ocr)) {
                    imageDao.setFavorite(img.id, true)
                }
                afterId = img.id
            }
            // 整页评分一个事务写回（原是每图 2 次独立写）
            imageDao.saveScoresBatch(writes)
            processed += page.size
            _progress.value = AnalysisProgress(AnalysisPhase.SCORE, processed, processed)
        }
        _progress.value = AnalysisProgress(AnalysisPhase.SCORE, processed, processed, msg = "完成")
        Timber.i("AnalysisPipeline.runScore: scored $processed primary images")
    }

    /** 判断一条 OCR 结果是否命中用户启用的任一自动收藏规则 */
    private fun matchesAutoFavorite(config: AutoFavoriteConfig, ocr: OcrTextEntity): Boolean =
        (config.idNumber && ocr.hasIdNumber) ||
            (config.orderNo && ocr.hasOrderNo) ||
            (config.amount && ocr.hasAmount) ||
            (config.phone && ocr.hasPhone) ||
            (config.address && ocr.hasAddress)

    /** 把用户数量上限解析为实际处理数：`<=0`（无限）→ Int.MAX_VALUE（SQLite LIMIT 视作不设上限） */
    private fun resolveLimit(limit: Int): Int = if (limit <= 0) Int.MAX_VALUE else limit

    companion object {
        private const val SCORE_BATCH = 500

        /**
         * hash+质量 合并阶段的块内并发度。原生 fd 直读后读不再是瓶颈、转为解码/DCT 的 CPU 开销，
         * 故按 CPU 核数取值；上限 8 防止过多并发 768px 解码 bitmap 造成内存抖动。
         */
        private val HASH_CONCURRENCY = Runtime.getRuntime().availableProcessors().coerceIn(4, 8)

        /** 质量 / pHash 的解码目标边长（pHash 再缩到 32，质量在此尺寸上评估） */
        private const val QUALITY_DECODE_SIDE = 768

        /** 进度上报步长 */
        private const val PROGRESS_STEP = 16

        // 各阶段每次装载进内存的实体块大小（id 列表分块 → getByIds）：bound 工作集内存，支持「无限」
        // 均 < SQLite IN 变量上限；hash/ocr 解码重，取小块；ai/向量受网络与并发信号量约束。
        private const val HASH_CHUNK = 256
        private const val OCR_CHUNK = 200
        private const val AI_CHUNK = 64
        private const val EMBEDDING_CHUNK = 64
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
