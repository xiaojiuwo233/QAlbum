package com.xjw.qalbum.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xjw.qalbum.access.AccessManager
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.domain.model.AccessMode
import com.xjw.qalbum.domain.model.ScanProgress
import com.xjw.qalbum.service.cleanup.CompressDemo
import com.xjw.qalbum.service.indexer.ScanController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 设置页 ViewModel：
 * - 暴露当前访问模式 / 路径 / 动态取色等设置
 * - 提供"检查权限"动作，把 Shizuku/Root 可用性同步回 UI
 * - 提供"立即扫描"动作（包装 [ScanController]）
 * - 回收站保留天数与原图压缩参数
 */
class SettingsViewModel(
    private val settings: SettingsRepository,
    private val accessManager: AccessManager,
    private val scanController: ScanController,
    private val appScope: CoroutineScope,
    private val compressDemo: CompressDemo,
) : ViewModel() {

    /**
     * 设置落盘统一走 App 级 [appScope]，而非 [viewModelScope]：二级设置页输入后常直接返回，
     * 页面的 viewModelScope 会随返回取消，写入会丢。全部写入经 [persistMutex] 串行。
     */
    private fun persist(block: suspend () -> Unit) {
        appScope.launch { persistMutex.withLock { block() } }
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        settings.accessMode,
        settings.chatpicPath,
        settings.dynamicColorEnabled,
    ) { mode, path, dynamicColor ->
        SettingsUiState(accessMode = mode, chatpicPath = path, dynamicColorEnabled = dynamicColor)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(),
    )

    private val _accessStatus = MutableStateFlow(AccessStatus.Unknown)
    val accessStatus: StateFlow<AccessStatus> = _accessStatus.asStateFlow()

    val scanRunning: StateFlow<Boolean> = scanController.running
    val scanProgress: StateFlow<ScanProgress?> = scanController.progress

    fun setAccessMode(mode: AccessMode) {
        persist { settings.setAccessMode(mode) }
        // 显式传入待测模式：persist 是异步落盘，此刻从 DataStore 读 accessMode 仍是旧值，
        // 会把连通性测到切换前的 provider 上（切到不可用模式却显示「✔ 就绪」）
        checkAccess(mode)
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

    /** 图库合并同一张 QQ 图片的多个版本（原图 / 预览图 / 缩略图） */
    val galleryStackVariants: StateFlow<Boolean> = settings.galleryStackVariants
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun setGalleryStackVariants(enabled: Boolean) {
        persist { settings.setGalleryStackVariants(enabled) }
    }

    /** 图片网格的快速滚动条 */
    val fastScrollEnabled: StateFlow<Boolean> = settings.fastScrollEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun setFastScrollEnabled(enabled: Boolean) {
        persist { settings.setFastScrollEnabled(enabled) }
    }

    /** 归档的图片也在图库中显示（关闭时只在归档页出现） */
    val archiveVisible: StateFlow<Boolean> = settings.archiveVisible
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setArchiveVisible(visible: Boolean) {
        persist { settings.setArchiveVisible(visible) }
    }

    // ---- 导出相册目录管理 ----

    val exportSubdirs: StateFlow<List<String>> = settings.exportSubdirs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 新建 / 删除导出子目录（SettingsRepository 内部已走 appScope 落盘） */
    fun addExportSubdir(name: String) = settings.addExportSubdir(name)
    fun removeExportSubdir(name: String) = settings.removeExportSubdir(name)

    /** @param mode 指定要检测的访问模式；null = 按当前设置项（适用于设置已落盘的常规检测） */
    fun checkAccess(mode: AccessMode? = null) {
        viewModelScope.launch {
            val provider = if (mode != null) accessManager.providerOf(mode) else accessManager.current()
            val ok = runCatching { provider.isAvailable() }.getOrDefault(false)
            _accessStatus.value = if (ok) AccessStatus.Ready else AccessStatus.NotReady
        }
    }

    fun startFullScan() = scanController.startFullScan()
    fun startIncrementalScan() = scanController.startIncrementalScan()
    fun startDeepVerify() = scanController.startDeepVerify()
    fun cancelScan() = scanController.cancel()

    // ---- 清理：回收站与原图压缩 ----

    val quarantineRetentionDays: StateFlow<Int> = settings.quarantineRetentionDays
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 30)

    fun setQuarantineRetentionDays(days: Int) {
        persist { settings.setQuarantineRetentionDays(days) }
    }

    val compressMaxSide: StateFlow<Int> = settings.compressMaxSide
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.DEFAULT_COMPRESS_MAX_SIDE)

    fun setCompressMaxSide(side: Int) {
        persist { settings.setCompressMaxSide(side) }
    }

    val compressKeepOriginal: StateFlow<Boolean> = settings.compressKeepOriginal
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun setCompressKeepOriginal(keep: Boolean) {
        persist { settings.setCompressKeepOriginal(keep) }
    }

    val compressQuality: StateFlow<Int> = settings.compressQuality
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.DEFAULT_COMPRESS_QUALITY)

    fun setCompressQuality(quality: Int) {
        persist { settings.setCompressQuality(quality) }
    }

    val compressCompare: StateFlow<Boolean> = settings.compressCompare
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun setCompressCompare(enabled: Boolean) {
        persist { settings.setCompressCompare(enabled) }
    }

    /** 压缩质量演示：内置照片按当前质量实际压一次 */
    val compressDemoResult: StateFlow<CompressDemo.Result?> = settings.compressQuality
        .mapLatest { compressDemo.render(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    companion object {
        /** 设置写串行锁（公平）：见 [persist]；跨页面 ViewModel 实例共用，防先后两页的写乱序覆盖 */
        private val persistMutex = Mutex()
    }
}

data class SettingsUiState(
    val accessMode: AccessMode = AccessMode.SHIZUKU,
    val chatpicPath: String = SettingsRepository.DEFAULT_CHATPIC_PATH,
    val dynamicColorEnabled: Boolean = true,
)

enum class AccessStatus { Unknown, Ready, NotReady }
