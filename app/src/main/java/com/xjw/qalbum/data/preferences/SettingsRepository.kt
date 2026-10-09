package com.xjw.qalbum.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
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
import com.xjw.qalbum.domain.model.SourceFacetOrder
import com.xjw.qalbum.domain.model.SourceSort
import com.xjw.qalbum.service.ai.AiPrivacyGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
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

    suspend fun setChatpicPath(path: String) {
        // 规范化 + 拒绝空值：root="" 会让扫描把根前缀算成 "/"，全部目录快照被判「消失」，
        // 后台增量扫描随即把 200 万行批量标记 missing（图库瞬间清空），因此空串绝不落盘
        val normalized = path.trim().trimEnd('/')
        if (normalized.isBlank()) return
        ds.edit { it[Keys.CHATPIC_PATH] = normalized }
    }

    // ---- AI 配置 ----

    /** 请求使用同一次 DataStore 快照，避免并发改设置时混用地址、模型与密钥。 */
    val aiConnection: Flow<AiConnectionConfig> = prefsData.map {
        AiConnectionConfig(
            enabled = it[Keys.AI_ENABLED] ?: false,
            endpoint = it[Keys.AI_ENDPOINT] ?: "",
            apiKey = it[Keys.AI_API_KEY] ?: "",
            model = it[Keys.AI_MODEL] ?: "gpt-4o-mini",
            embeddingModel = it[Keys.AI_EMBEDDING_MODEL] ?: "text-embedding-3-small",
            disableThinking = it[Keys.AI_DISABLE_THINKING] ?: true,
            maxTokens = (it[Keys.AI_MAX_TOKENS] ?: DEFAULT_AI_MAX_TOKENS).coerceIn(MIN_AI_MAX_TOKENS, MAX_AI_MAX_TOKENS),
        )
    }

    val aiEnabled: Flow<Boolean> = prefsData.map { it[Keys.AI_ENABLED] ?: false }

    suspend fun setAiEnabled(enabled: Boolean) {
        ds.edit { it[Keys.AI_ENABLED] = enabled }
    }

    val aiEndpoint: Flow<String> = prefsData.map { it[Keys.AI_ENDPOINT] ?: "" }

    suspend fun setAiEndpoint(endpoint: String) {
        // 空串合法（未配置 = AI 不可用），只做去空白规范化；末尾斜杠由 resolveUrl 统一处理
        val trimmed = endpoint.trim()
        ds.edit {
            val previous = (it[Keys.AI_ENDPOINT] ?: "").trim()
            it[Keys.AI_ENDPOINT] = trimmed
            // 确认绑定到具体 endpoint；任意地址变化（含公网 A→公网 B）必须原子清除旧授权。
            if (previous != trimmed) {
                it.remove(Keys.AI_PUBLIC_ENDPOINT_ACK_ENDPOINT)
                it.remove(Keys.AI_PUBLIC_ENDPOINT_ACK)
            }
        }
    }

    /**
     * 用户是否已在隐私弹窗确认过「向公网 AI 服务上传图片」。
     * 上传链路（OpenAiCompatClient 带图请求）以此为最终闸门：UI 确认弹窗被绕过时兜底拒发。
     */
    val aiPublicEndpointAcknowledged: Flow<Boolean> =
        prefsData.map { prefs ->
            val current = (prefs[Keys.AI_ENDPOINT] ?: "").trim()
            AiPrivacyGuard.classify(current) == AiPrivacyGuard.Safety.PUBLIC_INTERNET &&
                prefs[Keys.AI_PUBLIC_ENDPOINT_ACK_ENDPOINT] == current
        }

    /**
     * 上传前对请求已捕获的地址做同快照校验，避免 A 请求与并发保存/确认 B 地址交错时借用 B 的授权。
     */
    suspend fun isAiPublicEndpointAcknowledged(endpoint: String): Boolean {
        val requested = endpoint.trim()
        val prefs = prefsData.first()
        val current = (prefs[Keys.AI_ENDPOINT] ?: "").trim()
        return requested == current &&
            AiPrivacyGuard.classify(requested) == AiPrivacyGuard.Safety.PUBLIC_INTERNET &&
            prefs[Keys.AI_PUBLIC_ENDPOINT_ACK_ENDPOINT] == requested
    }

    /**
     * 历史 status='deleted' 行的派生数据（OCR/FTS/标签/向量/排斥）是否已一次性清理完成。
     * 永久删除路径现已内联级联清理，此标记只管旧版本删除留下的存量残留（QuarantinePurgeWorker 执行）。
     */
    val legacyDerivedDataPurged: Flow<Boolean> =
        prefsData.map { it[Keys.LEGACY_DERIVED_DATA_PURGED] ?: false }

    suspend fun setLegacyDerivedDataPurged() {
        ds.edit { it[Keys.LEGACY_DERIVED_DATA_PURGED] = true }
    }

    suspend fun setAiPublicEndpointAcknowledged(acked: Boolean) {
        ds.edit { prefs ->
            val current = (prefs[Keys.AI_ENDPOINT] ?: "").trim()
            if (acked && AiPrivacyGuard.classify(current) == AiPrivacyGuard.Safety.PUBLIC_INTERNET) {
                prefs[Keys.AI_PUBLIC_ENDPOINT_ACK_ENDPOINT] = current
            } else {
                prefs.remove(Keys.AI_PUBLIC_ENDPOINT_ACK_ENDPOINT)
            }
            // 旧版布尔值不绑定地址，不能迁移为授权；删除后强制为当前地址重新确认一次。
            prefs.remove(Keys.AI_PUBLIC_ENDPOINT_ACK)
        }
    }

    val aiApiKey: Flow<String> = prefsData.map { it[Keys.AI_API_KEY] ?: "" }

    suspend fun setAiApiKey(key: String) {
        ds.edit { it[Keys.AI_API_KEY] = key }
    }

    val aiModel: Flow<String> = prefsData.map { it[Keys.AI_MODEL] ?: "gpt-4o-mini" }

    suspend fun setAiModel(model: String) {
        ds.edit { it[Keys.AI_MODEL] = model }
    }

    val aiEmbeddingModel: Flow<String> = prefsData.map { it[Keys.AI_EMBEDDING_MODEL] ?: "text-embedding-3-small" }

    suspend fun setAiEmbeddingModel(model: String) {
        ds.edit { it[Keys.AI_EMBEDDING_MODEL] = model }
    }

    /** 是否过滤/关闭模型思考（thinking）：开启后请求会要求服务端禁用思考，并剥离响应中的思考内容 */
    val aiDisableThinking: Flow<Boolean> = prefsData.map { it[Keys.AI_DISABLE_THINKING] ?: true }

    suspend fun setAiDisableThinking(enabled: Boolean) {
        ds.edit { it[Keys.AI_DISABLE_THINKING] = enabled }
    }

    /** AI 标签 / 向量阶段的并发请求数（同时向接口发起多少张图的请求） */
    val aiConcurrency: Flow<Int> = prefsData.map {
        (it[Keys.AI_CONCURRENCY] ?: DEFAULT_AI_CONCURRENCY).coerceIn(MIN_AI_CONCURRENCY, MAX_AI_CONCURRENCY)
    }

    suspend fun setAiConcurrency(value: Int) {
        ds.edit { it[Keys.AI_CONCURRENCY] = value.coerceIn(MIN_AI_CONCURRENCY, MAX_AI_CONCURRENCY) }
    }

    /** Chat 调用的 max_tokens 上限；思考模型需较大额度，否则思考占满后正文（content）为空 */
    val aiMaxTokens: Flow<Int> = prefsData.map {
        (it[Keys.AI_MAX_TOKENS] ?: DEFAULT_AI_MAX_TOKENS).coerceIn(MIN_AI_MAX_TOKENS, MAX_AI_MAX_TOKENS)
    }

    suspend fun setAiMaxTokens(value: Int) {
        ds.edit { it[Keys.AI_MAX_TOKENS] = value.coerceIn(MIN_AI_MAX_TOKENS, MAX_AI_MAX_TOKENS) }
    }

    // ---- 回收站保留时长 ----

    val quarantineRetentionDays: Flow<Int> = prefsData.map { it[Keys.QUARANTINE_DAYS] ?: 30 }

    suspend fun setQuarantineRetentionDays(days: Int) {
        ds.edit { it[Keys.QUARANTINE_DAYS] = days }
    }

    // ---- 分析（hash/质量/OCR/AI/向量）每项开关 + 数量 + 排序方向 ----

    /**
     * 每个分析阶段的开关与数量上限（hash 数量 / OCR、AI、向量 各自开关+数量）。
     * 缺省或解析失败回退到 [AnalysisConfig.DEFAULT]。
     */
    val analysisConfig: Flow<AnalysisConfig> = prefsData.map { prefs ->
        prefs[Keys.ANALYSIS_CONFIG]?.let { raw ->
            runCatching { cleanupJson.decodeFromString<AnalysisConfig>(raw) }.getOrNull()
        } ?: AnalysisConfig.DEFAULT
    }

    suspend fun setAnalysisConfig(config: AnalysisConfig) {
        ds.edit { it[Keys.ANALYSIS_CONFIG] = cleanupJson.encodeToString(config) }
    }

    /** 分析顺序：true=从早到晚（mtime 升序），false=从晚到早（降序） */
    val analysisOldestFirst: Flow<Boolean> = prefsData.map { it[Keys.ANALYSIS_OLDEST_FIRST] ?: true }

    suspend fun setAnalysisOldestFirst(oldestFirst: Boolean) {
        ds.edit { it[Keys.ANALYSIS_OLDEST_FIRST] = oldestFirst }
    }

    /** 每次「生成审核清理」最多纳入多少张候选图（遵守分析顺序，从早/晚开始取） */
    val reviewLimit: Flow<Int> = prefsData.map {
        (it[Keys.REVIEW_LIMIT] ?: DEFAULT_REVIEW_LIMIT).coerceIn(MIN_REVIEW_LIMIT, MAX_REVIEW_LIMIT)
    }

    suspend fun setReviewLimit(limit: Int) {
        ds.edit { it[Keys.REVIEW_LIMIT] = limit.coerceIn(MIN_REVIEW_LIMIT, MAX_REVIEW_LIMIT) }
    }

    /**
     * 当前未关闭的审核会话对应的批次 id；null = 无进行中的审核会话。
     * 进程被低内存回收后重建时按此 id 恢复审核页（批次项已落库，见 CleanupViewModel.ensureReviewSessionRestored）。
     */
    val activeReviewBatchId: Flow<Long?> = prefsData.map { it[Keys.ACTIVE_REVIEW_BATCH_ID] }

    /** 记录/清除当前审核批次 id；fire-and-forget 走 appScope 落盘（closeReview 为非挂起调用点，且页面销毁不丢写） */
    fun setActiveReviewBatchId(batchId: Long?) {
        appScope.launch {
            ds.edit { prefs ->
                if (batchId == null) prefs.remove(Keys.ACTIVE_REVIEW_BATCH_ID)
                else prefs[Keys.ACTIVE_REVIEW_BATCH_ID] = batchId
            }
        }
    }

    /** 审核时是否把所有重复图一次性纳入（不受「每次审核数量」限制），避免重复图分多批清不完 */
    val cleanupAllDuplicates: Flow<Boolean> = prefsData.map { it[Keys.CLEANUP_ALL_DUPLICATES] ?: false }

    suspend fun setCleanupAllDuplicates(enabled: Boolean) {
        ds.edit { it[Keys.CLEANUP_ALL_DUPLICATES] = enabled }
    }

    /** 视觉重复组成员数上限：超过此数的视觉相似组视为误聚整组丢弃（精确 xxHash 重复不受限）。改后需重新查重。 */
    val maxDuplicateGroupSize: Flow<Int> = prefsData.map {
        (it[Keys.MAX_DUP_GROUP_SIZE] ?: DEFAULT_MAX_DUP_GROUP_SIZE).coerceIn(MIN_MAX_DUP_GROUP_SIZE, MAX_MAX_DUP_GROUP_SIZE)
    }

    suspend fun setMaxDuplicateGroupSize(value: Int) {
        ds.edit { it[Keys.MAX_DUP_GROUP_SIZE] = value.coerceIn(MIN_MAX_DUP_GROUP_SIZE, MAX_MAX_DUP_GROUP_SIZE) }
    }

    /** 重复检测严格度（视觉阈值档位）。缺省回退 [DuplicateStrictness.DEFAULT]；改后需重新查重才生效。 */
    val duplicateStrictness: Flow<DuplicateStrictness> = prefsData.map {
        DuplicateStrictness.fromName(it[Keys.DUPLICATE_STRICTNESS])
    }

    suspend fun setDuplicateStrictness(value: DuplicateStrictness) {
        ds.edit { it[Keys.DUPLICATE_STRICTNESS] = value.name }
    }

    val duplicateAlgorithmVersion: Flow<Int> = prefsData.map { it[Keys.DUPLICATE_ALGORITHM_VERSION] ?: 0 }

    suspend fun setDuplicateAlgorithmVersion(version: Int) {
        ds.edit { it[Keys.DUPLICATE_ALGORITHM_VERSION] = version }
    }

    /**
     * 视觉相似度学习模型的 4 个参数 [wBlock, wLuma, wEdge, threshold]；未学习返回 null
     * （调用方回退到严格度档位的基础模型）。只存原始 double，不依赖 service 层的模型类型。
     */
    val similarityWeights: Flow<DoubleArray?> = prefsData.map { p ->
        val wb = p[Keys.SIM_W_BLOCK]
        val wl = p[Keys.SIM_W_LUMA]
        val we = p[Keys.SIM_W_EDGE]
        val th = p[Keys.SIM_THRESHOLD]
        if (wb != null && wl != null && we != null && th != null) doubleArrayOf(wb, wl, we, th) else null
    }

    suspend fun setSimilarityWeights(wBlock: Double, wLuma: Double, wEdge: Double, threshold: Double) {
        ds.edit {
            it[Keys.SIM_W_BLOCK] = wBlock
            it[Keys.SIM_W_LUMA] = wLuma
            it[Keys.SIM_W_EDGE] = wEdge
            it[Keys.SIM_THRESHOLD] = threshold
        }
    }

    /** 清除学习成果，回退到档位基础模型。 */
    suspend fun resetSimilarityWeights() {
        ds.edit {
            it.remove(Keys.SIM_W_BLOCK)
            it.remove(Keys.SIM_W_LUMA)
            it.remove(Keys.SIM_W_EDGE)
            it.remove(Keys.SIM_THRESHOLD)
        }
    }

    // ---- 推荐删除（删除偏好学习模型）----

    /** 删除偏好模型（序列化字符串）；未学习返回 null。只存原始字符串，不依赖 service 层模型类型。 */
    val deleteModel: Flow<String?> = prefsData.map { it[Keys.DELETE_MODEL] }

    suspend fun setDeleteModel(serialized: String) {
        ds.edit { it[Keys.DELETE_MODEL] = serialized }
    }

    /** 已采集的删除/保留样本数（冷启动激活判断）。 */
    val deleteSampleCount: Flow<Int> = prefsData.map { it[Keys.DELETE_SAMPLE_COUNT] ?: 0 }

    suspend fun addDeleteSamples(n: Int) {
        ds.edit { it[Keys.DELETE_SAMPLE_COUNT] = (it[Keys.DELETE_SAMPLE_COUNT] ?: 0) + n }
    }

    suspend fun resetDeleteModel() {
        ds.edit {
            it.remove(Keys.DELETE_MODEL)
            it.remove(Keys.DELETE_SAMPLE_COUNT)
        }
    }

    /** 是否启用「推荐删除」分类。 */
    val recommendEnabled: Flow<Boolean> = prefsData.map { it[Keys.RECOMMEND_ENABLED] ?: false }

    suspend fun setRecommendEnabled(enabled: Boolean) {
        ds.edit { it[Keys.RECOMMEND_ENABLED] = enabled }
    }

    /** 每次「推荐删除」纳入的数量上限。 */
    val recommendLimit: Flow<Int> = prefsData.map {
        (it[Keys.RECOMMEND_LIMIT] ?: DEFAULT_RECOMMEND_LIMIT).coerceIn(MIN_RECOMMEND_LIMIT, MAX_RECOMMEND_LIMIT)
    }

    suspend fun setRecommendLimit(value: Int) {
        ds.edit { it[Keys.RECOMMEND_LIMIT] = value.coerceIn(MIN_RECOMMEND_LIMIT, MAX_RECOMMEND_LIMIT) }
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

    /** 已完成的图片家族回填版本（见 PictureVariantIndexer） */
    val variantIndexVersion: Flow<Int> = prefsData.map { it[Keys.VARIANT_INDEX_VERSION] ?: 0 }

    suspend fun setVariantIndexVersion(version: Int) {
        ds.edit { it[Keys.VARIANT_INDEX_VERSION] = version }
    }

    // ---- 清理规则配置 ----

    /** 每类清理维度的开关 + 门限；缺省或解析失败回退到 [CleanupConfig.DEFAULT]（平衡档） */
    val cleanupConfig: Flow<CleanupConfig> = prefsData.map { prefs ->
        prefs[Keys.CLEANUP_CONFIG]?.let { raw ->
            runCatching { cleanupJson.decodeFromString<CleanupConfig>(raw) }.getOrNull()
        } ?: CleanupConfig.DEFAULT
    }

    suspend fun setCleanupConfig(config: CleanupConfig) {
        ds.edit { it[Keys.CLEANUP_CONFIG] = cleanupJson.encodeToString(config) }
    }

    // ---- 自动收藏配置（分析阶段按敏感信息自动收藏，默认关闭）----

    /** 自动收藏规则；缺省或解析失败回退到 [AutoFavoriteConfig.DEFAULT]（关闭） */
    val autoFavoriteConfig: Flow<AutoFavoriteConfig> = prefsData.map { prefs ->
        prefs[Keys.AUTO_FAVORITE_CONFIG]?.let { raw ->
            runCatching { cleanupJson.decodeFromString<AutoFavoriteConfig>(raw) }.getOrNull()
        } ?: AutoFavoriteConfig.DEFAULT
    }

    suspend fun setAutoFavoriteConfig(config: AutoFavoriteConfig) {
        ds.edit { it[Keys.AUTO_FAVORITE_CONFIG] = cleanupJson.encodeToString(config) }
    }

    // ---- 主题动态取色 ----

    val dynamicColorEnabled: Flow<Boolean> = prefsData.map { it[Keys.DYNAMIC_COLOR] ?: true }

    suspend fun setDynamicColorEnabled(enabled: Boolean) {
        ds.edit { it[Keys.DYNAMIC_COLOR] = enabled }
    }

    // ---- 图库动画增强：true=缩略图共享元素（实验性），false=缩放进入+飞出下滑（默认稳定）----

    val detailSharedTransition: Flow<Boolean> = prefsData.map { it[Keys.DETAIL_SHARED_TRANSITION] ?: false }

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

        const val DEFAULT_RECOMMEND_LIMIT = 50
        const val MIN_RECOMMEND_LIMIT = 10
        const val MAX_RECOMMEND_LIMIT = 1000

        /** 冷启动：累计删除/保留样本 ≥ 此值才激活「推荐删除」（之前只默默学习、不推荐）。 */
        const val RECOMMEND_ACTIVATION_SAMPLES = 50

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

        val AI_ENABLED = booleanPreferencesKey("ai_enabled")
        val AI_ENDPOINT = stringPreferencesKey("ai_endpoint")
        val AI_PUBLIC_ENDPOINT_ACK_ENDPOINT = stringPreferencesKey("ai_public_endpoint_ack_endpoint")
        /** 旧版全局布尔授权，仅用于迁移时删除，不能作为当前 endpoint 的授权依据。 */
        val AI_PUBLIC_ENDPOINT_ACK = booleanPreferencesKey("ai_public_endpoint_ack")
        val LEGACY_DERIVED_DATA_PURGED = booleanPreferencesKey("legacy_derived_data_purged")
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
        val ACTIVE_REVIEW_BATCH_ID = longPreferencesKey("active_review_batch_id")
        val CLEANUP_ALL_DUPLICATES = booleanPreferencesKey("cleanup_all_duplicates")
        val MAX_DUP_GROUP_SIZE = intPreferencesKey("max_dup_group_size")
        val DUPLICATE_STRICTNESS = stringPreferencesKey("duplicate_strictness")
        val DUPLICATE_ALGORITHM_VERSION = intPreferencesKey("duplicate_algorithm_version")
        val SIM_W_BLOCK = doublePreferencesKey("sim_w_block")
        val SIM_W_LUMA = doublePreferencesKey("sim_w_luma")
        val SIM_W_EDGE = doublePreferencesKey("sim_w_edge")
        val SIM_THRESHOLD = doublePreferencesKey("sim_threshold")
        val DELETE_MODEL = stringPreferencesKey("delete_model")
        val DELETE_SAMPLE_COUNT = intPreferencesKey("delete_sample_count")
        val RECOMMEND_ENABLED = booleanPreferencesKey("recommend_enabled")
        val RECOMMEND_LIMIT = intPreferencesKey("recommend_limit")

        val GALLERY_COLUMNS = intPreferencesKey("gallery_columns")
        val GALLERY_SORT = stringPreferencesKey("gallery_sort")
        val GALLERY_LARGE_BYTES = longPreferencesKey("gallery_large_bytes")
        val GALLERY_STACK_VARIANTS = booleanPreferencesKey("gallery_stack_variants")
        val FAST_SCROLL = booleanPreferencesKey("fast_scroll")
        val VARIANT_INDEX_VERSION = intPreferencesKey("variant_index_version")
        val QQ_SOURCE_SORT = stringPreferencesKey("qq_source_sort")
        val QQ_SOURCE_FACET_ORDER = stringPreferencesKey("qq_source_facet_order")

        val CLEANUP_CONFIG = stringPreferencesKey("cleanup_config")
        val AUTO_FAVORITE_CONFIG = stringPreferencesKey("auto_favorite_config")

        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val DETAIL_SHARED_TRANSITION = booleanPreferencesKey("detail_shared_transition")
        val EXPORT_SUBDIRS = stringPreferencesKey("export_subdirs")
    }
}

data class AiConnectionConfig(
    val enabled: Boolean,
    val endpoint: String,
    val apiKey: String,
    val model: String,
    val embeddingModel: String,
    val disableThinking: Boolean,
    val maxTokens: Int,
)
