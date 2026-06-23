package com.xjw.qalbum.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xjw.qalbum.access.AccessManager
import com.xjw.qalbum.data.db.dao.EmbeddingDao
import com.xjw.qalbum.data.db.dao.ImageDao
import com.xjw.qalbum.data.db.dao.OcrDao
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.domain.model.AccessMode
import com.xjw.qalbum.domain.model.AnalysisConfig
import com.xjw.qalbum.domain.model.AutoFavoriteConfig
import com.xjw.qalbum.domain.model.CleanupConfig
import com.xjw.qalbum.domain.model.DuplicateStrictness
import com.xjw.qalbum.domain.model.normalizeAnalysisLimit
import com.xjw.qalbum.domain.model.CleanupPreset
import com.xjw.qalbum.domain.model.ScanProgress
import com.xjw.qalbum.service.indexer.ScanController
import com.xjw.qalbum.service.ocr.OcrModelDownloadService
import com.xjw.qalbum.service.ocr.OcrModelDownloadState
import com.xjw.qalbum.service.ocr.OcrModelStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 设置页 ViewModel：
 * - 暴露当前访问模式 / 路径 / 动态取色等设置
 * - 提供"检查权限"动作，把 Shizuku/Root 可用性同步回 UI
 * - 提供"立即扫描"动作（包装 [ScanController]）
 */
