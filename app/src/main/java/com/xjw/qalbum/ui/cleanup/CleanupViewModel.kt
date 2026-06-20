package com.xjw.qalbum.ui.cleanup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xjw.qalbum.data.db.dao.ImageDao
import com.xjw.qalbum.data.db.dao.TagDao
import com.xjw.qalbum.data.db.entity.CleanupBatchEntity
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.data.db.entity.ImageTagEntity
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
import com.xjw.qalbum.service.cleanup.CleanupBatch
import com.xjw.qalbum.service.cleanup.CleanupController
import com.xjw.qalbum.service.cleanup.CleanupRules
import com.xjw.qalbum.service.cleanup.DuplicateReviewPolicy
import com.xjw.qalbum.service.cleanup.ExternalViewTarget
import com.xjw.qalbum.service.cleanup.ImageFileManager
import com.xjw.qalbum.service.cleanup.ReviewBatchItem
import com.xjw.qalbum.service.cleanup.ThumbnailTrashManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CleanupViewModel(
    private val cleanupController: CleanupController,
    private val analysisPipeline: AnalysisPipeline,
    private val thumbnailTrashManager: ThumbnailTrashManager,
    private val imageRepository: ImageRepository,
    private val settings: SettingsRepository,
    private val tagDao: TagDao,
    private val imageFileManager: ImageFileManager,
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

    val quarantine: StateFlow<List<QuarantineEntity>> = cleanupController.observeQuarantine()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val thumbnailTrashSummary: StateFlow<ImageDao.ThumbnailTrashSummary> =
        thumbnailTrashManager.observeSummary()
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                ImageDao.ThumbnailTrashSummary(0, 0L),
            )

    val thumbnailTrash: StateFlow<List<ImageEntity>> = thumbnailTrashManager.observeItems()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _lastBatch = MutableStateFlow<CleanupBatch?>(null)
    val lastBatch: StateFlow<CleanupBatch?> = _lastBatch.asStateFlow()

    private val _reviewBatch = MutableStateFlow<CleanupReviewBatch?>(null)
    val reviewBatch: StateFlow<CleanupReviewBatch?> = _reviewBatch.asStateFlow()

    private val _selectedReviewDeleteIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedReviewDeleteIds: StateFlow<Set<Long>> = _selectedReviewDeleteIds.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** 一键清理进度（回收站 / 缩略图共用）；为 null 表示当前没有清理任务在跑 */
    private val _purgeProgress = MutableStateFlow<PurgeProgress?>(null)
    val purgeProgress: StateFlow<PurgeProgress?> = _purgeProgress.asStateFlow()

    /** 当前清理配置（每类开关 + 门限），由设置页或三档预设写入 */
    val cleanupConfig: StateFlow<CleanupConfig> = settings.cleanupConfig
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CleanupConfig.DEFAULT)

    /** 点击三档预设：把对应配置写入设置（用户之后可在设置页逐项微调） */
    fun applyPreset(preset: CleanupPreset) {
        viewModelScope.launch { settings.setCleanupConfig(preset.config) }
    }

    fun startAnalysis() = analysisPipeline.start()
    fun cancelAnalysis() = analysisPipeline.cancel()

    /** 重新聚类重复（不重新 hash）：用新算法修复存量错误的重复分组 */
    fun reclusterDuplicates(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            _busy.value = true
            try {
                analysisPipeline.reclusterDuplicates()
                onDone()
            } finally {
                _busy.value = false
            }
        }
    }

    fun generateBatch(onReady: () -> Unit = {}) {
        viewModelScope.launch {
            _busy.value = true
            try {
                val config = cleanupConfig.value
                val label = CleanupPreset.match(config)?.displayName ?: "自定义"
                // 审核数量与顺序：遵守设置中的「每次审核数量」（项数）与分析顺序（早到晚/晚到早）
                val maxItems = settings.reviewLimit.first()
                val oldestFirst = settings.analysisOldestFirst.first()
                val allDuplicates = settings.cleanupAllDuplicates.first()
                val batch = cleanupController.generateBatch(config, label, maxItems, oldestFirst, allDuplicates)
                _lastBatch.value = batch
                loadReviewBatch(batch)
                onReady()
            } finally {
                _busy.value = false
            }
        }
    }

    fun restore(quarantineId: Long) {
        viewModelScope.launch { cleanupController.restore(quarantineId) }
    }

    fun purge(quarantineId: Long) {
        viewModelScope.launch { cleanupController.purge(quarantineId) }
    }

    fun purgeAllRecycleBin(onDone: (Int) -> Unit = {}) {
        viewModelScope.launch {
            _busy.value = true
            // 图片可能多达数万张，逐张删除较慢：上报进度给 UI 展示进度条
            val quarantineItems = quarantine.value
            val total = quarantineItems.size + thumbnailTrashSummary.value.count
            _purgeProgress.value = PurgeProgress(0, total)
            try {
                var purgedImages = 0
                quarantineItems.forEachIndexed { index, q ->
                    if (cleanupController.purge(q.id)) purgedImages++
                    _purgeProgress.value = PurgeProgress((index + 1).coerceAtMost(total), total)
                }
                val base = quarantineItems.size
                val purgedThumbnails = thumbnailTrashManager.purgeAll { tDone, _ ->
                    _purgeProgress.value = PurgeProgress((base + tDone).coerceAtMost(total), total)
                }
                onDone(purgedImages + purgedThumbnails)
            } finally {
                _purgeProgress.value = null
                _busy.value = false
            }
        }
    }

    fun purgeThumbnails(imageIds: Set<Long>, onDone: (Int) -> Unit = {}) {
        if (imageIds.isEmpty()) return
        viewModelScope.launch {
            _busy.value = true
            try {
                onDone(thumbnailTrashManager.purge(imageIds.toList()))
            } finally {
                _busy.value = false
            }
        }
    }

    fun purgeAllThumbnails(onDone: (Int) -> Unit = {}) {
        viewModelScope.launch {
            _busy.value = true
            _purgeProgress.value = PurgeProgress(0, thumbnailTrashSummary.value.count)
            try {
                val n = thumbnailTrashManager.purgeAll { done, total ->
                    _purgeProgress.value = PurgeProgress(done, total)
                }
                onDone(n)
            } finally {
                _purgeProgress.value = null
                _busy.value = false
            }
        }
    }

    fun setReviewItemSelected(imageId: Long, selected: Boolean) {
        _selectedReviewDeleteIds.value = if (selected) {
            _selectedReviewDeleteIds.value + imageId
        } else {
            _selectedReviewDeleteIds.value - imageId
        }
    }

    /** 把给定图片加入选择（重复组详情的「全部删除 / 仅留 1 张」、按分类全选共用） */
    fun selectReviewIds(ids: Collection<Long>) {
        _selectedReviewDeleteIds.value = _selectedReviewDeleteIds.value + ids
    }

    /** 从选择中移除给定图片（重复组详情的「全部保留」、按分类全不选共用） */
    fun deselectReviewIds(ids: Collection<Long>) {
        _selectedReviewDeleteIds.value = _selectedReviewDeleteIds.value - ids.toSet()
    }

    /** 反选：给定范围内已选的取消、未选的选中（范围外保持不变） */
    fun invertReviewIds(ids: Collection<Long>) {
        val current = _selectedReviewDeleteIds.value
        val idSet = ids.toSet()
        val toAdd = idSet.filter { it !in current }
        _selectedReviewDeleteIds.value = (current - idSet) + toAdd
    }

    /** 全不选：清空所有勾选（直接回应「一进去自动勾一大堆」的诉求） */
    fun clearReviewSelection() {
        _selectedReviewDeleteIds.value = emptySet()
    }

    /** 恢复系统建议的默认预选（高把握维度 + 重复劣质副本） */
    fun restoreSuggestedSelection() {
        val review = _reviewBatch.value ?: return
        _selectedReviewDeleteIds.value = suggestedSelection(review.items)
    }

    fun applyReviewSelection(onDone: (Int) -> Unit = {}) {
        viewModelScope.launch {
            val review = _reviewBatch.value ?: return@launch
            val selectedIds = _selectedReviewDeleteIds.value
            if (selectedIds.isEmpty()) return@launch
            _busy.value = true
            _purgeProgress.value = PurgeProgress(0, selectedIds.size, "正在移入回收站…")
            try {
                val reasonByImageId = reasonByImageId(review.items)
                val moved = cleanupController.moveToTrash(
                    selectedIds.map { imageId -> imageId to (reasonByImageId[imageId] ?: CleanupRules.REASON_DUPLICATE) }
                ) { done, total ->
                    _purgeProgress.value = PurgeProgress(done, total, "正在移入回收站…")
                }
                // 移入回收站不代表审核结束：剔除已处理的图，留在审核页继续筛选
                _reviewBatch.value = review.copy(
                    items = review.items.mapNotNull { pruneReviewItem(it, selectedIds) },
                )
                _selectedReviewDeleteIds.value = emptySet()
                onDone(moved)
            } finally {
                _purgeProgress.value = null
                _busy.value = false
            }
        }
    }

    fun closeReview() {
        _reviewBatch.value = null
        _selectedReviewDeleteIds.value = emptySet()
        _lastBatch.value = null
    }

    /** 预览页：收藏 / 取消收藏 */
    fun setFavorite(imageId: Long, favorite: Boolean) {
        viewModelScope.launch { imageRepository.setFavorite(imageId, favorite) }
    }

    /**
     * 预览页：保留 —— 永不再进审核（user_keep=true）+ 打「用户保留」标签（仅此一处添加，type=user），
     * 并从当前审核列表移出。
     */
    fun keepImage(imageId: Long) {
        viewModelScope.launch {
            imageRepository.setUserKeep(imageId, true)
            tagDao.insertTag(TagEntity(name = TAG_USER_KEEP, type = "user"))
            tagDao.findByName(TAG_USER_KEEP)?.let { tag ->
                tagDao.linkTag(ImageTagEntity(imageId = imageId, tagId = tag.id, source = "user"))
            }
            removeFromReview(imageId)
        }
    }

    /** 审核页：批量保留选中的图（user_keep=true + 「用户保留」标签），并从审核列表移出 */
    fun keepSelected(onDone: (Int) -> Unit = {}) {
        viewModelScope.launch {
            val ids = _selectedReviewDeleteIds.value
            if (ids.isEmpty()) return@launch
            _busy.value = true
            try {
                tagDao.insertTag(TagEntity(name = TAG_USER_KEEP, type = "user"))
                val tag = tagDao.findByName(TAG_USER_KEEP)
                ids.forEach { id ->
                    imageRepository.setUserKeep(id, true)
                    tag?.let { tagDao.linkTag(ImageTagEntity(imageId = id, tagId = it.id, source = "user")) }
                }
                _reviewBatch.value = _reviewBatch.value?.let { rb ->
                    rb.copy(items = rb.items.mapNotNull { pruneReviewItem(it, ids) })
                }
                _selectedReviewDeleteIds.value = emptySet()
                onDone(ids.size)
            } finally {
                _busy.value = false
            }
        }
    }

    /** 预览页：立即清理这一张（移入回收站），并从审核列表移出 */
    fun trashOne(imageId: Long, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            val reason = _reviewBatch.value?.let { reasonByImageId(it.items)[imageId] }
                ?: CleanupRules.REASON_DUPLICATE
            _busy.value = true
            try {
                cleanupController.moveToTrash(listOf(imageId to reason))
                removeFromReview(imageId)
                onDone()
            } finally {
                _busy.value = false
            }
        }
    }

    /** 预览页：分享（缓存 + 补后缀），回调拿到可分享的 content Uri */
    fun shareImage(imageId: Long, onReady: (ExternalViewTarget) -> Unit) {
        viewModelScope.launch { imageFileManager.prepareShare(imageId)?.let(onReady) }
    }

    private fun removeFromReview(imageId: Long) {
        val removed = setOf(imageId)
        _reviewBatch.value = _reviewBatch.value?.let { rb ->
            rb.copy(items = rb.items.mapNotNull { pruneReviewItem(it, removed) })
        }
        _selectedReviewDeleteIds.value = _selectedReviewDeleteIds.value - imageId
    }

    private suspend fun loadReviewBatch(batch: CleanupBatch) {
        val singleIds = batch.items.filterIsInstance<ReviewBatchItem.Single>().map { it.imageId }
        val singleImages = imageRepository.getByIds(singleIds).associateBy { it.id }

        val groupMembers = HashMap<Long, List<ImageEntity>>()
        batch.items.filterIsInstance<ReviewBatchItem.Group>().forEach { g ->
            groupMembers[g.groupId] =
                imageRepository.getByDuplicateGroup(g.groupId).filter { it.status == "active" }
        }

        val items = buildReviewItems(batch.items, singleImages) { gid -> groupMembers[gid].orEmpty() }
        _reviewBatch.value = CleanupReviewBatch(batch, items)
        _selectedReviewDeleteIds.value = suggestedSelection(items)
    }
}

