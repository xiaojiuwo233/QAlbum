package com.xjw.qalbum.service.analysis

import android.os.RemoteException
import androidx.room.withTransaction
import com.xjw.qalbum.data.db.AppDatabase
import com.xjw.qalbum.access.AccessException
import com.xjw.qalbum.access.AccessManager
import com.xjw.qalbum.access.ImageBytesReader
import com.xjw.qalbum.data.db.dao.HashQualityWrite
import com.xjw.qalbum.data.db.dao.ImageDao
import com.xjw.qalbum.data.db.dao.OcrDao
import com.xjw.qalbum.data.db.dao.OcrWrite
import com.xjw.qalbum.data.db.dao.ScoreWrite
import com.xjw.qalbum.data.db.entity.OcrTextEntity
import com.xjw.qalbum.fileops.FileOpsClient
import com.xjw.qalbum.fileops.PrivilegedImageReader
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.data.remote.OpenAiCompatClient
import com.xjw.qalbum.domain.model.AutoFavoriteConfig
import com.xjw.qalbum.service.ai.AiTaggingService
import com.xjw.qalbum.service.ai.SemanticIndex
import com.xjw.qalbum.service.ai.SemanticDocument
import com.xjw.qalbum.service.ocr.OcrResult
import com.xjw.qalbum.service.ocr.OcrModelStatus
import com.xjw.qalbum.service.ocr.OcrModelStore
import com.xjw.qalbum.service.ocr.PaddleOcrEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

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
 * OCR 阻塞；OCR 每批完成后立即定向刷新该批评分与自动收藏。
 *
 * 承载与生命周期：数小时级长任务应经 [com.xjw.qalbum.worker.AnalysisWorker]（前台通知保活）
 * 直接 suspend 调 [runOnce]，取消沿调用链传导；[start]/[cancel] 是进程内 scope 的兼容入口。
 * 单飞由 [runGate] 原子保证——任何入口并发触发都只有一条管线在跑。
 *
 * 失败语义：单图解码 / 读取异常只跳过该图（不落 corrupt、不否决整块写回）；读取基础设施失败
 * （[AccessException] / [RemoteException]，文件可读性未知）连续超限才中止整轮置 Failed。
 */
