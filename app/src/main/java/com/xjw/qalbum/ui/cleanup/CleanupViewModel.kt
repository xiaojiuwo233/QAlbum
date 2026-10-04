package com.xjw.qalbum.ui.cleanup

import android.app.Application
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.xjw.qalbum.data.db.dao.CleanupDao
import com.xjw.qalbum.data.db.dao.ImageDao
import com.xjw.qalbum.data.db.dao.TagDao
import com.xjw.qalbum.data.db.entity.CleanupBatchEntity
import com.xjw.qalbum.data.db.entity.CleanupCandidateEntity
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.data.db.entity.ImageTagEntity
import com.xjw.qalbum.data.db.entity.OcrTextEntity
import com.xjw.qalbum.data.db.entity.QuarantineEntity
import com.xjw.qalbum.data.db.entity.TAG_USER_KEEP
import com.xjw.qalbum.data.db.entity.TagEntity
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.data.repository.ImageRepository
import com.xjw.qalbum.domain.model.CleanupConfig
import com.xjw.qalbum.domain.model.CleanupPreset
import com.xjw.qalbum.service.analysis.AnalysisPipeline
import com.xjw.qalbum.service.analysis.AnalysisProgress
import com.xjw.qalbum.service.analysis.AnalysisState
import com.xjw.qalbum.service.analysis.DeleteFeatures
import com.xjw.qalbum.service.analysis.DeletePreferenceModel
import com.xjw.qalbum.service.analysis.SimilarityLearner
import com.xjw.qalbum.service.analysis.SimilarityModel
import com.xjw.qalbum.service.cleanup.CleanupBatch
import com.xjw.qalbum.service.cleanup.CleanupController
import com.xjw.qalbum.service.cleanup.CleanupRules
import com.xjw.qalbum.service.cleanup.DuplicateReviewPolicy
import com.xjw.qalbum.service.cleanup.ExternalViewTarget
import com.xjw.qalbum.service.cleanup.ImageFileManager
import com.xjw.qalbum.service.cleanup.ReasonGroup
import com.xjw.qalbum.service.cleanup.ReviewBatchItem
import com.xjw.qalbum.service.cleanup.RiskReport
import com.xjw.qalbum.service.cleanup.ThumbnailTrashManager
import com.xjw.qalbum.worker.AnalysisWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger

/**
 * 跨 ViewModel 实例共享的操作状态。批量清理跑在 App 级 [CoroutineScope]（比页面长寿），
 * busy / 进度 / 错误提示必须在 VM 重建（旋转）与第二个入口（ThumbnailTrashActivity 自持一个
 * CleanupViewModel 实例）之间保持一致，否则清理进行中重建页面会丢失模态屏蔽与进度。
 */
private object CleanupOpState {
    private val busyCount = AtomicInteger(0)

    val busy = MutableStateFlow(false)
    val purgeProgress = MutableStateFlow<PurgeProgress?>(null)
    val message = MutableStateFlow<String?>(null)

    fun pushBusy() {
        busy.value = busyCount.incrementAndGet() > 0
    }

    fun popBusy() {
        busy.value = busyCount.decrementAndGet() > 0
    }
}

