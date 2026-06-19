package com.xjw.qalbum.ui.cleanup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xjw.qalbum.data.db.dao.ImageDao
import com.xjw.qalbum.data.db.entity.CleanupBatchEntity
import com.xjw.qalbum.data.db.entity.CleanupCandidateEntity
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.data.db.entity.QuarantineEntity
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.data.repository.ImageRepository
import com.xjw.qalbum.domain.model.CleanupConfig
import com.xjw.qalbum.domain.model.CleanupPreset
import com.xjw.qalbum.service.analysis.AnalysisPipeline
import com.xjw.qalbum.service.analysis.AnalysisProgress
import com.xjw.qalbum.service.analysis.AnalysisState
import com.xjw.qalbum.service.cleanup.CleanupBatch
import com.xjw.qalbum.service.cleanup.CleanupController
import com.xjw.qalbum.service.cleanup.ThumbnailTrashManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CleanupViewModel(
    private val cleanupController: CleanupController,
    private val analysisPipeline: AnalysisPipeline,
    private val thumbnailTrashManager: ThumbnailTrashManager,
    private val imageRepository: ImageRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    val analysisState: StateFlow<AnalysisState> = analysisPipeline.state
    val analysisProgress: StateFlow<AnalysisProgress?> = analysisPipeline.progress

    /** 全局分析完成度（DB 聚合）：驱动「已分析 X/Y」总进度条与按钮三态 */
    val analysisOverview: StateFlow<ImageDao.AnalysisProgressRow> =
        imageRepository.observeAnalysisProgress()
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                ImageDao.AnalysisProgressRow(0, 0, 0, 0, 0, 0),
            )

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

    fun generateBatch() {
        viewModelScope.launch {
            _busy.value = true
            try {
                val config = cleanupConfig.value
                val label = CleanupPreset.match(config)?.displayName ?: "自定义"
                // 审核数量与顺序：遵守设置中的「每次审核数量」与分析顺序（早到晚/晚到早）
                val maxCount = settings.reviewLimit.first()
                val oldestFirst = settings.analysisOldestFirst.first()
                val batch = cleanupController.generateBatch(config, label, maxCount, oldestFirst)
                _lastBatch.value = batch
                loadReviewBatch(batch)
            } finally {
                _busy.value = false
            }
        }
    }

    fun applyBatch(batchId: Long, onDone: (Int) -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            try {
                val moved = cleanupController.applyBatch(batchId)
                onDone(moved)
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

    /** 全选：把给定（当前可见）图片全部加入选择 */
    fun selectReviewIds(ids: Collection<Long>) {
        _selectedReviewDeleteIds.value = _selectedReviewDeleteIds.value + ids
    }

    /** 全不选：从选择中移除给定（当前可见）图片 */
    fun deselectReviewIds(ids: Collection<Long>) {
        _selectedReviewDeleteIds.value = _selectedReviewDeleteIds.value - ids.toSet()
    }

    /** 反选：给定范围内已选的取消、未选的选中（范围外的选择保持不变） */
    fun invertReviewIds(ids: Collection<Long>) {
        val current = _selectedReviewDeleteIds.value
        val idSet = ids.toSet()
        val toAdd = idSet.filter { it !in current }
        _selectedReviewDeleteIds.value = (current - idSet) + toAdd
    }

    fun applyReviewSelection(onDone: (Int) -> Unit = {}) {
        viewModelScope.launch {
            val review = _reviewBatch.value ?: return@launch
            val selectedIds = _selectedReviewDeleteIds.value
            _busy.value = true
            // 移入回收站逐图较慢，上报进度避免用户干等
            _purgeProgress.value = PurgeProgress(0, selectedIds.size, "正在移入回收站…")
            try {
                // 原因映射来自所有 tile（含重复组里推荐保留的最优图，便于用户选删时仍带原因）
                val reasonByImageId = review.tiles.associate { it.image.id to it.reason }
                val moved = cleanupController.moveToTrash(
                    selectedIds.map { imageId ->
                        imageId to (reasonByImageId[imageId] ?: "重复")
                    }
                ) { done, total ->
                    _purgeProgress.value = PurgeProgress(done, total, "正在移入回收站…")
                }
                _reviewBatch.value = null
                _selectedReviewDeleteIds.value = emptySet()
                _lastBatch.value = null
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
    }

    private suspend fun loadReviewBatch(batch: CleanupBatch) {
        val candidates = cleanupController.listCandidates(batch.id).ifEmpty { batch.candidates }
        val candidateImages = imageRepository.getByIds(candidates.map { it.imageId })
            .associateBy { it.id }
        val candidateIds = candidates.map { it.imageId }.toSet()

        val tiles = mutableListOf<ReviewTile>()
        val seenDuplicateGroups = mutableSetOf<Long>()

        candidates.forEach { candidate ->
            val image = candidateImages[candidate.imageId] ?: return@forEach
            val groupId = image.duplicateGroupId
            if (candidate.reason == "重复" && groupId != null) {
                // 同组只展开一次：列出整组所有活跃图（含推荐保留的最优一张），
                // 让用户自由对比并独立勾选——可勾两张、也可都不勾
                if (seenDuplicateGroups.add(groupId)) {
                    val groupImages = imageRepository.getByDuplicateGroup(groupId)
                        .filter { it.status == "active" }
                    groupImages.forEach { img ->
                        tiles += ReviewTile(
                            image = img,
                            reason = "重复",
                            duplicateGroupId = groupId,
                            recommendedDelete = img.id in candidateIds,
                        )
                    }
                }
            } else {
                tiles += ReviewTile(
                    image = image,
                    reason = candidate.reason,
                    duplicateGroupId = null,
                    recommendedDelete = true,
                )
            }
        }

        _reviewBatch.value = CleanupReviewBatch(
            batch = batch,
            candidates = candidates,
            tiles = tiles,
        )
        // 默认勾选所有「建议删除」项（非最优重复 + 全部无用项），与原行为一致
        _selectedReviewDeleteIds.value = tiles.filter { it.recommendedDelete }.map { it.image.id }.toSet()
    }
}

/**
 * 批处理进度：[done] 已处理张数 / [total] 本次总张数 / [label] 进度对话框标题。
 * 一键清理（永久删除）与移入回收站共用，靠 [label] 区分文案。
 */
data class PurgeProgress(val done: Int, val total: Int, val label: String = "正在清理…")

data class CleanupReviewBatch(
    val batch: CleanupBatch,
    val candidates: List<CleanupCandidateEntity>,
    val tiles: List<ReviewTile>,
)

/**
 * 审核页里的一个图片格子。
 *
 * - [reason]：命中的清理维度（重复 / 低分辨率 / 极小文件 / 模糊 / 空白纯色 / 损坏）
 * - [duplicateGroupId]：非空表示属于某个重复组（同组图片相邻展示）
 * - [recommendedDelete]：系统是否建议删除（默认预选）；重复组里最优的一张为 false（建议保留）
 */
data class ReviewTile(
    val image: ImageEntity,
    val reason: String,
    val duplicateGroupId: Long?,
    val recommendedDelete: Boolean,
)
