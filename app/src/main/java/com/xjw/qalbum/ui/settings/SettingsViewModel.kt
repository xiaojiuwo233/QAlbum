package com.xjw.qalbum.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xjw.qalbum.access.AccessManager
import com.xjw.qalbum.access.ChatFeatures
import com.xjw.qalbum.analytics.Analytics
import com.xjw.qalbum.analytics.advancedRisk
import com.xjw.qalbum.analytics.onboardingDone
import com.xjw.qalbum.data.db.AppDatabase
import com.xjw.qalbum.data.db.dao.RuleStats
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.domain.model.AccessMode
import com.xjw.qalbum.domain.model.PaletteStyle
import com.xjw.qalbum.domain.model.ScanProgress
import com.xjw.qalbum.domain.model.ThemeMode
import com.xjw.qalbum.domain.model.ThemeSettings
import com.xjw.qalbum.log.AppLog
import com.xjw.qalbum.service.cleanup.CompressDemo
import com.xjw.qalbum.service.cleanup.rules.CleanupRule
import com.xjw.qalbum.service.cleanup.rules.ScheduledCleanup
import com.xjw.qalbum.service.indexer.ScanController
import com.xjw.qalbum.ui.onboarding.AGREEMENT_VERSION
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.File

/**
 * 设置首页与各二级页共用的 ViewModel（每个页面各一个实例）：读写设置、检测文件访问、发起扫描。
 */
