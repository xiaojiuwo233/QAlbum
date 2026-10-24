package com.xjw.qalbum.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.xjw.qalbum.domain.model.AccessMode
import com.xjw.qalbum.domain.model.GallerySort
import com.xjw.qalbum.domain.model.PaletteStyle
import com.xjw.qalbum.domain.model.SourceFacetOrder
import com.xjw.qalbum.domain.model.SourceSort
import com.xjw.qalbum.domain.model.ThemeMode
import com.xjw.qalbum.domain.model.ThemeSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.IOException

// 顶层扩展：每个 Context 共享同一个 DataStore 实例。
// settings 文件损坏（写入中途掉电/被杀）时替换为空配置，避免此后每次读取都抛
// CorruptionException 把收集设置流的页面打崩、陷入重启秒崩循环。
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "qalbum_settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * 设置仓库：通过 DataStore Preferences 持久化用户配置
 *
 * 这里只放轻量级、跨页面共享的配置；耗时/大型配置应放数据库。
 */
class SettingsRepository(
    private val context: Context,
    private val appScope: CoroutineScope,
    private val ds: DataStore<Preferences> = context.dataStore,
) {

    // 所有设置读取的基流：IO 读失败回退空配置（各 map 自带默认值），异常不沿流传播——
    // 设置流被各页面 stateIn / service 层直接收集，一旦抛出即整进程崩溃
    private val prefsData: Flow<Preferences> = ds.data.catch { e ->
        if (e is IOException) {
            Timber.e(e, "读取设置失败，回退默认值")
            emit(emptyPreferences())
        } else {
            throw e
        }
    }

    private val cleanupJson = Json { ignoreUnknownKeys = true }

    // ---- 权限模式 ----

    val accessMode: Flow<AccessMode> = prefsData.map { prefs ->
        val raw = prefs[Keys.ACCESS_MODE] ?: AccessMode.SHIZUKU.name
        runCatching { AccessMode.valueOf(raw) }.getOrDefault(AccessMode.SHIZUKU)
    }

    suspend fun setAccessMode(mode: AccessMode) {
        ds.edit { it[Keys.ACCESS_MODE] = mode.name }
    }

    // ---- 扫描路径 ----

    val chatpicPath: Flow<String> = prefsData.map { it[Keys.CHATPIC_PATH] ?: DEFAULT_CHATPIC_PATH }

    // 来源完整性跨进程保留；读取失败、取消或崩溃后，不能把缺失的共用来源当作不存在。
    val qqSourcesComplete: Flow<Boolean> = prefsData.map { it[Keys.QQ_SOURCES_COMPLETE] ?: false }

    suspend fun setQqSourcesComplete(complete: Boolean) {
        ds.edit { it[Keys.QQ_SOURCES_COMPLETE] = complete }
    }

    /** 上次完整读取聊天来源时的解析规则版本（QqPictureCodec.PARSE_VERSION）；旧了要重新完整读取一次 */
    val qqParseVersion: Flow<Int> = prefsData.map { it[Keys.QQ_PARSE_VERSION] ?: 0 }

    suspend fun setQqParseVersion(version: Int) {
        ds.edit { it[Keys.QQ_PARSE_VERSION] = version }
    }

    /**
     * 读取聊天来源开始时来源、路径、关联三张表的位置（「来源:路径:关联」的最大 rowid，见 QqSourceRepository）。
     * 读完补好关联与统计后清掉；中途退出时留着，下次读取从这里补起。
     */
    val qqReadBaseline: Flow<String?> = prefsData.map { it[Keys.QQ_READ_BASELINE] }

    suspend fun setQqReadBaseline(value: String?) {
        ds.edit { if (value == null) it.remove(Keys.QQ_READ_BASELINE) else it[Keys.QQ_READ_BASELINE] = value }
    }

    /** 每天在充电时自动读取聊天来源里的新消息（Root），默认关闭 */
    val qqAutoRefresh: Flow<Boolean> = prefsData.map { it[Keys.QQ_AUTO_REFRESH] ?: false }

    suspend fun setQqAutoRefresh(enabled: Boolean) {
        ds.edit { it[Keys.QQ_AUTO_REFRESH] = enabled }
    }

    suspend fun setChatpicPath(path: String) {
        // 规范化 + 拒绝空值：root="" 会让扫描把根前缀算成 "/"，全部目录快照被判「消失」，
        // 后台增量扫描随即把 200 万行批量标记 missing（图库瞬间清空），因此空串绝不落盘
        val normalized = path.trim().trimEnd('/')
        if (normalized.isBlank()) return
        ds.edit { it[Keys.CHATPIC_PATH] = normalized }
    }

    // ---- 回收站保留时长 ----

    val quarantineRetentionDays: Flow<Int> = prefsData.map { it[Keys.QUARANTINE_DAYS] ?: 30 }

    suspend fun setQuarantineRetentionDays(days: Int) {
        ds.edit { it[Keys.QUARANTINE_DAYS] = days }
    }

    // ---- 清理规则与原图压缩 ----

    /** 某条清理规则上次选的参数（天数 / 年数 / 大小档位）；没选过为 null，用规则自己的默认值 */
    fun cleanupRuleParam(ruleId: String): Flow<Int?> = prefsData.map { it[ruleParamKey(ruleId)] }

    suspend fun setCleanupRuleParam(ruleId: String, value: Int) {
        ds.edit { it[ruleParamKey(ruleId)] = value }
    }

    private fun ruleParamKey(ruleId: String) = intPreferencesKey("cleanup_rule_$ruleId")

    /** 定时清理：每周自动把这些规则（规则 id）命中的图移入回收站，各用当前参数；默认都不选 */
    val scheduledCleanupRules: Flow<Set<String>> = prefsData.map { it[Keys.SCHEDULED_CLEANUP] ?: emptySet() }

    suspend fun setScheduledCleanupRule(ruleId: String, enabled: Boolean) {
        ds.edit { prefs ->
            val current = prefs[Keys.SCHEDULED_CLEANUP] ?: emptySet()
            prefs[Keys.SCHEDULED_CLEANUP] = if (enabled) current + ruleId else current - ruleId
        }
    }

    /** 后台自动扫描新图片（每 6 小时一次，增量） */
    val autoScan: Flow<Boolean> = prefsData.map { it[Keys.AUTO_SCAN] ?: true }

    suspend fun setAutoScan(enabled: Boolean) {
        ds.edit { it[Keys.AUTO_SCAN] = enabled }
    }

    /** 上次自动扫描的结果（扫描页显示），没跑过为 null */
    val autoScanLast: Flow<String?> = prefsData.map { it[Keys.AUTO_SCAN_LAST] }

    suspend fun setAutoScanLast(text: String) {
        ds.edit { it[Keys.AUTO_SCAN_LAST] = text }
    }

    /** 上次定时清理的结果（设置页显示），没跑过为 null */
    val scheduledCleanupLast: Flow<String?> = prefsData.map { it[Keys.SCHEDULED_CLEANUP_LAST] }

    suspend fun setScheduledCleanupLast(text: String) {
        ds.edit { it[Keys.SCHEDULED_CLEANUP_LAST] = text }
    }

    /** 原图压缩后的长边上限（像素）；0 = 不缩小分辨率，只重新编码 */
    val compressMaxSide: Flow<Int> = prefsData.map { it[Keys.COMPRESS_MAX_SIDE] ?: DEFAULT_COMPRESS_MAX_SIDE }

    suspend fun setCompressMaxSide(side: Int) {
        ds.edit { it[Keys.COMPRESS_MAX_SIDE] = side.coerceAtLeast(0) }
    }

    /** 压缩时把原图留在回收站（到期自动删除）；关闭则压缩后立即释放空间 */
    val compressKeepOriginal: Flow<Boolean> = prefsData.map { it[Keys.COMPRESS_KEEP_ORIGINAL] ?: true }

    suspend fun setCompressKeepOriginal(keep: Boolean) {
        ds.edit { it[Keys.COMPRESS_KEEP_ORIGINAL] = keep }
    }

    /** 压缩质量（JPEG 质量，百分比）：越小越模糊、省得越多 */
    val compressQuality: Flow<Int> = prefsData.map { prefs ->
        prefs[Keys.COMPRESS_QUALITY]?.takeIf { it in MIN_COMPRESS_QUALITY..MAX_COMPRESS_QUALITY } ?: DEFAULT_COMPRESS_QUALITY
    }

    suspend fun setCompressQuality(quality: Int) {
        ds.edit { it[Keys.COMPRESS_QUALITY] = quality.coerceIn(MIN_COMPRESS_QUALITY, MAX_COMPRESS_QUALITY) }
    }

    /** 0.2.1 压缩 / 还原留下的文件属主已修正（只需做一次） */
    val ownershipRepaired: Flow<Boolean> = prefsData.map { it[Keys.OWNERSHIP_REPAIRED] ?: false }

    suspend fun setOwnershipRepaired() {
        ds.edit { it[Keys.OWNERSHIP_REPAIRED] = true }
    }

    /** 压缩后先对比：压缩结果先放在缓存里给用户对比，点「应用」才替换原图；关闭则直接替换 */
    val compressCompare: Flow<Boolean> = prefsData.map { it[Keys.COMPRESS_COMPARE] ?: true }

    suspend fun setCompressCompare(enabled: Boolean) {
        ds.edit { it[Keys.COMPRESS_COMPARE] = enabled }
    }

    // ---- 图库页偏好（列数 / 排序 / 大图标记阈值） ----

    /** 图库每行图片数；与捏合缩放共用，范围 [MIN_GALLERY_COLUMNS, MAX_GALLERY_COLUMNS] */
    val galleryColumns: Flow<Int> = prefsData.map {
        (it[Keys.GALLERY_COLUMNS] ?: DEFAULT_GALLERY_COLUMNS)
            .coerceIn(MIN_GALLERY_COLUMNS, MAX_GALLERY_COLUMNS)
    }

    suspend fun setGalleryColumns(columns: Int) {
        ds.edit { it[Keys.GALLERY_COLUMNS] = columns.coerceIn(MIN_GALLERY_COLUMNS, MAX_GALLERY_COLUMNS) }
    }

    /** 图库排列顺序（详情页左右滑动共用此排序） */
    val gallerySort: Flow<GallerySort> = prefsData.map { GallerySort.fromName(it[Keys.GALLERY_SORT]) }

    suspend fun setGallerySort(sort: GallerySort) {
        ds.edit { it[Keys.GALLERY_SORT] = sort.name }
    }

    /** QQ 来源图片的排列方式（日期 / 大小，升序 / 降序） */
    val qqSourceSort: Flow<SourceSort> = prefsData.map { SourceSort.fromName(it[Keys.QQ_SOURCE_SORT]) }

    suspend fun setQqSourceSort(sort: SourceSort) {
        ds.edit { it[Keys.QQ_SOURCE_SORT] = sort.name }
    }

    /** 清理规则预览页的排序（各规则共用） */
    val cleanupRuleSort: Flow<SourceSort> = prefsData.map { SourceSort.fromName(it[Keys.CLEANUP_RULE_SORT]) }

    suspend fun setCleanupRuleSort(sort: SourceSort) {
        ds.edit { it[Keys.CLEANUP_RULE_SORT] = sort.name }
    }

    /** 回收站的排序（日期按移入回收站的时间） */
    val recycleBinSort: Flow<SourceSort> = prefsData.map { SourceSort.fromName(it[Keys.RECYCLE_BIN_SORT]) }

    suspend fun setRecycleBinSort(sort: SourceSort) {
        ds.edit { it[Keys.RECYCLE_BIN_SORT] = sort.name }
    }

    /** 视频页的排序 */
    val videoSort: Flow<SourceSort> = prefsData.map { SourceSort.fromName(it[Keys.VIDEO_SORT]) }

    suspend fun setVideoSort(sort: SourceSort) {
        ds.edit { it[Keys.VIDEO_SORT] = sort.name }
    }

    /** 视频页包含收到的文件（QQfile_recv，多是手动下载的群文件）；默认只看聊天视频 */
    val videoIncludeFiles: Flow<Boolean> = prefsData.map { it[Keys.VIDEO_INCLUDE_FILES] ?: false }

    suspend fun setVideoIncludeFiles(include: Boolean) {
        ds.edit { it[Keys.VIDEO_INCLUDE_FILES] = include }
    }

    /** QQ 来源分类列表的排序 */
    val qqSourceFacetOrder: Flow<SourceFacetOrder> = prefsData.map { SourceFacetOrder.fromName(it[Keys.QQ_SOURCE_FACET_ORDER]) }

    suspend fun setQqSourceFacetOrder(order: SourceFacetOrder) {
        ds.edit { it[Keys.QQ_SOURCE_FACET_ORDER] = order.name }
    }

    /** 超过该字节数的图片，在缩略图右上角显示「MB+」大图标记 */
    val galleryLargeThresholdBytes: Flow<Long> = prefsData.map {
        it[Keys.GALLERY_LARGE_BYTES] ?: DEFAULT_LARGE_THRESHOLD_BYTES
    }

    suspend fun setGalleryLargeThresholdBytes(bytes: Long) {
        ds.edit { it[Keys.GALLERY_LARGE_BYTES] = bytes.coerceAtLeast(MIN_LARGE_THRESHOLD_BYTES) }
    }

    /** 图库合并同一张 QQ 图片的原图 / 预览图 / 缩略图（只显示最大的那张，左下角显示版本数），默认开启 */
    val galleryStackVariants: Flow<Boolean> = prefsData.map { it[Keys.GALLERY_STACK_VARIANTS] ?: true }

    suspend fun setGalleryStackVariants(enabled: Boolean) {
        ds.edit { it[Keys.GALLERY_STACK_VARIANTS] = enabled }
    }

    /** 图片网格右侧的快速滚动条（滚动时出现，按住拖动快速定位），默认开启 */
    val fastScrollEnabled: Flow<Boolean> = prefsData.map { it[Keys.FAST_SCROLL] ?: true }

    suspend fun setFastScrollEnabled(enabled: Boolean) {
        ds.edit { it[Keys.FAST_SCROLL] = enabled }
    }

    /** 归档的图片也在图库、来源等处照常显示（像收藏一样）；默认关闭，只在归档页出现 */
    val archiveVisible: Flow<Boolean> = prefsData.map { it[Keys.ARCHIVE_VISIBLE] ?: false }.distinctUntilChanged()

    suspend fun setArchiveVisible(visible: Boolean) {
        ds.edit { it[Keys.ARCHIVE_VISIBLE] = visible }
    }

    /** 已完成的图片家族回填版本（见 PictureVariantIndexer） */
    val variantIndexVersion: Flow<Int> = prefsData.map { it[Keys.VARIANT_INDEX_VERSION] ?: 0 }

    suspend fun setVariantIndexVersion(version: Int) {
        ds.edit { it[Keys.VARIANT_INDEX_VERSION] = version }
    }

    /** 已完成的图片尺寸核对版本（见 ImageSizeBackfill） */
    val imageSizeCheckVersion: Flow<Int> = prefsData.map { it[Keys.IMAGE_SIZE_CHECK_VERSION] ?: 0 }

    suspend fun setImageSizeCheckVersion(version: Int) {
        ds.edit { it[Keys.IMAGE_SIZE_CHECK_VERSION] = version }
    }

    // ---- 外观 ----

    val themeSettings: Flow<ThemeSettings> = prefsData.map { prefs ->
        ThemeSettings(
            mode = prefs[Keys.THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            dynamicColor = prefs[Keys.DYNAMIC_COLOR] ?: true,
            seedColor = prefs[Keys.SEED_COLOR] ?: ThemeSettings.DEFAULT_SEED_COLOR,
            style = prefs[Keys.PALETTE_STYLE]?.let { runCatching { PaletteStyle.valueOf(it) }.getOrNull() } ?: PaletteStyle.VIBRANT,
            pureBlack = prefs[Keys.PURE_BLACK] ?: false,
        )
    }.distinctUntilChanged()

    suspend fun setThemeMode(mode: ThemeMode) {
        ds.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setDynamicColorEnabled(enabled: Boolean) {
        ds.edit { it[Keys.DYNAMIC_COLOR] = enabled }
    }

    suspend fun setSeedColor(color: Int) {
        ds.edit { it[Keys.SEED_COLOR] = color }
    }

    suspend fun setPaletteStyle(style: PaletteStyle) {
        ds.edit { it[Keys.PALETTE_STYLE] = style.name }
    }

    suspend fun setPureBlack(enabled: Boolean) {
        ds.edit { it[Keys.PURE_BLACK] = enabled }
    }

    // ---- 首次引导 ----

    /** 首次打开的引导做完了（或已经有图片的老用户） */
    val onboardingDone: Flow<Boolean> = prefsData.map { it[Keys.ONBOARDING_DONE] ?: false }

    suspend fun setOnboardingDone(done: Boolean) {
        ds.edit { it[Keys.ONBOARDING_DONE] = done }
    }

    // ---- 通知权限（Android 13+）：第一次有后台任务时问一次，之后由用户在系统设置里改 ----

    val notificationPermissionAsked: Flow<Boolean> = prefsData.map { it[Keys.NOTIFICATION_ASKED] ?: false }

    suspend fun setNotificationPermissionAsked() {
        ds.edit { it[Keys.NOTIFICATION_ASKED] = true }
    }

    // ---- 优雅动画：true=缩略图共享元素（默认），false=缩放进入+飞出下滑 ----

    val detailSharedTransition: Flow<Boolean> = prefsData.map { it[Keys.DETAIL_SHARED_TRANSITION] ?: true }

    suspend fun setDetailSharedTransition(enabled: Boolean) {
        ds.edit { it[Keys.DETAIL_SHARED_TRANSITION] = enabled }
    }

    // ---- 导出相册子目录（保存图片到 Pictures/QAlbum/<子目录>）----

    /** 用户创建的导出子目录名列表；缺省空（仅根目录） */
    val exportSubdirs: Flow<List<String>> = prefsData.map { decodeSubdirs(it[Keys.EXPORT_SUBDIRS]) }

    /** 新建导出子目录（清洗非法字符 + 去重）；fire-and-forget 走 appScope 落盘，避免页面销毁丢写 */
    fun addExportSubdir(name: String) {
        val clean = sanitizeExportSubdir(name) ?: return
        appScope.launch {
            ds.edit { prefs ->
                val current = decodeSubdirs(prefs[Keys.EXPORT_SUBDIRS])
                if (current.none { it.equals(clean, ignoreCase = true) }) {
                    prefs[Keys.EXPORT_SUBDIRS] = cleanupJson.encodeToString(current + clean)
                }
            }
        }
    }

    /** 删除导出子目录（仅移除设置项，不影响已导出到系统相册的文件） */
    fun removeExportSubdir(name: String) {
        appScope.launch {
            ds.edit { prefs ->
                val current = decodeSubdirs(prefs[Keys.EXPORT_SUBDIRS])
                prefs[Keys.EXPORT_SUBDIRS] = cleanupJson.encodeToString(current.filterNot { it == name })
            }
        }
    }

    private fun decodeSubdirs(raw: String?): List<String> =
        raw?.let { runCatching { cleanupJson.decodeFromString<List<String>>(it) }.getOrNull() } ?: emptyList()

    companion object {
        const val DEFAULT_CHATPIC_PATH =
            "/storage/emulated/0/Android/data/com.tencent.mobileqq/Tencent/MobileQQ/chatpic"

        const val DEFAULT_COMPRESS_MAX_SIDE = 2560

        const val DEFAULT_COMPRESS_QUALITY = 80
        const val MIN_COMPRESS_QUALITY = 30
        const val MAX_COMPRESS_QUALITY = 95

        /** 设置与压缩对比页可选的压缩质量 */
        val COMPRESS_QUALITY_OPTIONS = listOf(90, 80, 70, 60, 50)

        const val DEFAULT_GALLERY_COLUMNS = 4
        const val MIN_GALLERY_COLUMNS = 2
        const val MAX_GALLERY_COLUMNS = 6

        const val DEFAULT_LARGE_THRESHOLD_BYTES = 1L * 1024 * 1024
        const val MIN_LARGE_THRESHOLD_BYTES = 100L * 1024

        /** 清洗导出子目录名：去空白、剔除文件系统非法字符、限长；非法或空返回 null */
        fun sanitizeExportSubdir(name: String): String? {
            val cleaned = name.trim().replace(Regex("""[/\\:*?"<>|]"""), "").trim()
            return cleaned.ifBlank { null }?.take(40)
        }
    }

    private object Keys {
        val ACCESS_MODE = stringPreferencesKey("access_mode")
        val CHATPIC_PATH = stringPreferencesKey("chatpic_path")
        val QQ_SOURCES_COMPLETE = booleanPreferencesKey("qq_sources_complete")
        val QQ_READ_BASELINE = stringPreferencesKey("qq_read_baseline")
        val QQ_AUTO_REFRESH = booleanPreferencesKey("qq_auto_refresh")
        val QQ_PARSE_VERSION = intPreferencesKey("qq_parse_version")

        val QUARANTINE_DAYS = intPreferencesKey("quarantine_days")
        val COMPRESS_MAX_SIDE = intPreferencesKey("compress_max_side")
        val COMPRESS_KEEP_ORIGINAL = booleanPreferencesKey("compress_keep_original")
        val COMPRESS_QUALITY = intPreferencesKey("compress_quality")
        val COMPRESS_COMPARE = booleanPreferencesKey("compress_compare")
        val OWNERSHIP_REPAIRED = booleanPreferencesKey("compress_ownership_repaired")

        val GALLERY_COLUMNS = intPreferencesKey("gallery_columns")
        val GALLERY_SORT = stringPreferencesKey("gallery_sort")
        val GALLERY_LARGE_BYTES = longPreferencesKey("gallery_large_bytes")
        val GALLERY_STACK_VARIANTS = booleanPreferencesKey("gallery_stack_variants")
        val FAST_SCROLL = booleanPreferencesKey("fast_scroll")
        val ARCHIVE_VISIBLE = booleanPreferencesKey("archive_visible")
        val VARIANT_INDEX_VERSION = intPreferencesKey("variant_index_version")
        val IMAGE_SIZE_CHECK_VERSION = intPreferencesKey("image_size_check_version")
        val QQ_SOURCE_SORT = stringPreferencesKey("qq_source_sort")
        val CLEANUP_RULE_SORT = stringPreferencesKey("cleanup_rule_sort")
        val RECYCLE_BIN_SORT = stringPreferencesKey("recycle_bin_sort")
        val VIDEO_SORT = stringPreferencesKey("video_sort")
        val VIDEO_INCLUDE_FILES = booleanPreferencesKey("video_include_files")
        val SCHEDULED_CLEANUP = stringSetPreferencesKey("scheduled_cleanup_rules")
        val SCHEDULED_CLEANUP_LAST = stringPreferencesKey("scheduled_cleanup_last")
        val AUTO_SCAN = booleanPreferencesKey("auto_scan")
        val AUTO_SCAN_LAST = stringPreferencesKey("auto_scan_last")
        val QQ_SOURCE_FACET_ORDER = stringPreferencesKey("qq_source_facet_order")

        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val SEED_COLOR = intPreferencesKey("seed_color")
        val PALETTE_STYLE = stringPreferencesKey("palette_style")
        val PURE_BLACK = booleanPreferencesKey("pure_black")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val NOTIFICATION_ASKED = booleanPreferencesKey("notification_permission_asked")
        val DETAIL_SHARED_TRANSITION = booleanPreferencesKey("detail_shared_transition")
        val EXPORT_SUBDIRS = stringPreferencesKey("export_subdirs")
    }
}