/**
 * 批处理进度：[done] 已处理张数 / [total] 本次总张数 / [label] 进度对话框标题。
 */
data class PurgeProgress(val done: Int, val total: Int, val label: String = "正在清理…")

data class CleanupReviewBatch(
    val batch: CleanupBatch,
    val items: List<ReviewItem>,
) {
    /** 给定已选 id 集合的字节总和（顶栏展示用） */
    fun selectedBytes(selectedIds: Set<Long>): Long {
        val sizeById = HashMap<Long, Long>()
        items.forEach { item ->
            when (item) {
                is ReviewItem.Single -> sizeById[item.image.id] = item.image.size
                is ReviewItem.Group -> item.members.forEach { sizeById[it.id] = it.size }
            }
        }
        return selectedIds.sumOf { sizeById[it] ?: 0L }
    }
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
        /** 默认预选删除的劣质副本（精确副本 / chatimg / 更低分辨率 / 更模糊） */
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
 * - [ReviewBatchItem.Single] → [ReviewItem.Single]，默认预选与否取决于是否高把握维度。
 * - [ReviewBatchItem.Group] → 用 [DuplicateReviewPolicy.decide] 重算保留图与劣质副本；
 *   组内无 active 成员或决策为空时丢弃该组。
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
                defaultSelected = item.reason in HIGH_CONFIDENCE_REASONS,
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
                defaultDeleteIds = decision.deleteIds,
            )
        }
    }
}

/** 进审核页时的默认勾选集合：高把握单图 + 各重复组的劣质副本。 */
internal fun suggestedSelection(items: List<ReviewItem>): Set<Long> = buildSet {
    items.forEach { item ->
        when (item) {
            is ReviewItem.Single -> if (item.defaultSelected) add(item.image.id)
            is ReviewItem.Group -> addAll(item.defaultDeleteIds)
        }
    }
}

/** 移入回收站后，从审核项剔除已处理的图：重复组成员减少后重算保留/勾选，剩 <2 张则整组移除。 */
internal fun pruneReviewItem(item: ReviewItem, removed: Set<Long>): ReviewItem? = when (item) {
    is ReviewItem.Single -> if (item.image.id in removed) null else item
    is ReviewItem.Group -> {
        val remaining = item.members.filter { it.id !in removed }
        if (remaining.size < 2) {
            null
        } else {
            val decision = DuplicateReviewPolicy.decide(remaining)
            if (decision == null) {
                null
            } else {
                item.copy(
                    members = remaining,
                    keeperId = decision.keepId,
                    defaultDeleteIds = decision.deleteIds,
                )
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