class AnalysisPipeline(
    private val accessManager: AccessManager,
    private val fileOpsClient: FileOpsClient,
    private val db: AppDatabase,
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

    /** [start]/[reclusterDuplicates] 在进程内 scope 启动的运行；Worker 直接调 [runOnce] 不经此字段。 */
    private var job: Job? = null

    /** 单飞门闩：检查与置位原子，双击 / Worker 与手动并发触发只放行一条管线。 */
    private val runGate = AtomicBoolean(false)

    /**
     * 进程内启动（UI 兼容入口；保活的正路是 AnalysisWorker）。协程取消是协作式的，纯 CPU 段
     * （解码 / DCT / 聚类）要到下一个挂起点才真正停：新一轮先 join 掉被取消的旧轮再开跑，
     * 避免双管线并发（两份特权绑定、同批图重复处理、进度互相覆盖）。
     */
    @Synchronized
    fun start() {
        val old = job
        if (old?.isActive == true && !old.isCancelled) {
            Timber.w("AnalysisPipeline: already running")
            return
        }
        job = scope.launch {
            old?.cancelAndJoin()
            runOnce()
        }
    }

    @Synchronized
    fun cancel() {
        val old = job
        old?.cancel()
        // 仅当被取消的确实是进程内活跃运行才立即清状态（Worker 驱动的运行不经 job 字段，
        // 其取消走 WorkManager，这里不能抢先把 Running 清成 Idle）；
        // 旧轮残余子协程的进度回写由 runOnce 的取消收尾在子协程全部退出后兜底清空
        if (old?.isActive == true) {
            _state.value = AnalysisState.Idle
            _progress.value = null
        }
    }

    /**
     * 同步驱动一整轮分析，挂起直至终态并返回之。[com.xjw.qalbum.worker.AnalysisWorker] 在
     * dataSync 前台通知下直接调用，WorkManager 停止 Worker 时取消传导进各阶段。
     * 已有一轮在跑时不启动第二条管线，返回 null。
     */
    suspend fun runOnce(): AnalysisState? {
        if (!runGate.compareAndSet(false, true)) {
            Timber.w("AnalysisPipeline: already running, skip duplicate trigger")
            return null
        }
        _state.value = AnalysisState.Running
        _progress.value = null
        var reader: PrivilegedImageReader? = null
        try {
            // open 也必须位于本 try 内：绑定期间取消/异常仍要释放 runGate 并把 state 退出 Running。
            val activeReader = PrivilegedImageReader.open(accessManager, fileOpsClient)
            reader = activeReader
            // 每阶段各自的开关与数量上限；排序方向各阶段共用。每阶段只取「未完成」的前 limit 张，
            // limit<=0 视为无限（分页循环跑完所有待办）。各 *_done 标记持久化，故天然「从上次增量继续」。
            val config = settings.analysisConfig.first()
            val oldestFirst = settings.analysisOldestFirst.first()
            // 新指纹或算法升级才重算；升级后的存量分组也必须应用新规则。
            val hashed = runHashAndQuality(config.hashLimit, oldestFirst, activeReader)
            if (hashed > 0 || settings.duplicateAlgorithmVersion.first() != DuplicateDetector.ALGORITHM_VERSION) runDuplicate()
            // 评分前置：只读 DB 里「已累积」的 OCR 信号（hasPhone 等），不依赖本轮 OCR/AI 输出，
            // 放到慢的 OCR 之前先把 info/delete 评分刷新出来，避免被逐图 OCR 阻塞、迟迟不更新。
            runScore()
            if (config.ocrEnabled) runOcr(config.ocrLimit, oldestFirst, activeReader)
            // AI 标签 / 向量：各自开关 + 全局 AI 服务可用（endpoint 已配）才执行；失败不影响整体
            if (isAiReady()) {
                if (config.aiTagEnabled) runAiTag(config.aiLimit, oldestFirst, activeReader)
                if (config.embeddingEnabled) runEmbedding(config.embeddingLimit, oldestFirst)
            }
            _state.value = AnalysisState.Done
            return AnalysisState.Done
        } catch (ce: CancellationException) {
            // 取消：此时各阶段 coroutineScope 的子协程已全部退出，这里收尾不会再被幽灵进度覆盖
            _state.value = AnalysisState.Idle
            _progress.value = null
            throw ce
        } catch (t: Throwable) {
            Timber.e(t, "AnalysisPipeline failed")
            _state.value = AnalysisState.Failed
            _progress.value = _progress.value?.copy(msg = t.message ?: "未知错误")
                ?: AnalysisProgress(AnalysisPhase.HASH, 0, 0, t.message ?: "未知错误")
            return AnalysisState.Failed
        } finally {
            try {
                reader?.close()
            } finally {
                runGate.set(false)
            }
        }
    }

    /**
     * 仅重新聚类重复（不重新 hash）：重复算法更新或分组异常时手动触发，修复存量分组。
     * 在 pipeline 自有 scope 执行——手动入口由 ViewModel 触发时，离开页面 / ViewModel 销毁
     * 不再把聚类拦腰取消（写回在 DuplicateDetector 内与清空同一事务，取消也不会丢分组）。
     */
    @Synchronized
    fun reclusterDuplicates() {
        val old = job
        if (old?.isActive == true && !old.isCancelled) return
        job = scope.launch {
            old?.cancelAndJoin()
            if (!runGate.compareAndSet(false, true)) return@launch
            _state.value = AnalysisState.Running
            try {
                runDuplicate()
                _state.value = AnalysisState.Idle
            } catch (ce: CancellationException) {
                _state.value = AnalysisState.Idle
                throw ce
            } catch (t: Throwable) {
                Timber.e(t, "reclusterDuplicates failed")
                _state.value = AnalysisState.Idle
            } finally {
                _progress.value = null
                runGate.set(false)
            }
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

        val bounded = limit > 0
        val pendingAtStart = imageDao.countPendingHash()
        val total = if (bounded) minOf(limit, pendingAtStart) else pendingAtStart
        var completed = 0
        var attempted = 0
        val infraStreak = AtomicInteger(0)
        var failed = 0
        var afterMtime = if (oldestFirst) Long.MIN_VALUE else Long.MAX_VALUE
        var afterId = if (oldestFirst) Long.MIN_VALUE else Long.MAX_VALUE
        var rounds = 0
        while (!bounded || completed < limit) {
            val pageLimit = if (bounded) minOf(UNLIMITED_PAGE, limit - completed) else UNLIMITED_PAGE
            val fetched = imageDao.pendingHashPage(pageLimit, oldestFirst, afterMtime, afterId)
            if (fetched.isEmpty()) break
            afterMtime = fetched.last().mtime
            afterId = fetched.last().id
            val ids = fetched.map { it.id }
            ids.chunked(HASH_CHUNK).forEach { chunkIds ->
                val batch = imageDao.getByIds(chunkIds)
                // 块内并发处理（读图现已是原生 fd 直读，瓶颈转向解码/DCT 的 CPU，故并发按核数）
                val results = coroutineScope {
                    val semaphore = Semaphore(HASH_CONCURRENCY)
                    batch.map { img ->
                        async(Dispatchers.IO) {
                            semaphore.withPermit {
                                val r = analyzeOneIsolated(reader, img.path, img.size, infraStreak)
                                if (r == null) img.id to null
                                else img.id to HashQualityWrite(
                                    img.id, r.xxhash, r.phash, r.blockHash, r.featureGrid,
                                    r.width, r.height, r.blur, r.blank, r.corrupt,
                                    expectedSize = img.size, expectedMtime = img.mtime,
                                )
                            }
                        }
                    }.awaitAll()
                }
                val writes = results.mapNotNull { it.second }
                attempted += results.size
                failed += results.size - writes.size
                // 整块结果一个事务写回（原是每图 2 次独立写 → 40w 事务争抢写锁）
                if (writes.isNotEmpty()) imageDao.saveHashQualityBatch(writes)
                completed += writes.size
                _progress.value = AnalysisProgress(AnalysisPhase.HASH, completed, maxOf(total, completed))
            }
            if (++rounds > MAX_PENDING_ROUNDS) break
        }
        _progress.value = AnalysisProgress(AnalysisPhase.HASH, completed, maxOf(total, completed), msg = "完成")
        Timber.i("AnalysisPipeline.runHashAndQuality: completed $completed/$attempted attempts (limit=$limit, failed=$failed)")
        return completed
    }

    /**
     * 逐图隔离的 [analyzeOne]：单图解码 OOM / 运行时异常只跳过本图（返回 null、不落任何库），
     * 不再让整块 awaitAll 上抛把整轮置 Failed——pending 顺序确定，否则每轮都会死在同一块。
     * 跳过图**不写 corrupt**：内存压力下的瞬时 OOM 会把健康图误标损坏进而被清理推荐误删。
     */
    private suspend fun analyzeOneIsolated(
        reader: ImageBytesReader,
        path: String,
        expectedSize: Long,
        infraStreak: AtomicInteger,
    ): HashQualityResult? {
        return try {
            analyzeOne(reader, path, expectedSize).also { infraStreak.set(0) }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: AccessException) {
            onInfraFailure(e, path, infraStreak); null
        } catch (e: RemoteException) {
            onInfraFailure(e, path, infraStreak); null
        } catch (t: Throwable) {
            Timber.w(t, "analyzeOne failed, skip this image: $path")
            null
        }
    }

    /**
     * 读取基础设施失败（Shizuku/Root 整体不可用，文件可读性未知）：本图跳过且严禁落 corrupt /
     * 空 OCR 记录；连续 [INFRA_ABORT_STREAK] 张即中止本轮——继续跑只会把剩余图白读一遍。
     */
    private fun onInfraFailure(cause: Exception, path: String, streak: AtomicInteger) {
        val n = streak.incrementAndGet()
        Timber.w(cause, "读取基础设施失败（连续 $n）：$path")
        if (n >= INFRA_ABORT_STREAK) {
            throw AnalysisInfraException("读取通道连续失败（Shizuku/Root 不可用？），本轮已中止", cause)
        }
    }

    private class AnalysisInfraException(message: String, cause: Throwable?) : RuntimeException(message, cause)

    /** 一次读取字节 → xxHash + pHash + 质量，避免对同一文件重复跨进程读取。 */
    private suspend fun analyzeOne(reader: ImageBytesReader, path: String, expectedSize: Long): HashQualityResult {
        val bytes = reader.read(path, PrivilegedImageReader.MAX_IMAGE_BYTES)
            ?: throw java.io.IOException("图片暂不可读")
        check(bytes.size.toLong() == expectedSize) { "文件读取不完整或分析期间已变化" }
        val xxhash = HashEngine.xxHashOf(bytes)
        // EXIF 方向归一 + 原图尺寸：视觉特征在显示方向像素上算（旋转副本才可能聚上）；
        // 宽高写原图 bounds（旋转档位换边）而非 768px 采样解码尺寸——采样尺寸入库会让全库
        // 分辨率信号失真（低分辨率误判清理、重复组 best 选错方向）。
        val decoded = HashEngine.decodeScaledOriented(bytes, QUALITY_DECODE_SIDE)
            ?: return HashQualityResult(xxhash, null, null, null, 0, 0, null, false, true)
        val bitmap = decoded.bitmap
        try {
            val phash = HashEngine.computePHashFromBitmap(bitmap)
            val blockHash = HashEngine.computeBlockHashFromBitmap(bitmap)      // 复用已解码 bitmap，零额外解码
            val featureGrid = HashEngine.computeFeatureGridFromBitmap(bitmap)  // 同上，仅多一次 32×32 缩放
            val q = QualityAnalyzer.analyzeBitmap(bitmap)
            return HashQualityResult(
                xxhash, phash, blockHash, featureGrid,
                decoded.sourceWidth, decoded.sourceHeight, q.blurScore, q.isBlank, q.isCorrupt,
            )
        } finally {
            bitmap.recycle()
        }
    }

    private data class HashQualityResult(
        val xxhash: String?,
        val phash: Long?,
        val blockHash: ByteArray?,
        val featureGrid: ByteArray?,
        val width: Int,
        val height: Int,
        val blur: Float?,
        val blank: Boolean,
        val corrupt: Boolean,
    )

    private suspend fun runDuplicate() {
        _progress.value = AnalysisProgress(AnalysisPhase.DUPLICATE, 0, 0, msg = "聚类中…")
        val strictness = settings.duplicateStrictness.first()
        val weights = settings.similarityWeights.first()
        // 学习模型（用户反馈调过的权重）优先；未学习则用严格度档位的基础模型
        val model = weights?.let { SimilarityModel(it[0], it[1], it[2], it[3]) }
            ?: SimilarityModel.DEFAULT.copy(threshold = strictness.scoreThreshold)
        // 旧分配的清空在 DuplicateDetector 的写回事务内完成（清空 + 写新原子）：
        // 聚类分钟级窗口内取消 / 崩溃不会把全库分组留在「已清空未写回」状态
        duplicateDetector.recluster(settings.maxDuplicateGroupSize.first(), strictness, model)
        settings.setDuplicateAlgorithmVersion(DuplicateDetector.ALGORITHM_VERSION)
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

        val bounded = limit > 0
        var total = if (bounded) limit else 0
        var completed = 0
        var attempted = 0
        var infraStreak = 0
        var failStreak = 0
        var failed = 0
        var afterMtime = if (oldestFirst) Long.MIN_VALUE else Long.MAX_VALUE
        var afterId = if (oldestFirst) Long.MIN_VALUE else Long.MAX_VALUE
        var rounds = 0
        while (!bounded || completed < limit) {
            val pageLimit = if (bounded) minOf(UNLIMITED_PAGE, limit - completed) else UNLIMITED_PAGE
            val fetched = imageDao.pendingOcrPage(pageLimit, oldestFirst, afterMtime, afterId)
            if (fetched.isEmpty()) break
            afterMtime = fetched.last().mtime
            afterId = fetched.last().id
            if (!bounded) total += fetched.size
            val ids = fetched.map { it.id }
            for (chunkIds in ids.chunked(OCR_CHUNK)) {
                // 整块结果一个事务写回（saveOcrResultsBatch 内逐条过 markOcrDoneGuarded 乐观校验：
                // 文件在 OCR 期间被重写的条目连同文本一起丢弃，留待下轮重跑）
                val writes = ArrayList<OcrWrite>(chunkIds.size)
                for (img in imageDao.getByIds(chunkIds)) {
                    attempted++
                    // 不可读的文件留待下轮重试，不能当成「确认无文字」写空 OCR。
                    // 抛 AccessException/RemoteException = 基础设施失败：本图不落任何记录（下轮重试），
                    // 连续超限中止本阶段（先保住已完成结果）。
                    val bytes = try {
                        (reader.read(img.path, PrivilegedImageReader.MAX_IMAGE_BYTES) ?: continue)
                            .also { infraStreak = 0 }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (e: Exception) {
                        if (e !is AccessException && e !is RemoteException) throw e
                        failed++
                        Timber.w(e, "读取基础设施失败（连续 ${infraStreak + 1}）：${img.path}")
                        if (++infraStreak >= INFRA_ABORT_STREAK) {
                            saveOcrAndRefreshScores(writes)
                            throw AnalysisInfraException("读取通道连续失败（Shizuku/Root 不可用？），OCR 已中止", e)
                        }
                        continue
                    }
                    val result = ocrEngine.recognize(bytes)
                    val now = System.currentTimeMillis()
                    when (result) {
                        is OcrResult.Success -> {
                            failStreak = 0
                            writes += OcrWrite(
                                imageId = img.id,
                                expectedSize = img.size,
                                expectedMtime = img.mtime,
                                text = OcrTextEntity(
                                    imageId = img.id,
                                    text = result.text,
                                    confidence = result.confidence,
                                    hasPhone = result.hasPhone,
                                    hasAmount = result.hasAmount,
                                    hasAddress = result.hasAddress,
                                    hasIdNumber = result.hasIdNumber,
                                    hasOrderNo = result.hasOrderNo,
                                    updatedAt = now,
                                ),
                            )
                        }
                        OcrResult.Empty -> {
                            failStreak = 0
                            // 写一条空文本记录，避免 pendingOcr 反复返回
                            writes += OcrWrite(
                                imageId = img.id,
                                expectedSize = img.size,
                                expectedMtime = img.mtime,
                                text = OcrTextEntity(
                                    imageId = img.id,
                                    text = "",
                                    confidence = 0f,
                                    updatedAt = now,
                                ),
                            )
                        }
                        is OcrResult.Failed -> {
                            // 瞬时识别失败不标完成（否则这些图永久失去 OCR 文本且无重试路径）：
                            // keyset 游标已越过本图，下轮分析再重试；连续大量失败
                            // 说明引擎坏了，中止本阶段而不是把全库图逐张喂给坏引擎。
                            Timber.w("OCR failed for ${img.path}: ${result.reason}")
                            failed++
                            if (++failStreak >= OCR_FAIL_ABORT_STREAK) {
                                saveOcrAndRefreshScores(writes)
                                completed += writes.size
                                _progress.value = AnalysisProgress(AnalysisPhase.OCR, completed, maxOf(total, completed), msg = "OCR 引擎连续失败，已中止")
                                Timber.e("AnalysisPipeline.runOcr: aborted after $failStreak consecutive failures")
                                throw AnalysisInfraException("OCR 引擎连续失败，已保留完成结果", null)
                            }
                        }
                        OcrResult.Unavailable -> {
                            Timber.i("OCR skipped: model is not installed")
                            saveOcrAndRefreshScores(writes)
                            completed += writes.size
                            _progress.value = AnalysisProgress(AnalysisPhase.OCR, completed, maxOf(total, completed), msg = "OCR 引擎不可用")
                            return
                        }
                    }
                }
                if (writes.isNotEmpty()) {
                    saveOcrAndRefreshScores(writes)
                    completed += writes.size
                    _progress.value = AnalysisProgress(AnalysisPhase.OCR, completed, maxOf(total, completed))
                }
            }
            if (++rounds > MAX_PENDING_ROUNDS) break
        }
        _progress.value = AnalysisProgress(AnalysisPhase.OCR, completed, maxOf(total, completed), msg = "完成")
        Timber.i("AnalysisPipeline.runOcr: completed $completed/$attempted attempts (limit=$limit, failed=$failed)")
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

        // 按用户配置的并发数同时发起请求；本地推理服务多为串行队列，过高并发收益有限，由用户自行权衡
        val concurrency = settings.aiConcurrency.first().coerceAtLeast(1)
        val bounded = limit > 0
        var total = if (bounded) limit else 0
        var completed = 0
        var attempted = 0
        var failed = 0
        val infraStreak = AtomicInteger(0)
        val failStreak = AtomicInteger(0)
        var afterMtime = if (oldestFirst) Long.MIN_VALUE else Long.MAX_VALUE
        var afterId = if (oldestFirst) Long.MIN_VALUE else Long.MAX_VALUE
        var rounds = 0
        while (!bounded || completed < limit) {
            val pageLimit = if (bounded) minOf(UNLIMITED_PAGE, limit - completed) else UNLIMITED_PAGE
            val fetched = imageDao.pendingAiTagPage(pageLimit, oldestFirst, afterMtime, afterId)
            if (fetched.isEmpty()) break
            afterMtime = fetched.last().mtime
            afterId = fetched.last().id
            if (!bounded) total += fetched.size
            val ids = fetched.map { it.id }
            for (chunkIds in ids.chunked(AI_CHUNK)) {
                val results = coroutineScope {
                    val semaphore = Semaphore(concurrency)
                    imageDao.getByIds(chunkIds).map { img ->
                        async(Dispatchers.IO) {
                            semaphore.withPermit {
                                tagOneIsolated(reader, img, infraStreak, failStreak)
                            }
                        }
                    }.awaitAll()
                }
                attempted += results.size
                val successes = results.count { it }
                completed += successes
                failed += results.size - successes
                _progress.value = AnalysisProgress(AnalysisPhase.AI_TAG, completed, maxOf(total, completed))
            }
            if (++rounds > MAX_PENDING_ROUNDS) break
        }
        _progress.value = AnalysisProgress(AnalysisPhase.AI_TAG, completed, maxOf(total, completed), msg = "完成")
        Timber.i("AnalysisPipeline.runAiTag: completed $completed/$attempted attempts (limit=$limit, concurrency=$concurrency, failed=$failed)")
    }

    private suspend fun tagOneIsolated(
        reader: ImageBytesReader,
        img: com.xjw.qalbum.data.db.entity.ImageEntity,
        infraStreak: AtomicInteger,
        failStreak: AtomicInteger,
    ): Boolean {
        val result = try {
            aiTagging.tagImage(reader, img.id)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: AccessException) {
            onInfraFailure(e, img.path, infraStreak)
            return false
        } catch (e: RemoteException) {
            onInfraFailure(e, img.path, infraStreak)
            return false
        } catch (t: Throwable) {
            Timber.w(t, "AI tag failed, skip this image: ${img.path}")
            return false
        }
        if (result is AiTaggingService.Result.Success) {
            infraStreak.set(0)
            failStreak.set(0)
            // 标签、描述及完成标记由服务在同一守卫事务中提交。
            return true
        } else {
            // 端点不可达时每张都失败：连续超限中止本阶段，而不是把全库 pending 逐张白打一遍网络
            if (failStreak.incrementAndGet() >= AI_FAIL_ABORT_STREAK) {
                throw AnalysisInfraException("AI 服务连续失败（endpoint 不可达？），打标已中止", null)
            }
            return false
        }
    }

    private suspend fun runEmbedding(limit: Int, oldestFirst: Boolean) {
        val provider = accessManager.current()
        if (!provider.isAvailable()) return

        val concurrency = settings.aiConcurrency.first().coerceAtLeast(1)
        val cfg = settings.aiConnection.first()
        val embModel = SemanticDocument.modelKey(cfg.endpoint, cfg.embeddingModel)
        val bounded = limit > 0
        var total = if (bounded) limit else 0
        var completed = 0
        var attempted = 0
        var failed = 0
        val failStreak = AtomicInteger(0)
        var afterMtime = if (oldestFirst) Long.MIN_VALUE else Long.MAX_VALUE
        var afterId = if (oldestFirst) Long.MIN_VALUE else Long.MAX_VALUE
        var rounds = 0
        while (!bounded || completed < limit) {
            val pageLimit = if (bounded) minOf(UNLIMITED_PAGE, limit - completed) else UNLIMITED_PAGE
            val fetched = imageDao.pendingEmbeddingPage(pageLimit, oldestFirst, afterMtime, afterId, embModel)
            if (fetched.isEmpty()) break
            afterMtime = fetched.last().mtime
            afterId = fetched.last().id
            if (!bounded) total += fetched.size
            val ids = fetched.map { it.id }
            for (chunkIds in ids.chunked(EMBEDDING_CHUNK)) {
                val results = coroutineScope {
                    val semaphore = Semaphore(concurrency)
                    imageDao.getByIds(chunkIds).map { img ->
                        async(Dispatchers.IO) {
                            semaphore.withPermit {
                                val ok = try {
                                    embedOne(img, embModel, cfg.embeddingModel, cfg.endpoint)
                                } catch (ce: CancellationException) {
                                    throw ce
                                } catch (t: Throwable) {
                                    Timber.w(t, "embedding failed, skip this image: ${img.path}")
                                    false
                                }
                                if (ok) {
                                    failStreak.set(0)
                                } else {
                                    if (failStreak.incrementAndGet() >= AI_FAIL_ABORT_STREAK) {
                                        throw AnalysisInfraException("向量服务连续失败（endpoint 不可达？），已中止", null)
                                    }
                                }
                                ok
                            }
                        }
                    }.awaitAll()
                }
                attempted += results.size
                val successes = results.count { it }
                completed += successes
                failed += results.size - successes
                _progress.value = AnalysisProgress(AnalysisPhase.EMBEDDING, completed, maxOf(total, completed))
            }
            if (++rounds > MAX_PENDING_ROUNDS) break
        }
        _progress.value = AnalysisProgress(AnalysisPhase.EMBEDDING, completed, maxOf(total, completed), msg = "完成")
        Timber.i("AnalysisPipeline.runEmbedding: completed $completed/$attempted attempts (limit=$limit, concurrency=$concurrency, failed=$failed)")
    }

    /**
     * 单张图片的向量化：输入来自图片描述、标签和 OCR；无内容时不请求向量服务。
     * 返回是否已推进（写向量或标记完成）；false = 本图失败，本轮不再重试。
     * 完成标记用定向 UPDATE（[ImageDao.markEmbeddingDone]），不整行回写陈旧快照。
     */
    private suspend fun embedOne(
        img: com.xjw.qalbum.data.db.entity.ImageEntity, modelKey: String, model: String, endpoint: String,
    ): Boolean {
        val text = db.withTransaction { buildEmbeddingText(img.id) }
        val vec = if (text.isNotBlank()) aiClient.embedding(text, model, endpoint) ?: return false else null
        return db.withTransaction {
            // 网络期间 OCR/AI 或文件内容可能更新，拒绝把旧文档的向量标为已完成。
            if (buildEmbeddingText(img.id) != text) return@withTransaction false
            if (imageDao.markEmbeddingDone(img.id, img.size, img.mtime) != 1) return@withTransaction false
            if (vec != null) semanticIndex.upsert(img.id, modelKey, vec)
            else db.embeddingDao().deleteByImageId(img.id)
            true
        }
    }

    private suspend fun buildEmbeddingText(imageId: Long): String = SemanticDocument.build(
        ocrDao.getByImageId(imageId)?.text,
        db.aiAnalysisDao().getByImageId(imageId),
        db.tagDao().getTagsForImage(imageId).map { it.name },
    )

    private suspend fun runScore() {
        // 全库遍历主图，重新计算 info_score / delete_score；**只有分数变化的行才写回**（脏检查）：
        // 此前每轮无条件重写全部 hash_done 行，200w 规模下每轮 200w 带双索引 UPDATE ≈ GB 级 WAL 写放大
        // 评分时会读取关联的 OCR 结果（hasPhone / hasAmount 等）作为强信号
        // 自动收藏改为可选特性：仅当用户在设置中开启后，按其自定义规则命中才收藏
        val autoFav = settings.autoFavoriteConfig.first()
        var processed = 0
        var written = 0
        var afterId = 0L
        while (true) {
            val page = imageDao.pagePrimaryHashed(afterId, SCORE_BATCH)
            if (page.isEmpty()) break
            written += db.withTransaction { refreshScores(page.map { it.id }, autoFav) }
            afterId = page.last().id
            processed += page.size
            _progress.value = AnalysisProgress(AnalysisPhase.SCORE, processed, processed)
        }
        _progress.value = AnalysisProgress(AnalysisPhase.SCORE, processed, processed, msg = "完成")
        Timber.i("AnalysisPipeline.runScore: scored $processed primary images ($written changed)")
    }

    private suspend fun saveOcrAndRefreshScores(writes: List<OcrWrite>) {
        if (writes.isEmpty()) return
        val autoFav = settings.autoFavoriteConfig.first()
        db.withTransaction {
            ocrDao.saveOcrResultsBatch(writes)
            refreshScores(writes.map { it.imageId }, autoFav)
        }
    }

    private suspend fun refreshScores(ids: List<Long>, autoFav: AutoFavoriteConfig): Int {
        val images = imageDao.getByIds(ids).filter { it.hashDone }
        val ocrMap = ocrDao.getByImageIds(images.map { it.id }).associateBy { it.imageId }
        val writes = ArrayList<ScoreWrite>()
        val favorites = ArrayList<Long>()
        for (image in images) {
            val ocr = ocrMap[image.id]
            val favorite = autoFav.enabled && !image.isFavorite && ocr != null && matchesAutoFavorite(autoFav, ocr)
            val scored = if (favorite) image.copy(isFavorite = true) else image
            val info = ScoringEngine.computeInfoScore(scored, ocr)
            val delete = ScoringEngine.computeDeleteScore(scored, ocr)
            if (!scoreEquals(image.infoScore, info) || !scoreEquals(image.deleteScore, delete)) {
                writes += ScoreWrite(image.id, info, delete)
            }
            if (favorite) favorites += image.id
        }
        if (writes.isNotEmpty()) imageDao.saveScoresBatch(writes)
        favorites.chunked(FAVORITE_CHUNK).forEach { imageDao.favoriteByIds(it) }
        return writes.size
    }

    /** 评分脏检查的相等判定（容差比较，避免浮点表示误差造成的无效重写）。 */
    private fun scoreEquals(old: Float, new: Float): Boolean = abs(old - new) < 1e-4f

    /** 判断一条 OCR 结果是否命中用户启用的任一自动收藏规则 */
    private fun matchesAutoFavorite(config: AutoFavoriteConfig, ocr: OcrTextEntity): Boolean =
        (config.idNumber && ocr.hasIdNumber) ||
            (config.orderNo && ocr.hasOrderNo) ||
            (config.amount && ocr.hasAmount) ||
            (config.phone && ocr.hasPhone) ||
            (config.address && ocr.hasAddress)

    companion object {
        private const val SCORE_BATCH = 500

        /**
         * hash+质量 合并阶段的块内并发度。原生 fd 直读后读不再是瓶颈、转为解码/DCT 的 CPU 开销，
         * 按 CPU 与堆预算取较小值，每路预留 64MB（原始字节 + 解码）；上限 4。
         */
        private val HASH_CONCURRENCY = minOf(
            Runtime.getRuntime().availableProcessors(),
            (Runtime.getRuntime().maxMemory() / (64L * 1024 * 1024)).toInt(),
        ).coerceIn(1, 4)

        /** 质量 / pHash 的解码目标边长（pHash 再缩到 32，质量在此尺寸上评估） */
        private const val QUALITY_DECODE_SIDE = 768

        // 各阶段每次装载进内存的实体块大小（id 列表分块 → getByIds）：bound 工作集内存，支持「无限」
        // 均 < SQLite IN 变量上限；hash/ocr 解码重，取小块；ai/向量受网络与并发信号量约束。
        private const val HASH_CHUNK = 256
        private const val OCR_CHUNK = 200
        private const val AI_CHUNK = 64
        private const val EMBEDDING_CHUNK = 64

        /** 无限模式（limit<=0）的待办 id 分页大小：循环消费直到取空，不一次物化全量 id 列表。 */
        private const val UNLIMITED_PAGE = 4096

        /** 自动收藏批量写入的分块大小（< SQLite 变量上限）。 */
        private const val FAVORITE_CHUNK = 500

        /** 基础设施失败连续张数达到该值即中止本轮（Shizuku/Root 整体不可用）。 */
        private const val INFRA_ABORT_STREAK = 20

        /** OCR 引擎连续失败达到该值即中止 OCR 阶段（引擎大概率已坏，别再逐张喂）。 */
        private const val OCR_FAIL_ABORT_STREAK = 50

        /** AI 打标 / 向量连续失败达到该值即中止对应阶段（endpoint 大概率不可达）。 */
        private const val AI_FAIL_ABORT_STREAK = 25

        /** 无限模式分页循环的保险丝（防御 mtime 反复漂移等病态场景下的死循环）。 */
        private const val MAX_PENDING_ROUNDS = 5000
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