class SettingsViewModel(
    private val context: Context,
    private val settings: SettingsRepository,
    private val accessManager: AccessManager,
    private val scanController: ScanController,
    private val appScope: CoroutineScope,
    private val compressDemo: CompressDemo,
    chatFeatures: ChatFeatures,
    private val cleanupScheduler: ScheduledCleanup,
    private val analytics: Analytics? = null,
) : ViewModel() {

    /**
     * 设置落盘统一走 App 级 [appScope]，而非 [viewModelScope]：二级设置页改完常直接返回，
     * 页面的 viewModelScope 会随返回取消，写入会丢。全部写入经 [persistMutex] 串行。
     */
    private fun persist(block: suspend () -> Unit) {
        appScope.launch { persistMutex.withLock { block() } }
    }

    private fun <T> kotlinx.coroutines.flow.Flow<T>.state(initial: T): StateFlow<T> =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    // ---- 文件访问 ----

    val accessMode: StateFlow<AccessMode> = settings.accessMode.state(AccessMode.SHIZUKU)

    private val _access = MutableStateFlow<AccessState?>(null)

    /** 当前访问方式的检测结果（null = 还没检测） */
    val access: StateFlow<AccessState?> = _access.asStateFlow()

    private var accessJob: Job? = null

    /**
     * 检测当前（或刚选的 [mode]）访问方式：授权状态，以及能不能读到图片目录（[path] 为刚改的目录，设置还没落盘时传入）。
     * 同一方式重新检测时先留着上次的结果（回到页面不闪「检测中」），换了方式或 [showProgress]（手动刷新、重试）才显示检测中。
     */
    fun checkAccess(mode: AccessMode? = null, path: String? = null, showProgress: Boolean = false) {
        accessJob?.cancel()
        accessJob = viewModelScope.launch {
            val target = mode ?: settings.accessMode.first()
            val previous = _access.value
            if (showProgress || previous == null || previous.mode != target || previous.checking) {
                _access.value = AccessState(target, checking = true, shizuku = previous?.shizuku?.takeIf { previous.mode == target })
            }
            val state = withContext(Dispatchers.IO) { probeAccess(target, path ?: settings.chatpicPath.first()) }
            _access.value = state
            // 页面刚打开时设置还没读出来，会先按默认的 Shizuku 检测一次：不是当前方式的结果不记
            if (target == settings.accessMode.first()) analytics?.accessChecked(target.name.lowercase(), state.resultCode())
        }
    }

    /** 统计用的检测结果 */
    private fun AccessState.resultCode(): String = when {
        shizuku?.state == ShizukuState.NotInstalled -> "not_installed"
        shizuku?.state == ShizukuState.NotRunning -> "not_running"
        shizuku?.state == ShizukuState.PermissionDenied -> "denied"
        granted == false -> if (mode == AccessMode.ROOT) "denied" else "unavailable"
        folderFound == false -> "no_folder"
        else -> "ok"
    }

    private suspend fun probeAccess(mode: AccessMode, path: String): AccessState {
        val provider = accessManager.providerOf(mode)
        val shizuku = if (mode == AccessMode.SHIZUKU) probeShizuku(context) else null
        if (shizuku != null && shizuku.state != ShizukuState.Granted) return AccessState(mode, shizuku = shizuku)
        val granted = runCatching { provider.isAvailable() }.getOrDefault(false)
        if (!granted) return AccessState(mode, shizuku = shizuku, granted = false)
        val folder = runCatching { provider.exists(path) }.getOrDefault(false)
        return AccessState(mode, shizuku = shizuku, granted = true, folderFound = folder)
    }

    fun setAccessMode(mode: AccessMode) {
        persist { settings.setAccessMode(mode) }
        // 落盘是异步的，这里直接检测选中的方式，不读设置
        checkAccess(mode)
    }

    /** 高级功能（读取并解密 QQ 数据库），只能在这里的 Root 方式下开启 */
    val advancedFeatures: StateFlow<Boolean> = settings.advancedFeatures.state(false)

    /** 第一次开启时看过风险提示（null = 还没读出来） */
    val advancedRiskAcknowledged: StateFlow<Boolean?> = settings.advancedRiskAcknowledged.state(null)

    fun setAdvancedFeatures(enabled: Boolean) {
        persist { settings.setAdvancedFeatures(enabled) }
    }

    /** 风险提示里点了开启 */
    fun enableAdvancedFeatures() {
        analytics?.advancedRisk(confirmed = true)
        persist { settings.enableAdvancedFeatures() }
    }

    /** 风险提示里点了取消 */
    fun cancelAdvancedFeatures() {
        analytics?.advancedRisk(confirmed = false)
    }

    fun requestShizuku() {
        requestShizukuPermission()
    }

    /** Shizuku 启动、退出、授权结果回来时重新检测（只在 Shizuku 模式下） */
    private val shizukuChanged: () -> Unit = {
        if (_access.value?.mode == AccessMode.SHIZUKU) checkAccess(AccessMode.SHIZUKU)
    }
    private val binderReceived = Shizuku.OnBinderReceivedListener { shizukuChanged() }
    private val binderDead = Shizuku.OnBinderDeadListener { shizukuChanged() }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { _, _ -> shizukuChanged() }

    init {
        runCatching {
            Shizuku.addBinderReceivedListener(binderReceived)
            Shizuku.addBinderDeadListener(binderDead)
            Shizuku.addRequestPermissionResultListener(permissionResult)
        }
    }

    override fun onCleared() {
        runCatching {
            Shizuku.removeBinderReceivedListener(binderReceived)
            Shizuku.removeBinderDeadListener(binderDead)
            Shizuku.removeRequestPermissionResultListener(permissionResult)
        }
    }

    // ---- 扫描 ----

    val chatpicPath: StateFlow<String> = settings.chatpicPath.state(SettingsRepository.DEFAULT_CHATPIC_PATH)
    val scanRunning: StateFlow<Boolean> = scanController.running
    val scanProgress: StateFlow<ScanProgress?> = scanController.progress
    val scanAutomatic: StateFlow<Boolean> = scanController.automatic

    /** 后台自动扫描（每 6 小时）与上次的结果 */
    val autoScan: StateFlow<Boolean> = settings.autoScan.state(true)
    val autoScanLast: StateFlow<String?> = settings.autoScanLast.state(null)

    fun setAutoScan(enabled: Boolean) = persist { settings.setAutoScan(enabled) }

    /** 保存图片目录；空的不保存，返回是否保存了 */
    fun setChatpicPath(path: String): Boolean {
        val trimmed = path.trim()
        if (trimmed.isBlank()) return false
        persist { settings.setChatpicPath(trimmed) }
        return true
    }

    fun startFullScan() = scanController.startFullScan()
    fun startIncrementalScan() = scanController.startIncrementalScan()
    fun startDeepVerify() = scanController.startDeepVerify()
    fun cancelScan() = scanController.cancel()

    // ---- 清理与回收站 ----

    val quarantineRetentionDays: StateFlow<Int> = settings.quarantineRetentionDays.state(30)

    fun setQuarantineRetentionDays(days: Int) {
        persist { settings.setQuarantineRetentionDays(days) }
    }

    /** 每周自动执行的规则 id */
    val scheduledCleanup: StateFlow<Set<String>> = settings.scheduledCleanupRules.state(emptySet())

    /** 上次定时清理的结果（没跑过为 null） */
    val scheduledCleanupLast: StateFlow<String?> = settings.scheduledCleanupLast.state(null)

    /** 能定时执行的规则与它们当前的参数（如「30 天前」）；按聊天的规则只在能用时列出 */
    val scheduledRules: StateFlow<List<Pair<CleanupRule, String>>> = combine(
        combine(
            ScheduledCleanup.eligible.map { rule ->
                settings.cleanupRuleParam(rule.id).map { value ->
                    rule to (rule.option(value ?: rule.defaultOption) ?: rule.option(rule.defaultOption))?.subtitle.orEmpty()
                }
            },
        ) { it.toList() },
        chatFeatures.available,
    ) { rules, chat -> rules.filter { (rule, _) -> chat || rule !in CleanupRule.chat } }
        .state(emptyList())

    fun setScheduledCleanup(ruleId: String, enabled: Boolean) {
        persist { settings.setScheduledCleanupRule(ruleId, enabled) }
    }

    /** 这条规则现在执行会移入多少张、多大（开启每周自动清理前确认用） */
    suspend fun scheduledRulePreview(rule: CleanupRule): RuleStats = withContext(Dispatchers.IO) { cleanupScheduler.preview(rule) }

    // ---- 原图压缩 ----

    val compressQuality: StateFlow<Int> = settings.compressQuality.state(SettingsRepository.DEFAULT_COMPRESS_QUALITY)
    val compressMaxSide: StateFlow<Int> = settings.compressMaxSide.state(SettingsRepository.DEFAULT_COMPRESS_MAX_SIDE)
    val compressKeepOriginal: StateFlow<Boolean> = settings.compressKeepOriginal.state(true)
    val compressCompare: StateFlow<Boolean> = settings.compressCompare.state(true)

    fun setCompressQuality(quality: Int) = persist { settings.setCompressQuality(quality) }
    fun setCompressMaxSide(side: Int) = persist { settings.setCompressMaxSide(side) }
    fun setCompressKeepOriginal(keep: Boolean) = persist { settings.setCompressKeepOriginal(keep) }
    fun setCompressCompare(enabled: Boolean) = persist { settings.setCompressCompare(enabled) }

    /** 压缩质量演示：内置照片按当前质量实际压一次 */
    val compressDemoResult: StateFlow<CompressDemo.Result?> = settings.compressQuality
        .mapLatest { compressDemo.render(it) }
        .state(null)

    // ---- 外观 ----

    val theme: StateFlow<ThemeSettings> = settings.themeSettings.state(ThemeSettings())

    fun setThemeMode(mode: ThemeMode) = persist { settings.setThemeMode(mode) }
    fun setDynamicColor(enabled: Boolean) = persist { settings.setDynamicColorEnabled(enabled) }
    fun setSeedColor(color: Int) = persist { settings.setSeedColor(color) }
    fun setPaletteStyle(style: PaletteStyle) = persist { settings.setPaletteStyle(style) }
    fun setPureBlack(enabled: Boolean) = persist { settings.setPureBlack(enabled) }

    // ---- 图库 ----

    val galleryStackVariants: StateFlow<Boolean> = settings.galleryStackVariants.state(true)
    val fastScrollEnabled: StateFlow<Boolean> = settings.fastScrollEnabled.state(true)
    val archiveVisible: StateFlow<Boolean> = settings.archiveVisible.state(false)
    val detailSharedTransition: StateFlow<Boolean> = settings.detailSharedTransition.state(true)
    val exportSubdirs: StateFlow<List<String>> = settings.exportSubdirs.state(emptyList())

    fun setGalleryStackVariants(enabled: Boolean) = persist { settings.setGalleryStackVariants(enabled) }
    fun setFastScrollEnabled(enabled: Boolean) = persist { settings.setFastScrollEnabled(enabled) }
    fun setArchiveVisible(visible: Boolean) = persist { settings.setArchiveVisible(visible) }
    fun setDetailSharedTransition(enabled: Boolean) = persist { settings.setDetailSharedTransition(enabled) }

    /** 新建 / 删除导出子目录（SettingsRepository 内部已走 appScope 落盘） */
    fun addExportSubdir(name: String) = settings.addExportSubdir(name)
    fun removeExportSubdir(name: String) = settings.removeExportSubdir(name)

    // ---- 首页摘要 ----

    /** 数据库文件与日志的大小（首页「存储空间」「日志」的副标题） */
    val footprint: StateFlow<Footprint?> = MutableStateFlow<Footprint?>(null).also { flow ->
        viewModelScope.launch(Dispatchers.IO) {
            val db = context.getDatabasePath(AppDatabase.DB_NAME)
            flow.value = Footprint(db.length() + File(db.path + "-wal").length(), AppLog.diskSizeBytes())
        }
    }

    data class Footprint(val databaseBytes: Long, val logBytes: Long)

    /** 详细日志开着（首页「日志」的副标题提醒一下） */
    val detailedLog: StateFlow<Boolean> = settings.detailedLog.state(false)

    /** 同意了用户协议（引导里或协议更新后） */
    fun acceptAgreement() = persist { settings.setAgreementVersion(AGREEMENT_VERSION) }

    /** 首次引导走完；[scan] 是点了开始扫描 */
    fun finishOnboarding(scan: Boolean) {
        val access = _access.value
        analytics?.onboardingDone(scan, (access?.mode ?: AccessMode.SHIZUKU).name.lowercase(), access?.ready == true)
        persist { settings.setOnboardingDone(true) }
    }

    companion object {
        /** 设置写串行锁（公平）：见 [persist]；跨页面 ViewModel 实例共用，防先后两页的写乱序覆盖 */
        private val persistMutex = Mutex()
    }
}

/**
 * 文件访问的检测结果。[shizuku] 只在 Shizuku 模式下有；[granted] 为 null 表示还没走到这一步；
 * [folderFound] 是能不能找到图片目录（授权了才检测）。
 */
data class AccessState(
    val mode: AccessMode,
    val checking: Boolean = false,
    val shizuku: ShizukuStatus? = null,
    val granted: Boolean? = null,
    val folderFound: Boolean? = null,
) {
    /** 可以扫描、清理 */
    val ready: Boolean get() = granted == true
}
