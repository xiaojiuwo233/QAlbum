package com.xjw.qalbum.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.xjw.qalbum.domain.model.AccessMode
import com.xjw.qalbum.domain.model.AnalysisConfig
import com.xjw.qalbum.domain.model.AutoFavoriteConfig
import com.xjw.qalbum.domain.model.CleanupConfig
import com.xjw.qalbum.domain.model.DuplicateStrictness
import com.xjw.qalbum.domain.model.GallerySort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// 顶层扩展：每个 Context 共享同一个 DataStore 实例
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "qalbum_settings")

/**
 * 设置仓库：通过 DataStore Preferences 持久化用户配置
 *
 * 这里只放轻量级、跨页面共享的配置；耗时/大型配置应放数据库。
 */
class SettingsRepository(
    private val context: Context,
    private val appScope: CoroutineScope,
) {

    private val ds = context.dataStore

    private val cleanupJson = Json { ignoreUnknownKeys = true }

    // ---- 权限模式 ----

    val accessMode: Flow<AccessMode> = ds.data.map { prefs ->
        val raw = prefs[Keys.ACCESS_MODE] ?: AccessMode.SHIZUKU.name
        runCatching { AccessMode.valueOf(raw) }.getOrDefault(AccessMode.SHIZUKU)
    }

    suspend fun setAccessMode(mode: AccessMode) {
        ds.edit { it[Keys.ACCESS_MODE] = mode.name }
    }

    // ---- 扫描路径 ----

    val chatpicPath: Flow<String> = ds.data.map { it[Keys.CHATPIC_PATH] ?: DEFAULT_CHATPIC_PATH }

    suspend fun setChatpicPath(path: String) {
        ds.edit { it[Keys.CHATPIC_PATH] = path }
    }

    // ---- AI 配置 ----

    val aiEnabled: Flow<Boolean> = ds.data.map { it[Keys.AI_ENABLED] ?: false }

    suspend fun setAiEnabled(enabled: Boolean) {
        ds.edit { it[Keys.AI_ENABLED] = enabled }
    }

    val aiEndpoint: Flow<String> = ds.data.map { it[Keys.AI_ENDPOINT] ?: "" }

    suspend fun setAiEndpoint(endpoint: String) {
        ds.edit { it[Keys.AI_ENDPOINT] = endpoint }
    }

    val aiApiKey: Flow<String> = ds.data.map { it[Keys.AI_API_KEY] ?: "" }

    suspend fun setAiApiKey(key: String) {
        ds.edit { it[Keys.AI_API_KEY] = key }
    }

    val aiModel: Flow<String> = ds.data.map { it[Keys.AI_MODEL] ?: "gpt-4o-mini" }

    suspend fun setAiModel(model: String) {
        ds.edit { it[Keys.AI_MODEL] = model }
    }

    val aiEmbeddingModel: Flow<String> = ds.data.map { it[Keys.AI_EMBEDDING_MODEL] ?: "text-embedding-3-small" }

    suspend fun setAiEmbeddingModel(model: String) {
        ds.edit { it[Keys.AI_EMBEDDING_MODEL] = model }
    }

    /** 是否过滤/关闭模型思考（thinking）：开启后请求会要求服务端禁用思考，并剥离响应中的思考内容 */
    val aiDisableThinking: Flow<Boolean> = ds.data.map { it[Keys.AI_DISABLE_THINKING] ?: true }

    suspend fun setAiDisableThinking(enabled: Boolean) {
        ds.edit { it[Keys.AI_DISABLE_THINKING] = enabled }
    }

    /** AI 标签 / 向量阶段的并发请求数（同时向接口发起多少张图的请求） */
    val aiConcurrency: Flow<Int> = ds.data.map {
        (it[Keys.AI_CONCURRENCY] ?: DEFAULT_AI_CONCURRENCY).coerceIn(MIN_AI_CONCURRENCY, MAX_AI_CONCURRENCY)
    }

    suspend fun setAiConcurrency(value: Int) {
        ds.edit { it[Keys.AI_CONCURRENCY] = value.coerceIn(MIN_AI_CONCURRENCY, MAX_AI_CONCURRENCY) }
    }

    /** Chat 调用的 max_tokens 上限；思考模型需较大额度，否则思考占满后正文（content）为空 */
    val aiMaxTokens: Flow<Int> = ds.data.map {
        (it[Keys.AI_MAX_TOKENS] ?: DEFAULT_AI_MAX_TOKENS).coerceIn(MIN_AI_MAX_TOKENS, MAX_AI_MAX_TOKENS)
    }

    suspend fun setAiMaxTokens(value: Int) {
        ds.edit { it[Keys.AI_MAX_TOKENS] = value.coerceIn(MIN_AI_MAX_TOKENS, MAX_AI_MAX_TOKENS) }
    }

    // ---- 回收站保留时长 ----

    val quarantineRetentionDays: Flow<Int> = ds.data.map { it[Keys.QUARANTINE_DAYS] ?: 30 }

    suspend fun setQuarantineRetentionDays(days: Int) {
        ds.edit { it[Keys.QUARANTINE_DAYS] = days }
    }

    // ---- 分析（hash/质量/OCR/AI/向量）每项开关 + 数量 + 排序方向 ----

    /**
     * 每个分析阶段的开关与数量上限（hash 数量 / OCR、AI、向量 各自开关+数量）。
     * 缺省或解析失败回退到 [AnalysisConfig.DEFAULT]。
     */
    val analysisConfig: Flow<AnalysisConfig> = ds.data.map { prefs ->
        prefs[Keys.ANALYSIS_CONFIG]?.let { raw ->
            runCatching { cleanupJson.decodeFromString<AnalysisConfig>(raw) }.getOrNull()
        } ?: AnalysisConfig.DEFAULT
    }

    suspend fun setAnalysisConfig(config: AnalysisConfig) {
        ds.edit { it[Keys.ANALYSIS_CONFIG] = cleanupJson.encodeToString(config) }
    }

    /** 分析顺序：true=从早到晚（mtime 升序），false=从晚到早（降序） */
    val analysisOldestFirst: Flow<Boolean> = ds.data.map { it[Keys.ANALYSIS_OLDEST_FIRST] ?: true }

    suspend fun setAnalysisOldestFirst(oldestFirst: Boolean) {
        ds.edit { it[Keys.ANALYSIS_OLDEST_FIRST] = oldestFirst }
    }

    /** 每次「生成审核清理」最多纳入多少张候选图（遵守分析顺序，从早/晚开始取） */
    val reviewLimit: Flow<Int> = ds.data.map {
        (it[Keys.REVIEW_LIMIT] ?: DEFAULT_REVIEW_LIMIT).coerceIn(MIN_REVIEW_LIMIT, MAX_REVIEW_LIMIT)
    }

    suspend fun setReviewLimit(limit: Int) {
        ds.edit { it[Keys.REVIEW_LIMIT] = limit.coerceIn(MIN_REVIEW_LIMIT, MAX_REVIEW_LIMIT) }
    }

    /** 审核时是否把所有重复图一次性纳入（不受「每次审核数量」限制），避免重复图分多批清不完 */
    val cleanupAllDuplicates: Flow<Boolean> = ds.data.map { it[Keys.CLEANUP_ALL_DUPLICATES] ?: false }

    suspend fun setCleanupAllDuplicates(enabled: Boolean) {
        ds.edit { it[Keys.CLEANUP_ALL_DUPLICATES] = enabled }
    }

    /** 视觉重复组成员数上限：超过此数的视觉相似组视为误聚整组丢弃（精确 xxHash 重复不受限）。改后需重新查重。 */
    val maxDuplicateGroupSize: Flow<Int> = ds.data.map {
        (it[Keys.MAX_DUP_GROUP_SIZE] ?: DEFAULT_MAX_DUP_GROUP_SIZE).coerceIn(MIN_MAX_DUP_GROUP_SIZE, MAX_MAX_DUP_GROUP_SIZE)
    }

    suspend fun setMaxDuplicateGroupSize(value: Int) {
        ds.edit { it[Keys.MAX_DUP_GROUP_SIZE] = value.coerceIn(MIN_MAX_DUP_GROUP_SIZE, MAX_MAX_DUP_GROUP_SIZE) }
    }

    /** 重复检测严格度（视觉阈值档位）。缺省回退 [DuplicateStrictness.DEFAULT]；改后需重新查重才生效。 */
    val duplicateStrictness: Flow<DuplicateStrictness> = ds.data.map {
        DuplicateStrictness.fromName(it[Keys.DUPLICATE_STRICTNESS])
    }

    suspend fun setDuplicateStrictness(value: DuplicateStrictness) {
        ds.edit { it[Keys.DUPLICATE_STRICTNESS] = value.name }
    }

    // ---- 图库页偏好（列数 / 排序 / 大图标记阈值） ----

    /** 图库每行图片数；与捏合缩放共用，范围 [MIN_GALLERY_COLUMNS, MAX_GALLERY_COLUMNS] */
    val galleryColumns: Flow<Int> = ds.data.map {
        (it[Keys.GALLERY_COLUMNS] ?: DEFAULT_GALLERY_COLUMNS)
            .coerceIn(MIN_GALLERY_COLUMNS, MAX_GALLERY_COLUMNS)
    }

    suspend fun setGalleryColumns(columns: Int) {
        ds.edit { it[Keys.GALLERY_COLUMNS] = columns.coerceIn(MIN_GALLERY_COLUMNS, MAX_GALLERY_COLUMNS) }
    }

    /** 图库排列顺序（详情页左右滑动共用此排序） */
    val gallerySort: Flow<GallerySort> = ds.data.map { GallerySort.fromName(it[Keys.GALLERY_SORT]) }

    suspend fun setGallerySort(sort: GallerySort) {
        ds.edit { it[Keys.GALLERY_SORT] = sort.name }
    }

    /** 超过该字节数的图片，在缩略图右上角显示「MB+」大图标记 */
    val galleryLargeThresholdBytes: Flow<Long> = ds.data.map {
        it[Keys.GALLERY_LARGE_BYTES] ?: DEFAULT_LARGE_THRESHOLD_BYTES
    }

    suspend fun setGalleryLargeThresholdBytes(bytes: Long) {
        ds.edit { it[Keys.GALLERY_LARGE_BYTES] = bytes.coerceAtLeast(MIN_LARGE_THRESHOLD_BYTES) }
    }

    // ---- 清理规则配置 ----

    /** 每类清理维度的开关 + 门限；缺省或解析失败回退到 [CleanupConfig.DEFAULT]（平衡档） */
    val cleanupConfig: Flow<CleanupConfig> = ds.data.map { prefs ->
        prefs[Keys.CLEANUP_CONFIG]?.let { raw ->
            runCatching { cleanupJson.decodeFromString<CleanupConfig>(raw) }.getOrNull()
        } ?: CleanupConfig.DEFAULT
    }

    suspend fun setCleanupConfig(config: CleanupConfig) {
        ds.edit { it[Keys.CLEANUP_CONFIG] = cleanupJson.encodeToString(config) }
    }

    // ---- 自动收藏配置（分析阶段按敏感信息自动收藏，默认关闭）----

    /** 自动收藏规则；缺省或解析失败回退到 [AutoFavoriteConfig.DEFAULT]（关闭） */
    val autoFavoriteConfig: Flow<AutoFavoriteConfig> = ds.data.map { prefs ->
        prefs[Keys.AUTO_FAVORITE_CONFIG]?.let { raw ->
            runCatching { cleanupJson.decodeFromString<AutoFavoriteConfig>(raw) }.getOrNull()
        } ?: AutoFavoriteConfig.DEFAULT
    }

    suspend fun setAutoFavoriteConfig(config: AutoFavoriteConfig) {
        ds.edit { it[Keys.AUTO_FAVORITE_CONFIG] = cleanupJson.encodeToString(config) }
    }

    // ---- 主题动态取色 ----

    val dynamicColorEnabled: Flow<Boolean> = ds.data.map { it[Keys.DYNAMIC_COLOR] ?: true }

    suspend fun setDynamicColorEnabled(enabled: Boolean) {
        ds.edit { it[Keys.DYNAMIC_COLOR] = enabled }
    }

    // ---- 图库动画增强：true=缩略图共享元素（实验性），false=缩放进入+飞出下滑（默认稳定）----

    val detailSharedTransition: Flow<Boolean> = ds.data.map { it[Keys.DETAIL_SHARED_TRANSITION] ?: false }

    suspend fun setDetailSharedTransition(enabled: Boolean) {
        ds.edit { it[Keys.DETAIL_SHARED_TRANSITION] = enabled }
    }

    // ---- 导出相册子目录（保存图片到 Pictures/QAlbum/<子目录>）----

    /** 用户创建的导出子目录名列表；缺省空（仅根目录） */
    val exportSubdirs: Flow<List<String>> = ds.data.map { decodeSubdirs(it[Keys.EXPORT_SUBDIRS]) }

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

        const val DEFAULT_REVIEW_LIMIT = 200
        const val MIN_REVIEW_LIMIT = 20
        const val MAX_REVIEW_LIMIT = 5_000

        const val DEFAULT_MAX_DUP_GROUP_SIZE = 20
        const val MIN_MAX_DUP_GROUP_SIZE = 2
        const val MAX_MAX_DUP_GROUP_SIZE = 500

        const val DEFAULT_AI_CONCURRENCY = 2
        const val MIN_AI_CONCURRENCY = 1
        const val MAX_AI_CONCURRENCY = 16

        const val DEFAULT_AI_MAX_TOKENS = 2048
        const val MIN_AI_MAX_TOKENS = 256
        const val MAX_AI_MAX_TOKENS = 8192

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

        val AI_ENABLED = booleanPreferencesKey("ai_enabled")
        val AI_ENDPOINT = stringPreferencesKey("ai_endpoint")
        val AI_API_KEY = stringPreferencesKey("ai_api_key")
        val AI_MODEL = stringPreferencesKey("ai_model")
        val AI_EMBEDDING_MODEL = stringPreferencesKey("ai_embedding_model")
        val AI_DISABLE_THINKING = booleanPreferencesKey("ai_disable_thinking")
        val AI_CONCURRENCY = intPreferencesKey("ai_concurrency")
        val AI_MAX_TOKENS = intPreferencesKey("ai_max_tokens")

        val QUARANTINE_DAYS = intPreferencesKey("quarantine_days")
        val ANALYSIS_CONFIG = stringPreferencesKey("analysis_config")
        val ANALYSIS_OLDEST_FIRST = booleanPreferencesKey("analysis_oldest_first")
        val REVIEW_LIMIT = intPreferencesKey("review_limit")
        val CLEANUP_ALL_DUPLICATES = booleanPreferencesKey("cleanup_all_duplicates")
        val MAX_DUP_GROUP_SIZE = intPreferencesKey("max_dup_group_size")
        val DUPLICATE_STRICTNESS = stringPreferencesKey("duplicate_strictness")

        val GALLERY_COLUMNS = intPreferencesKey("gallery_columns")
        val GALLERY_SORT = stringPreferencesKey("gallery_sort")
        val GALLERY_LARGE_BYTES = longPreferencesKey("gallery_large_bytes")

        val CLEANUP_CONFIG = stringPreferencesKey("cleanup_config")
        val AUTO_FAVORITE_CONFIG = stringPreferencesKey("auto_favorite_config")

        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val DETAIL_SHARED_TRANSITION = booleanPreferencesKey("detail_shared_transition")
        val EXPORT_SUBDIRS = stringPreferencesKey("export_subdirs")
    }
}
