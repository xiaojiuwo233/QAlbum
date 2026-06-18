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
import com.xjw.qalbum.domain.model.AutoFavoriteConfig
import com.xjw.qalbum.domain.model.CleanupConfig
import com.xjw.qalbum.domain.model.GallerySort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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
class SettingsRepository(private val context: Context) {

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

    // ---- 分析（hash/质量/OCR/AI/向量）每次处理上限 + 排序方向 ----

    /** 每次分析最多处理多少张「未完成」图片（各逐图阶段共用此上限） */
    val analysisLimit: Flow<Int> = ds.data.map { it[Keys.ANALYSIS_LIMIT] ?: DEFAULT_ANALYSIS_LIMIT }

    suspend fun setAnalysisLimit(limit: Int) {
        ds.edit { it[Keys.ANALYSIS_LIMIT] = limit.coerceIn(MIN_ANALYSIS_LIMIT, MAX_ANALYSIS_LIMIT) }
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

    companion object {
        const val DEFAULT_CHATPIC_PATH =
            "/storage/emulated/0/Android/data/com.tencent.mobileqq/Tencent/MobileQQ/chatpic"

        const val DEFAULT_ANALYSIS_LIMIT = 500
        const val MIN_ANALYSIS_LIMIT = 50
        const val MAX_ANALYSIS_LIMIT = 20_000

        const val DEFAULT_REVIEW_LIMIT = 200
        const val MIN_REVIEW_LIMIT = 20
        const val MAX_REVIEW_LIMIT = 5_000

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
        val ANALYSIS_LIMIT = intPreferencesKey("analysis_limit")
        val ANALYSIS_OLDEST_FIRST = booleanPreferencesKey("analysis_oldest_first")
        val REVIEW_LIMIT = intPreferencesKey("review_limit")

        val GALLERY_COLUMNS = intPreferencesKey("gallery_columns")
        val GALLERY_SORT = stringPreferencesKey("gallery_sort")
        val GALLERY_LARGE_BYTES = longPreferencesKey("gallery_large_bytes")

        val CLEANUP_CONFIG = stringPreferencesKey("cleanup_config")
        val AUTO_FAVORITE_CONFIG = stringPreferencesKey("auto_favorite_config")

        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val DETAIL_SHARED_TRANSITION = booleanPreferencesKey("detail_shared_transition")
    }
}