class CleanupViewModel(
    private val app: Application,
    private val cleanupController: CleanupController,
    private val analysisPipeline: AnalysisPipeline,
    private val thumbnailTrashManager: ThumbnailTrashManager,
    private val imageRepository: ImageRepository,
    private val settings: SettingsRepository,
    private val tagDao: TagDao,
    private val imageFileManager: ImageFileManager,
    private val appScope: CoroutineScope,
) : ViewModel() {

    val analysisState: StateFlow<AnalysisState> = analysisPipeline.state
    val analysisProgress: StateFlow<AnalysisProgress?> = analysisPipeline.progress

    /** 全局分析完成度（DB 聚合）：提供总数与 hash 完成数，驱动总进度条分母与按钮三态 */
    val analysisOverview: StateFlow<ImageDao.AnalysisProgressRow> =
        imageRepository.observeAnalysisProgress()
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                ImageDao.AnalysisProgressRow(0, 0, 0, 0, 0, 0),
            )

    /**
     * 「已分析 X/Y」里的 X：完成全部**当前需要**阶段的图片数（与图库左下角完成角标同口径）。
     */
    val fullyAnalyzedCount: StateFlow<Int> =
        combine(settings.analysisConfig, settings.aiEnabled, settings.aiEndpoint) { config, aiEnabled, endpoint ->
            val aiReady = aiEnabled && endpoint.isNotBlank()
            Triple(config.ocrEnabled, config.aiTagEnabled && aiReady, config.embeddingEnabled && aiReady)
        }.flatMapLatest { (ocr, ai, emb) ->
            imageRepository.observeFullyAnalyzedCount(ocr, ai, emb)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val recentBatches: StateFlow<List<CleanupBatchEntity>> = cleanupController.observeRecentBatches()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val quarantine: Flow<PagingData<QuarantineEntity>> =
        cleanupController.pagingQuarantine().cachedIn(viewModelScope)

    val quarantineStats: StateFlow<CleanupDao.QuarantineStatsRow> =
        cleanupController.observeQuarantineStats()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CleanupDao.QuarantineStatsRow(0, 0))

    val thumbnailTrashSummary: StateFlow<ImageDao.ThumbnailTrashSummary> =
        thumbnailTrashManager.observeSummary()
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                ImageDao.ThumbnailTrashSummary(0, 0L),
            )

    val thumbnailTrash: Flow<PagingData<ImageEntity>> =
        thumbnailTrashManager.pagingItems().cachedIn(viewModelScope)

    private val _lastBatch = MutableStateFlow<CleanupBatch?>(null)
    val lastBatch: StateFlow<CleanupBatch?> = _lastBatch.asStateFlow()

    private val _reviewBatch = MutableStateFlow<CleanupReviewBatch?>(null)
    val reviewBatch: StateFlow<CleanupReviewBatch?> = _reviewBatch.asStateFlow()

    private val _selectedReviewDeleteIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedReviewDeleteIds: StateFlow<Set<Long>> = _selectedReviewDeleteIds.asStateFlow()

    val busy: StateFlow<Boolean> = CleanupOpState.busy.asStateFlow()

    /** 一键清理进度（回收站 / 缩略图共用）；为 null 表示当前没有清理任务在跑 */
    val purgeProgress: StateFlow<PurgeProgress?> = CleanupOpState.purgeProgress.asStateFlow()
    private val _purgeProgress = CleanupOpState.purgeProgress

    /** 操作结果 / 失败提示（Toast 展示后调 [consumeMessage] 清空）；进程级共享，重建不丢 */
    val message: StateFlow<String?> = CleanupOpState.message.asStateFlow()

    fun consumeMessage() {
        CleanupOpState.message.value = null
    }

    /** 是否已尝试过恢复审核会话（仅清理页首帧触发一次，避免 ThumbnailTrashActivity 的实例白装载） */
    private var restoreAttempted = false

    /** 当前清理配置（每类开关 + 门限），由设置页或三档预设写入 */
    val cleanupConfig: StateFlow<CleanupConfig> = settings.cleanupConfig
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CleanupConfig.DEFAULT)

    /**
     * 统一协程入口：清理页所有写操作都可能在磁盘将满时执行，Room 写入（SQLiteFullException）、
     * shell 派生失败等异常必须兜住降级为可见错误，而不是穿透 launch 杀进程。
     * 批量 / 文件操作传 [appScope]（页面销毁不中断），纯展示计算传 viewModelScope。
     */
    private fun launchGuarded(
        scope: CoroutineScope,
        label: String,
        withBusy: Boolean = true,
        block: suspend CoroutineScope.() -> Unit,
    ) {
        scope.launch {
            if (withBusy) CleanupOpState.pushBusy()
            try {
                block()
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Timber.e(t, "%s 失败", label)
                CleanupOpState.message.value = "$label 失败：${t.message ?: t.javaClass.simpleName}"
            } finally {
                if (withBusy) CleanupOpState.popBusy()
            }
        }
    }

    /** UI 回调（Toast / 页面切换）统一回主线程：appScope 任务跑在 IO 线程，直接 Toast 会崩。 */
    private suspend fun onMain(block: () -> Unit) = withContext(Dispatchers.Main) { block() }

    /** 点击三档预设：把对应配置写入设置（落盘走 appScope，页面销毁不丢写） */
    fun applyPreset(preset: CleanupPreset) {
        launchGuarded(appScope, "应用清理预设", withBusy = false) { settings.setCleanupConfig(preset.config) }
    }

    /** 分析交给 AnalysisWorker（dataSync 前台通知承载），页面销毁 / 进程回收不中断整轮分析 */
    fun startAnalysis() = AnalysisWorker.enqueue(app)

    fun cancelAnalysis() {
        AnalysisWorker.cancel(app)
        analysisPipeline.cancel()
    }

    /**
     * 重新聚类重复（不重新 hash）：fire-and-forget 在 pipeline 自有 scope 跑，页面销毁不中断；
     * 进行中状态由 [analysisState]==Running 反映（与分析卡片共用），[onStarted] 只表示已发起。
     */
    fun reclusterDuplicates(onStarted: () -> Unit = {}) {
        analysisPipeline.reclusterDuplicates()
        onStarted()
    }

    fun generateBatch(onReady: () -> Unit = {}) {
        launchGuarded(viewModelScope, "生成审核批次") {
            val config = cleanupConfig.value
            val label = CleanupPreset.match(config)?.displayName ?: "自定义"
            // 审核数量与顺序：遵守设置中的「每次审核数量」（项数）与分析顺序（早到晚/晚到早）
            val maxItems = settings.reviewLimit.first()
            val oldestFirst = settings.analysisOldestFirst.first()
            val allDuplicates = settings.cleanupAllDuplicates.first()
            val recommendModel = resolveRecommendModel()
            val recommendLimit = if (recommendModel != null) settings.recommendLimit.first() else 0
            val batch = cleanupController.generateBatch(
                config, label, maxItems, oldestFirst, allDuplicates, recommendModel, recommendLimit,
            )
            _lastBatch.value = batch
            settings.setActiveReviewBatchId(batch.id) // 进程被杀后可从该批次恢复审核会话
            // 先打开审核页（此时 busy=true、列表为空 → 显示加载中），再后台装载，
            // 避免「卡片出来后干等几秒才进入审核页」（3w 图装载期间审核页已可见）。
            _reviewBatch.value = null
            _selectedReviewDeleteIds.value = emptySet()
            onReady()
            loadReviewBatch(batch)
        }
    }

    fun restore(quarantineId: Long) {
        // busy 置位（回收站「一键清理」按 !busy 门控）+ QuarantineManager 的 inFlight 互斥，
        // 恢复期间的一键清理 / 同记录永久删除不会与 mv 交错
        launchGuarded(appScope, "恢复") {
            val ok = cleanupController.restore(quarantineId)
            CleanupOpState.message.value =
                if (ok) "已恢复到原位置" else "恢复失败：回收站文件缺失或文件访问未就绪"
        }
    }

    fun purge(quarantineId: Long) {
        launchGuarded(appScope, "永久删除") {
            val ok = cleanupController.purge(quarantineId)
            if (!ok) CleanupOpState.message.value = "永久删除失败：记录不存在或文件删除失败，可稍后重试"
        }
    }

    fun purgeAllRecycleBin(onDone: (Int) -> Unit = {}) {
        launchGuarded(appScope, "清理回收站") {
            // 图片可能多达数万张，逐张删除较慢：上报进度给 UI 展示进度条
            val quarantineCount = cleanupController.countQuarantine()
            val total = quarantineCount
            _purgeProgress.value = PurgeProgress(0, total)
            try {
                var purgedImages = 0
                var processed = 0
                // keyset 分页遍历回收站，按页批量 purge（批删文件 + 批量 DB），避免逐张事务；
                // 批删物理删除 quarantine 行，afterId 单调推进，已删行 id ≤ afterId 不再被取到
                var afterId = 0L
                val pageSize = 200
                while (true) {
                    val page = cleanupController.listQuarantinePaged(afterId, pageSize)
                    if (page.isEmpty()) break
                    afterId = page.last().id
                    purgedImages += cleanupController.purgeByRecords(page)
                    processed += page.size
                    _purgeProgress.value = PurgeProgress(processed.coerceAtMost(total), total)
                }
                onMain { onDone(purgedImages) }
            } finally {
                _purgeProgress.value = null
            }
        }
    }

    fun setReviewItemSelected(imageId: Long, selected: Boolean) {
        // 受保护（用户保留 / 已收藏）的图不接受勾选：所有勾选入口都经这里或下面的批量口
        if (selected && _reviewBatch.value?.protectedIds?.contains(imageId) == true) return
        _selectedReviewDeleteIds.update { if (selected) it + imageId else it - imageId }
    }

    /** 把给定图片加入选择（重复组详情的「全部删除 / 仅留 1 张」、按分类全选共用）；保护图被过滤 */
    fun selectReviewIds(ids: Collection<Long>) {
        val protectedSet = _reviewBatch.value?.protectedIds.orEmpty()
        _selectedReviewDeleteIds.update { cur -> cur + ids.filterNot { it in protectedSet } }
    }

    /** 从选择中移除给定图片（重复组详情的「全部保留」、按分类全不选共用） */
    fun deselectReviewIds(ids: Collection<Long>) {
        _selectedReviewDeleteIds.update { it - ids.toSet() }
    }

    /** 反选：给定范围内已选的取消、未选的选中（范围外保持不变）；保护图不会被选上 */
    fun invertReviewIds(ids: Collection<Long>) {
        val protectedSet = _reviewBatch.value?.protectedIds.orEmpty()
        _selectedReviewDeleteIds.update { current ->
            val idSet = ids.toSet()
            val toAdd = idSet.filter { it !in current && it !in protectedSet }
            (current - idSet) + toAdd
        }
    }

    /** 全不选：清空所有勾选（直接回应「一进去自动勾一大堆」的诉求） */
    fun clearReviewSelection() {
        _selectedReviewDeleteIds.value = emptySet()
    }

    /** 「按推荐勾选」：按系统建议（高把握维度 + 推荐分类）勾选；5w 图计算放后台，期间 busy 显示加载中。 */
    fun restoreSuggestedSelection() {
        launchGuarded(viewModelScope, "按推荐勾选") {
            val review = _reviewBatch.value ?: return@launchGuarded
            _selectedReviewDeleteIds.value = withContext(Dispatchers.Default) {
                // 保护集兜底再减一次：装载后才被收藏的图，组的 defaultDeleteIds 里可能还留着
                suggestedSelection(review.items) - review.protectedIds
            }
        }
    }

    fun applyReviewSelection(onDone: (Int) -> Unit = {}) {
        launchGuarded(appScope, "移入回收站") {
            val review = _reviewBatch.value ?: return@launchGuarded
            val selectedIds = _selectedReviewDeleteIds.value
            if (selectedIds.isEmpty()) return@launchGuarded
            _purgeProgress.value = PurgeProgress(0, selectedIds.size, "正在移入回收站…")
            try {
                val reasonByImageId = reasonByImageId(review.items)
                val moved = cleanupController.moveToTrash(
                    selectedIds.map { imageId -> imageId to (reasonByImageId[imageId] ?: CleanupRules.REASON_DUPLICATE) }
                ) { done, total ->
                    _purgeProgress.value = PurgeProgress(done, total, "正在移入回收站…")
                }
                // 成功集合以 DB 为准：getByIds 只回 active 行，仍 active 的就是没移成功的
                // （Shizuku 掉线 / 磁盘满 / 文件被外部删除）。失败图不进学习样本、不出审核列表。
                val failedIds: Set<Long> = if (moved >= selectedIds.size) {
                    emptySet()
                } else {
                    imageRepository.getByIds(selectedIds.toList()).mapTo(HashSet()) { it.id }
                }
                val movedIds = selectedIds - failedIds
                if (movedIds.isNotEmpty()) {
                    learnDeletePreference(movedIds, emptyList()) // 删=正样本（剔除前学，图还在内存）
                    // 移入回收站不代表审核结束：剔除已处理的图，留在审核页继续筛选。
                    // 基于当前值做变换（而非挂起前快照），不覆盖清理期间的关闭 / 保留等并发修改
                    _reviewBatch.update { cur ->
                        cur?.copy(items = cur.items.mapNotNull { pruneReviewItem(it, movedIds) })
                    }
                }
                _selectedReviewDeleteIds.update { it - movedIds } // 失败的保留勾选，可重试
                if (failedIds.isNotEmpty()) {
                    CleanupOpState.message.value = "${failedIds.size} 张清理失败，已保留在审核列表，可稍后重试"
                }
                onMain { onDone(movedIds.size) }
            } finally {
                _purgeProgress.value = null
            }
        }
    }

    fun closeReview() {
        _reviewBatch.value = null
        _selectedReviewDeleteIds.value = emptySet()
        _lastBatch.value = null
        settings.setActiveReviewBatchId(null) // 已关闭的会话不再被进程重建恢复
    }

    /**
     * 用户判定重复组「差异过大」非真重复：拆分该组并永久排斥（重新查重不再聚到一起），
     * 同时从当前审核列表移除该组、清掉其成员的勾选。
     */
    fun splitDuplicateGroup(groupId: Long, onDone: () -> Unit = {}) {
        launchGuarded(appScope, "拆分重复组") {
            val memberIds = _reviewBatch.value?.items
                ?.filterIsInstance<ReviewItem.Group>()
                ?.firstOrNull { it.groupId == groupId }
                ?.members?.map { it.id }?.toSet().orEmpty()
            imageRepository.splitDuplicateGroup(groupId)
            learnSplitFeedback(memberIds) // 用拆分反馈在线更新相似度模型
            _reviewBatch.update { rb ->
                rb?.copy(items = rb.items.filterNot { it is ReviewItem.Group && it.groupId == groupId })
            }
            _selectedReviewDeleteIds.update { it - memberIds }
            onMain { onDone() }
        }
    }

    /**
     * 用一次「拆分」反馈在线更新视觉相似度模型：组内两两为负样本（用户判定非重复），
     * 配阈值绝对下界防塌缩（下界锚到当前严格度档位）。失败不影响拆分本身。
     */
    private suspend fun learnSplitFeedback(memberIds: Set<Long>) {
        if (memberIds.size < 2) return
        try {
            val feats = imageRepository.featuresByIds(memberIds.toList())
                .map { SimilarityLearner.Feat(it.blockHash, it.featureGrid) }
            val strictness = settings.duplicateStrictness.first()
            val weights = settings.similarityWeights.first()
            val base = weights?.let { SimilarityModel(it[0], it[1], it[2], it[3]) }
                ?: SimilarityModel.DEFAULT.copy(threshold = strictness.scoreThreshold)
            val updated = SimilarityLearner.learnFromSplit(
                base, feats, minThreshold = strictness.scoreThreshold / 2,
            )
            // 落盘走 App 级 appScope：拆分后立刻退出清理页也不丢本次学习
            appScope.launch {
                settings.setSimilarityWeights(updated.wBlock, updated.wLuma, updated.wEdge, updated.threshold)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Timber.w(t, "拆分反馈学习失败")
        }
    }

    /** 预览页：收藏 / 取消收藏。同步刷新审核批次里的实体快照，预览图标即时翻转、取消收藏可用。 */
    fun setFavorite(imageId: Long, favorite: Boolean) {
        launchGuarded(appScope, "收藏", withBusy = false) {
            imageRepository.setFavorite(imageId, favorite)
            _reviewBatch.update { cur ->
                cur?.copy(items = cur.items.map { item ->
                    when (item) {
                        is ReviewItem.Single ->
                            if (item.image.id == imageId) item.copy(image = item.image.copy(isFavorite = favorite)) else item
                        is ReviewItem.Group ->
                            if (item.members.any { it.id == imageId }) {
                                item.copy(members = item.members.map { m ->
                                    if (m.id == imageId) m.copy(isFavorite = favorite) else m
                                })
                            } else {
                                item
                            }
                    }
                })
            }
            // 收藏即保护：从删除勾选中移除，避免刚收藏的图仍留在待删集合里被一并清掉
            if (favorite) _selectedReviewDeleteIds.update { it - imageId }
        }
    }

    /**
     * 预览页：保留 —— 永不再进审核（user_keep=true）+ 打「用户保留」标签（仅此一处添加，type=user），
     * 并从当前审核列表移出。
     */
    fun keepImage(imageId: Long) {
        launchGuarded(appScope, "保留") {
            imageRepository.setUserKeep(imageId, true)
            tagDao.insertTag(TagEntity(name = TAG_USER_KEEP, type = "user"))
            tagDao.findByName(TAG_USER_KEEP)?.let { tag ->
                tagDao.linkTag(ImageTagEntity(imageId = imageId, tagId = tag.id, source = "user"))
            }
            learnDeletePreference(emptyList(), listOf(imageId)) // 保留=负样本
            removeFromReview(imageId)
        }
    }

    /** 审核页：批量保留选中的图（user_keep=true + 「用户保留」标签），并从审核列表移出 */
    fun keepSelected(onDone: (Int) -> Unit = {}) {
        launchGuarded(appScope, "批量保留") {
            val ids = _selectedReviewDeleteIds.value
            if (ids.isEmpty()) return@launchGuarded
            tagDao.insertTag(TagEntity(name = TAG_USER_KEEP, type = "user"))
            val tag = tagDao.findByName(TAG_USER_KEEP)
            val idList = ids.toList()
            // 批量写库：分块 IN 更新单事务 + 批量插入标签关联；逐张 2 个独立事务在万级选中时
            // 是分钟级 fsync 风暴（禁止逐行写的硬约束场景）
            imageRepository.setUserKeepBatch(idList, true)
            tag?.let { t ->
                tagDao.linkTags(idList.map { ImageTagEntity(imageId = it, tagId = t.id, source = "user") })
            }
            learnDeletePreference(emptyList(), ids) // 保留=负样本
            _reviewBatch.update { rb ->
                rb?.copy(items = rb.items.mapNotNull { pruneReviewItem(it, ids, keepSingleton = true) })
            }
            _selectedReviewDeleteIds.update { it - ids }
            onMain { onDone(ids.size) }
        }
    }

    /** 预览页：立即清理这一张（移入回收站），成功才移出审核列表 */
    fun trashOne(imageId: Long, onDone: () -> Unit = {}) {
        launchGuarded(appScope, "清理") {
            val reason = reasonForImage(imageId)
            val moved = cleanupController.moveToTrash(listOf(imageId to reason))
            if (moved == 1) {
                learnDeletePreference(listOf(imageId), emptyList()) // 删=正样本（剔除前学）
                removeFromReview(imageId)
            } else {
                // 移入失败再核对 DB：已非 active 说明被其它路径处理过，只需移出列表；
                // 仍 active 才是真失败，保留在审核列表并明确提示
                val img = imageRepository.getById(imageId)
                if (img == null || img.status != "active") {
                    removeFromReview(imageId)
                } else {
                    CleanupOpState.message.value = "清理失败：文件移动未成功，图片保留在审核列表"
                }
            }
            onMain { onDone() }
        }
    }

    /** 从当前审核批次收集 id → ImageEntity（含 featureGrid 等特征），供删除偏好学习。 */
    private fun reviewImagesById(): Map<Long, ImageEntity> {
        val map = HashMap<Long, ImageEntity>()
        _reviewBatch.value?.items?.forEach { item ->
            when (item) {
                is ReviewItem.Single -> map[item.image.id] = item.image
                is ReviewItem.Group -> item.members.forEach { map[it.id] = it }
            }
        }
        return map
    }

    /** 定向查单图的清理原因（单张操作用）：不为查 1 个 id 构建全量 map。 */
    private fun reasonForImage(imageId: Long): String {
        val items = _reviewBatch.value?.items ?: return CleanupRules.REASON_DUPLICATE
        for (item in items) {
            when (item) {
                is ReviewItem.Single -> if (item.image.id == imageId) return item.reason
                is ReviewItem.Group -> if (item.members.any { it.id == imageId }) return CleanupRules.REASON_DUPLICATE
            }
        }
        return CleanupRules.REASON_DUPLICATE
    }

    /** 用清理/保留行为在线更新删除偏好模型：删=正样本、保留=负样本。失败不影响主流程。 */
    private suspend fun learnDeletePreference(deletedIds: Collection<Long>, keptIds: Collection<Long>) {
        if (deletedIds.isEmpty() && keptIds.isEmpty()) return
        try {
            val byId = reviewImagesById()
            val serialized = settings.deleteModel.first()
            val stored = DeletePreferenceModel.deserialize(serialized)
            // 存量字符串非空却解析失败 = 数据损坏：样本计数必须一并清零重学，
            // 否则「计数已达标 + 全零模型」会让推荐器拿冷模型硬凑推荐
            val corrupted = stored == null && !serialized.isNullOrBlank()
            var model = stored ?: DeletePreferenceModel.DEFAULT
            val now = System.currentTimeMillis()
            var n = 0
            fun learn(id: Long, deleted: Boolean) {
                val img = byId[id] ?: return
                val f = DeleteFeatures.extract(
                    img.featureGrid, img.blurScore, img.width, img.height, img.size,
                    img.sourceType, img.isAnimated, img.mtime, img.duplicateGroupId != null, now,
                )
                model = model.update(f, deleted)
                n++
            }
            deletedIds.forEach { learn(it, true) }
            keptIds.forEach { learn(it, false) }
            if (n > 0) {
                val out = model.serialize()
                // 落盘走 App 级 appScope：操作后立刻退出清理页也不丢本次学习；
                // 损坏重置与写入放同一协程保证先后顺序
                appScope.launch {
                    if (corrupted) settings.resetDeleteModel()
                    settings.setDeleteModel(out)
                    settings.addDeleteSamples(n)
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Timber.w(t, "删除偏好学习失败")
        }
    }

    /** 预览页：分享（缓存 + 补后缀），回调拿到可分享的 content Uri */
    fun shareImage(imageId: Long, onReady: (ExternalViewTarget) -> Unit) {
        launchGuarded(viewModelScope, "分享", withBusy = false) {
            imageFileManager.prepareShare(imageId)?.let { onMain { onReady(it) } }
        }
    }

    /** 导出子目录列表（保存弹窗用） */
    val exportSubdirs: StateFlow<List<String>> = settings.exportSubdirs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 保存弹窗新建子目录（落盘走 App 级 appScope） */
    fun createSubdir(name: String) = settings.addExportSubdir(name)

    /**
     * 预览页「保存」：导出到 Pictures/QAlbum[/子目录]；[alsoTrash] 为真则保存后把该图移入回收站并移出审核。
     */
    fun saveImage(imageId: Long, subdir: String?, alsoTrash: Boolean, onDone: (Boolean) -> Unit) {
        launchGuarded(appScope, "保存", withBusy = false) {
            // Q 以下 WRITE_EXTERNAL_STORAGE 是运行时权限：未授予时 insert 会被 catch 成静默失败，
            // 这里先行拦截给出可读原因
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && !imageFileManager.canExportPreQ()) {
                CleanupOpState.message.value = "保存失败：需要存储权限，请在系统设置中授予后重试"
                onMain { onDone(false) }
                return@launchGuarded
            }
            val ok = imageFileManager.exportToGallery(imageId, subdir)
            if (ok && alsoTrash) {
                val moved = cleanupController.moveToTrash(listOf(imageId to reasonForImage(imageId)))
                if (moved == 1) {
                    removeFromReview(imageId)
                } else {
                    CleanupOpState.message.value = "已保存，但移入回收站失败，图片保留在审核列表"
                }
            }
            onMain { onDone(ok) }
        }
    }

    /** 预览页「查看详情」：按需取该图 OCR 文本（与图库详情页同源） */
    suspend fun getOcr(imageId: Long): OcrTextEntity? = imageRepository.getOcr(imageId)

    private fun removeFromReview(imageId: Long) {
        val removed = setOf(imageId)
        _reviewBatch.update { rb ->
            rb?.copy(items = rb.items.mapNotNull { pruneReviewItem(it, removed, keepSingleton = true) })
        }
        _selectedReviewDeleteIds.update { it - imageId }
    }

    private suspend fun loadReviewBatch(batch: CleanupBatch) {
        // 装载与组装全程放后台线程：3w 图的批量查询 + 逐组保留决策不阻塞主线程，
        // 期间审核页已先行打开并显示「加载中」（见 generateBatch）。
        val items = withContext(Dispatchers.Default) {
            val singleIds = batch.items.filterIsInstance<ReviewBatchItem.Single>().map { it.imageId }
            val singleImages = imageRepository.getByIds(singleIds).associateBy { it.id }

            // 重复组成员一次性批量取（IN 分块），替代逐组 N+1 查询——这是 3w 图卡顿的主因。
            val groupIds = batch.items.filterIsInstance<ReviewBatchItem.Group>().map { it.groupId }
            val groupMembers = imageRepository.getActiveByDuplicateGroups(groupIds)

            buildReviewItems(batch.items, singleImages) { gid -> groupMembers[gid].orEmpty() }
        }
        _reviewBatch.value = CleanupReviewBatch(batch, items)
        _selectedReviewDeleteIds.value = emptySet() // 默认不勾选；由「按推荐勾选」按需触发（避免 5w 图自动勾选卡顿）
    }

    /**
     * 进入清理页时恢复上次未关闭的审核会话（进程被低内存回收后重建）。批次项已落库，
     * 按持久化的批次 id 重建；勾选集不持久化（体量可达数万 id，不适合 DataStore），
     * 恢复后可用「按推荐勾选」快速重建。仅清理页首帧调用一次。
     */
    fun ensureReviewSessionRestored() {
        if (restoreAttempted) return
        restoreAttempted = true
        launchGuarded(viewModelScope, "恢复审核会话") {
            if (_reviewBatch.value != null || _lastBatch.value != null) return@launchGuarded
            val batchId = settings.activeReviewBatchId.first() ?: return@launchGuarded
            val candidates = cleanupController.listCandidates(batchId)
            val rebuilt = withContext(Dispatchers.Default) { rebuildReviewBatch(batchId, candidates) }
            if (rebuilt == null) {
                settings.setActiveReviewBatchId(null) // 批次已空（全部处理完 / 被清理），不再尝试
                return@launchGuarded
            }
            _lastBatch.value = rebuilt
            loadReviewBatch(rebuilt)
        }
    }

    /** 由已落库的候选重建审核批次：仍 active 的候选按重复组折叠，报告按剩余项重算。 */
    private suspend fun rebuildReviewBatch(
        batchId: Long,
        candidates: List<CleanupCandidateEntity>,
    ): CleanupBatch? {
        if (candidates.isEmpty()) return null
        val images = imageRepository.getByIds(candidates.map { it.imageId }).associateBy { it.id } // 仅 active
        if (images.isEmpty()) return null
        val items = ArrayList<ReviewBatchItem>()
        val seenGroups = HashSet<Long>()
        val seenSingles = HashSet<Long>()
        val byReason = LinkedHashMap<String, ReasonGroup>()
        var totalSize = 0L
        for (c in candidates) {
            val img = images[c.imageId] ?: continue
            totalSize += img.size
            val gid = img.duplicateGroupId
            if (gid != null) {
                if (seenGroups.add(gid)) items.add(ReviewBatchItem.Group(gid))
            } else {
                // 按 imageId 去重：审核项 key 唯一是 LazyGrid 的硬要求
                if (seenSingles.add(c.imageId)) items.add(ReviewBatchItem.Single(c.imageId, c.reason))
            }
            val reason = if (gid != null) CleanupRules.REASON_DUPLICATE else c.reason
            val prev = byReason[reason]
            byReason[reason] = ReasonGroup((prev?.count ?: 0) + 1, (prev?.size ?: 0L) + img.size)
        }
        if (items.isEmpty()) return null
        return CleanupBatch(batchId, RiskReport(items.size, totalSize, byReason), items)
    }

    /** 解析当前应用的删除推荐模型；未开启 / 冷启动样本不足 / 数据损坏 → null（不推荐，只默默学习）。 */
    private suspend fun resolveRecommendModel(): DeletePreferenceModel? {
        if (!settings.recommendEnabled.first()) return null
        if (settings.deleteSampleCount.first() < SettingsRepository.RECOMMEND_ACTIVATION_SAMPLES) return null
        val model = DeletePreferenceModel.deserialize(settings.deleteModel.first())
        if (model == null) {
            // 模型损坏：样本计数一并清零回到冷启动静默学习期，避免「计数达标 + 全零 DEFAULT」的假激活
            appScope.launch { settings.resetDeleteModel() }
            return null
        }
        return model
    }
}

/**
 * 批处理进度：[done] 已处理张数 / [total] 本次总张数 / [label] 进度对话框标题。
 */
data class PurgeProgress(val done: Int, val total: Int, val label: String = "正在清理…")

/** 受保护的图：用户显式保留或已收藏，审核列表中明确标识且不可被勾入删除集合。 */
internal fun isProtectedImage(image: ImageEntity): Boolean = image.userKeep == true || image.isFavorite

/** 保护标识的展示文案；非保护图返回 null。 */
internal fun protectionLabel(image: ImageEntity): String? = when {
    image.userKeep == true -> "已保留"
    image.isFavorite -> "已收藏"
    else -> null
}

data class CleanupReviewBatch(
    val batch: CleanupBatch,
    val items: List<ReviewItem>,
) {
    /** id → 字节数。懒建一次（items 不可变；prune 产生新实例自动重建），勾选求和 O(已选数)。 */
    private val sizeById: Map<Long, Long> by lazy {
        buildMap {
            items.forEach { item ->
                when (item) {
                    is ReviewItem.Single -> put(item.image.id, item.image.size)
                    is ReviewItem.Group -> item.members.forEach { put(it.id, it.size) }
                }
            }
        }
    }

    /** 受保护（用户保留 / 已收藏）的图片 id 集合：勾选入口统一据此过滤。懒建一次。 */
    val protectedIds: Set<Long> by lazy {
        buildSet {
            items.forEach { item ->
                when (item) {
                    is ReviewItem.Single -> if (isProtectedImage(item.image)) add(item.image.id)
                    is ReviewItem.Group -> item.members.forEach { if (isProtectedImage(it)) add(it.id) }
                }
            }
        }
    }

    /** 给定已选 id 集合的字节总和（顶栏展示用） */
    fun selectedBytes(selectedIds: Set<Long>): Long = selectedIds.sumOf { sizeById[it] ?: 0L }
}

/**
 * 审核项的 UI 模型。重复组折叠为 [Group]（点开看组内对比），其余各图一项 [Single]。
 */
sealed interface ReviewItem {
    val itemKey: String

    data class Single(
        val image: ImageEntity,
        val reason: String,
        /** 是否进入默认预选（高把握维度：损坏 / 空白 / 表情包） */
        val defaultSelected: Boolean,
    ) : ReviewItem {
        override val itemKey: String get() = "single-${image.id}"
    }

    data class Group(
        val groupId: Long,
        val keeperId: Long,
        val members: List<ImageEntity>,
        /** 默认预选删除的劣质副本（精确副本 / chatimg / 更低分辨率 / 更模糊）；保护图已排除 */
        val defaultDeleteIds: Set<Long>,
    ) : ReviewItem {
        override val itemKey: String get() = "group-$groupId"
    }
}

/** 高把握维度：进审核页即默认勾选；低分辨率 / 模糊 / 极小文件不在此列（仅打建议标签） */
internal val HIGH_CONFIDENCE_REASONS: Set<String> =
    setOf(CleanupRules.REASON_CORRUPT, CleanupRules.REASON_BLANK, CleanupRules.REASON_EMOJI)

/**
 * 由批次审核项构建 UI 审核项（纯函数，便于单测）。
 *
 * - [ReviewBatchItem.Single] → [ReviewItem.Single]，默认预选与否取决于是否高把握维度（保护图不预选）。
 * - [ReviewBatchItem.Group] → 用 [DuplicateReviewPolicy.decide] 重算保留图与劣质副本，
 *   保护（user_keep / 收藏）成员从默认勾选中排除；组内无 active 成员或决策为空时丢弃该组。
 */
internal fun buildReviewItems(
    batchItems: List<ReviewBatchItem>,
    singleImages: Map<Long, ImageEntity>,
    groupMembers: (groupId: Long) -> List<ImageEntity>,
): List<ReviewItem> = batchItems.mapNotNull { item ->
    when (item) {
        is ReviewBatchItem.Single -> {
            val img = singleImages[item.imageId] ?: return@mapNotNull null
            ReviewItem.Single(
                image = img,
                reason = item.reason,
                defaultSelected = !isProtectedImage(img) && img.sourceType !in setOf("chatimg", "chatraw", "chatthumb", "temp") &&
                    (item.reason in HIGH_CONFIDENCE_REASONS || item.reason == CleanupRules.REASON_RECOMMENDED),
            )
        }

        is ReviewBatchItem.Group -> {
            val members = groupMembers(item.groupId)
            val decision = DuplicateReviewPolicy.decide(members) ?: return@mapNotNull null
            if (members.isEmpty()) return@mapNotNull null
            ReviewItem.Group(
                groupId = item.groupId,
                keeperId = decision.keepId,
                members = members,
                defaultDeleteIds = decision.deleteIds - protectedIdsOf(members),
            )
        }
    }
}

/** 组内保护成员 id（构建 / prune 重算默认勾选时排除用）。 */
private fun protectedIdsOf(members: List<ImageEntity>): Set<Long> =
    members.asSequence().filter { isProtectedImage(it) }.mapTo(HashSet()) { it.id }

/** 进审核页时的默认勾选集合：高把握单图 + 各重复组的劣质副本。 */
internal fun suggestedSelection(items: List<ReviewItem>): Set<Long> = buildSet {
    items.forEach { item ->
        when (item) {
            is ReviewItem.Single -> if (item.defaultSelected && item.image.sourceType !in setOf("chatimg", "chatraw", "chatthumb", "temp")) add(item.image.id)
            is ReviewItem.Group -> addAll(item.defaultDeleteIds)
        }
    }
}

/**
 * 处理（清理 / 保留）部分图后，从审核项剔除已处理的图：重复组成员减少后重算保留/勾选。
 *
 * 组缩到只剩 1 张时不再是「可对比的重复组」，此时：
 * - [keepSingleton] = false（批量删除路径）：幸存者是用户**未勾选删除**、即隐含保留的图，整组移除，保持审核列表干净。
 * - [keepSingleton] = true（保留 / 预览单张处理路径）：剩下那张**尚未被用户决定**，转成单图留在审核，
 *   避免它脱离审核流后「消失在茫茫图库里」找不到。
 */
internal fun pruneReviewItem(
    item: ReviewItem,
    removed: Set<Long>,
    keepSingleton: Boolean = false,
): ReviewItem? = when (item) {
    is ReviewItem.Single -> if (item.image.id in removed) null else item
    is ReviewItem.Group -> {
        val remaining = item.members.filter { it.id !in removed }
        when {
            remaining.isEmpty() -> null
            remaining.size == 1 -> if (keepSingleton) {
                ReviewItem.Single(
                    image = remaining.single(),
                    reason = CleanupRules.REASON_DUPLICATE,
                    defaultSelected = false,
                )
            } else {
                null
            }
            else -> {
                val decision = DuplicateReviewPolicy.decide(remaining)
                if (decision == null) {
                    null
                } else {
                    item.copy(
                        members = remaining,
                        keeperId = decision.keepId,
                        defaultDeleteIds = decision.deleteIds - protectedIdsOf(remaining),
                    )
                }
            }
        }
    }
}

/** 移入回收站时的「原因」映射：单图用其维度，组内图统一记为「重复」。 */
internal fun reasonByImageId(items: List<ReviewItem>): Map<Long, String> = buildMap {
    items.forEach { item ->
        when (item) {
            is ReviewItem.Single -> put(item.image.id, item.reason)
            is ReviewItem.Group -> item.members.forEach { put(it.id, CleanupRules.REASON_DUPLICATE) }
        }
    }
}