class SettingsViewModel(
    private val settings: SettingsRepository,
    private val accessManager: AccessManager,
    private val scanController: ScanController,
    private val imageDao: ImageDao,
    private val ocrDao: OcrDao,
    private val embeddingDao: EmbeddingDao,
    private val ocrModelDownload: OcrModelDownloadService,
    private val appScope: CoroutineScope,
) : ViewModel() {

    /**
     * 设置落盘统一走 App 级 [appScope]，而非 [viewModelScope]。
     *
     * 二级设置页输入后，用户常直接点返回 / 系统返回离开页面，此时由 InstantSaveTextField
     * 的 onDispose 兜底触发保存；但该页对应的 NavBackStackEntry 正随这次返回销毁，
     * viewModelScope 会被一并取消，落在其上的 DataStore 写入会被丢弃（输入退出后仍是旧值）。
     * 用独立于页面生命周期的 appScope 写入，确保离开即落盘。
     */
    private fun persist(block: suspend () -> Unit) {
        appScope.launch { block() }
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        settings.accessMode,
        settings.chatpicPath,
        settings.dynamicColorEnabled,
        settings.aiEnabled,
        settings.aiEndpoint,
    ) { mode, path, dynamicColor, aiEnabled, aiEndpoint ->
        SettingsBase(mode, path, dynamicColor, aiEnabled, aiEndpoint)
    }.combine(
        combine(settings.aiApiKey, settings.aiModel, settings.aiEmbeddingModel) { k, m, em ->
            Triple(k, m, em)
        }
    ) { base, ai ->
        SettingsUiState(
            accessMode = base.accessMode,
            chatpicPath = base.chatpicPath,
            dynamicColorEnabled = base.dynamicColorEnabled,
            aiEnabled = base.aiEnabled,
            aiEndpoint = base.aiEndpoint,
            aiApiKey = ai.first,
            aiModel = ai.second,
            aiEmbeddingModel = ai.third,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(),
    )

    private data class SettingsBase(
        val accessMode: AccessMode,
        val chatpicPath: String,
        val dynamicColorEnabled: Boolean,
        val aiEnabled: Boolean,
        val aiEndpoint: String,
    )

    private val _accessStatus = MutableStateFlow(AccessStatus.Unknown)
    val accessStatus: StateFlow<AccessStatus> = _accessStatus.asStateFlow()

    val scanRunning: StateFlow<Boolean> = scanController.running
    val scanProgress: StateFlow<ScanProgress?> = scanController.progress

    fun setAccessMode(mode: AccessMode) {
        persist { settings.setAccessMode(mode) }
        checkAccess()
    }

    fun setChatpicPath(path: String) {
        persist { settings.setChatpicPath(path) }
    }

    fun setDynamicColor(enabled: Boolean) {
        persist { settings.setDynamicColorEnabled(enabled) }
    }

    val detailSharedTransition: StateFlow<Boolean> = settings.detailSharedTransition
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setDetailSharedTransition(enabled: Boolean) {
        persist { settings.setDetailSharedTransition(enabled) }
    }

    // ---- 导出相册目录管理 ----

    val exportSubdirs: StateFlow<List<String>> = settings.exportSubdirs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 新建 / 删除导出子目录（SettingsRepository 内部已走 appScope 落盘） */
    fun addExportSubdir(name: String) = settings.addExportSubdir(name)
    fun removeExportSubdir(name: String) = settings.removeExportSubdir(name)

    fun checkAccess() {
        viewModelScope.launch {
            val provider = accessManager.current()
            val ok = runCatching { provider.isAvailable() }.getOrDefault(false)
            _accessStatus.value = if (ok) AccessStatus.Ready else AccessStatus.NotReady
        }
    }

    fun startFullScan() = scanController.startFullScan()
    fun startIncrementalScan() = scanController.startIncrementalScan()
    fun startDeepVerify() = scanController.startDeepVerify()
    fun cancelScan() = scanController.cancel()

    // ---- AI 配置 ----

    fun setAiEnabled(enabled: Boolean) {
        persist { settings.setAiEnabled(enabled) }
    }
    fun setAiEndpoint(endpoint: String) {
        persist { settings.setAiEndpoint(endpoint.trim()) }
    }
    fun setAiApiKey(key: String) {
        persist { settings.setAiApiKey(key.trim()) }
    }
    fun setAiModel(model: String) {
        persist { settings.setAiModel(model.trim()) }
    }
    fun setAiEmbeddingModel(model: String) {
        persist { settings.setAiEmbeddingModel(model.trim()) }
    }

    val aiDisableThinking: StateFlow<Boolean> = settings.aiDisableThinking
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val aiConcurrency: StateFlow<Int> = settings.aiConcurrency
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.DEFAULT_AI_CONCURRENCY)

    val aiMaxTokens: StateFlow<Int> = settings.aiMaxTokens
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.DEFAULT_AI_MAX_TOKENS)

    fun setAiDisableThinking(enabled: Boolean) {
        persist { settings.setAiDisableThinking(enabled) }
    }
    fun setAiConcurrency(value: Int) {
        persist { settings.setAiConcurrency(value) }
    }
    fun setAiMaxTokens(value: Int) {
        persist { settings.setAiMaxTokens(value) }
    }

    // ---- 清理规则配置 ----

    val cleanupConfig: StateFlow<CleanupConfig> = settings.cleanupConfig
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CleanupConfig.DEFAULT)

    private fun updateCleanupConfig(transform: (CleanupConfig) -> CleanupConfig) {
        persist {
            settings.setCleanupConfig(transform(settings.cleanupConfig.first()))
        }
    }

    fun applyCleanupPreset(preset: CleanupPreset) = updateCleanupConfig { preset.config }

    fun setDuplicateEnabled(enabled: Boolean) = updateCleanupConfig { it.copy(duplicateEnabled = enabled) }
    fun setLowResolutionEnabled(enabled: Boolean) = updateCleanupConfig { it.copy(lowResolutionEnabled = enabled) }
    fun setLowResolutionMaxPixels(pixels: Long) = updateCleanupConfig { it.copy(lowResolutionMaxPixels = pixels) }
    fun setBlurEnabled(enabled: Boolean) = updateCleanupConfig { it.copy(blurEnabled = enabled) }
    fun setBlurMaxScore(score: Float) = updateCleanupConfig { it.copy(blurMaxScore = score) }
    fun setTinyFileEnabled(enabled: Boolean) = updateCleanupConfig { it.copy(tinyFileEnabled = enabled) }
    fun setTinyFileMaxBytes(bytes: Long) = updateCleanupConfig { it.copy(tinyFileMaxBytes = bytes) }
    fun setBlankEnabled(enabled: Boolean) = updateCleanupConfig { it.copy(blankEnabled = enabled) }
    fun setCorruptEnabled(enabled: Boolean) = updateCleanupConfig { it.copy(corruptEnabled = enabled) }
    fun setEmojiEnabled(enabled: Boolean) = updateCleanupConfig { it.copy(emojiEnabled = enabled) }
    fun setEmojiSquareRatio(ratio: Float) = updateCleanupConfig { it.copy(emojiSquareRatio = ratio) }
    fun setEmojiMaxSide(px: Int) = updateCleanupConfig { it.copy(emojiMaxSide = px) }
    fun setEmojiMinSide(px: Int) = updateCleanupConfig { it.copy(emojiMinSide = px) }

    // ---- 分析每项开关/数量 / 排序 / 重置 ----

    val analysisConfig: StateFlow<AnalysisConfig> = settings.analysisConfig
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AnalysisConfig.DEFAULT)

    val analysisOldestFirst: StateFlow<Boolean> = settings.analysisOldestFirst
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    private fun updateAnalysisConfig(transform: (AnalysisConfig) -> AnalysisConfig) {
        persist { settings.setAnalysisConfig(transform(settings.analysisConfig.first())) }
    }

    fun setHashLimit(limit: Int) = updateAnalysisConfig { it.copy(hashLimit = normalizeAnalysisLimit(limit)) }
    fun setOcrEnabled(enabled: Boolean) = updateAnalysisConfig { it.copy(ocrEnabled = enabled) }
    fun setOcrLimit(limit: Int) = updateAnalysisConfig { it.copy(ocrLimit = normalizeAnalysisLimit(limit)) }
    fun setAiTagEnabled(enabled: Boolean) = updateAnalysisConfig { it.copy(aiTagEnabled = enabled) }
    fun setAiLimit(limit: Int) = updateAnalysisConfig { it.copy(aiLimit = normalizeAnalysisLimit(limit)) }
    fun setEmbeddingEnabled(enabled: Boolean) = updateAnalysisConfig { it.copy(embeddingEnabled = enabled) }
    fun setEmbeddingLimit(limit: Int) = updateAnalysisConfig { it.copy(embeddingLimit = normalizeAnalysisLimit(limit)) }

    fun setAnalysisOldestFirst(oldestFirst: Boolean) {
        persist { settings.setAnalysisOldestFirst(oldestFirst) }
    }

    val ocrModelDownloadState: StateFlow<OcrModelDownloadState> = ocrModelDownload.downloadState

    private val _ocrModelStatus = MutableStateFlow<OcrModelStatus>(ocrModelDownload.refreshStatus())
    val ocrModelStatus: StateFlow<OcrModelStatus> = _ocrModelStatus.asStateFlow()

    fun refreshOcrModelStatus() {
        _ocrModelStatus.value = ocrModelDownload.refreshStatus()
    }

    fun downloadOcrModel() {
        ocrModelDownload.startDownload()
    }

    fun deleteOcrModel() {
        ocrModelDownload.clearModel()
        refreshOcrModelStatus()
    }

    // ---- 每次审核数量 ----

    val reviewLimit: StateFlow<Int> = settings.reviewLimit
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.DEFAULT_REVIEW_LIMIT)

    fun setReviewLimit(limit: Int) {
        persist { settings.setReviewLimit(limit) }
    }

    val cleanupAllDuplicates: StateFlow<Boolean> = settings.cleanupAllDuplicates
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setCleanupAllDuplicates(enabled: Boolean) {
        persist { settings.setCleanupAllDuplicates(enabled) }
    }

    val maxDuplicateGroupSize: StateFlow<Int> = settings.maxDuplicateGroupSize
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.DEFAULT_MAX_DUP_GROUP_SIZE)

    fun setMaxDuplicateGroupSize(value: Int) {
        persist { settings.setMaxDuplicateGroupSize(value) }
    }

    val duplicateStrictness: StateFlow<DuplicateStrictness> = settings.duplicateStrictness
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DuplicateStrictness.DEFAULT)

    fun setDuplicateStrictness(value: DuplicateStrictness) {
        persist { settings.setDuplicateStrictness(value) }
    }

    /** 清除重复检测的学习成果，回退到当前严格度档位的基础模型。 */
    fun resetDuplicateModel() {
        persist { settings.resetSimilarityWeights() }
    }

    // ---- 推荐删除 ----

    val recommendEnabled: StateFlow<Boolean> = settings.recommendEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val recommendLimit: StateFlow<Int> = settings.recommendLimit
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.DEFAULT_RECOMMEND_LIMIT)

    fun setRecommendEnabled(enabled: Boolean) {
        persist { settings.setRecommendEnabled(enabled) }
    }

    fun setRecommendLimit(value: Int) {
        persist { settings.setRecommendLimit(value) }
    }

    /** 清除推荐删除的学习成果。 */
    fun resetDeleteModel() {
        persist { settings.resetDeleteModel() }
    }

    // ---- 自动收藏配置（分析阶段，默认关闭，规则可自定义）----

    val autoFavoriteConfig: StateFlow<AutoFavoriteConfig> = settings.autoFavoriteConfig
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AutoFavoriteConfig.DEFAULT)

    private fun updateAutoFavoriteConfig(transform: (AutoFavoriteConfig) -> AutoFavoriteConfig) {
        persist {
            settings.setAutoFavoriteConfig(transform(settings.autoFavoriteConfig.first()))
        }
    }

    fun setAutoFavoriteEnabled(enabled: Boolean) = updateAutoFavoriteConfig { it.copy(enabled = enabled) }
    fun setAutoFavoriteIdNumber(enabled: Boolean) = updateAutoFavoriteConfig { it.copy(idNumber = enabled) }
    fun setAutoFavoriteOrderNo(enabled: Boolean) = updateAutoFavoriteConfig { it.copy(orderNo = enabled) }
    fun setAutoFavoriteAmount(enabled: Boolean) = updateAutoFavoriteConfig { it.copy(amount = enabled) }
    fun setAutoFavoritePhone(enabled: Boolean) = updateAutoFavoriteConfig { it.copy(phone = enabled) }
    fun setAutoFavoriteAddress(enabled: Boolean) = updateAutoFavoriteConfig { it.copy(address = enabled) }

    /** 仅重置本地分析（hash/质量/重复/评分），重新分析时只重跑这些免费的本地阶段 */
    fun resetLocalAnalysis(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            imageDao.resetLocalAnalysis()
            onDone()
        }
    }

    /** 重置全部分析标记（含 OCR/AI/向量），并清空 OCR 文本与向量数据 */
    fun resetAllAnalysis(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            imageDao.resetAllAnalysis()
            ocrDao.clearAllOcr()
            embeddingDao.deleteAll()
            onDone()
        }
    }
}

data class SettingsUiState(
    val accessMode: AccessMode = AccessMode.SHIZUKU,
    val chatpicPath: String = SettingsRepository.DEFAULT_CHATPIC_PATH,
    val dynamicColorEnabled: Boolean = true,
    val aiEnabled: Boolean = false,
    val aiEndpoint: String = "",
    val aiApiKey: String = "",
    val aiModel: String = "gpt-4o-mini",
    val aiEmbeddingModel: String = "text-embedding-3-small",
)

enum class AccessStatus { Unknown, Ready, NotReady }
