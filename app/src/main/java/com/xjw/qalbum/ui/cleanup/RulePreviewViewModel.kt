package com.xjw.qalbum.ui.cleanup

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.xjw.qalbum.data.db.AppDatabase
import com.xjw.qalbum.data.db.StatsCache
import com.xjw.qalbum.data.db.dao.ImageDao
import com.xjw.qalbum.data.db.dao.RuleStats
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.data.paging.RefreshGate
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.data.repository.ImageRepository
import com.xjw.qalbum.domain.model.SourceSort
import com.xjw.qalbum.domain.model.TimelineSection
import com.xjw.qalbum.service.cleanup.ArchiveManager
import com.xjw.qalbum.service.cleanup.CompressionCoordinator
import com.xjw.qalbum.service.cleanup.ExternalViewTarget
import com.xjw.qalbum.service.cleanup.ImageFileManager
import com.xjw.qalbum.service.cleanup.OriginalCompressor
import com.xjw.qalbum.service.cleanup.rules.CleanupRule
import com.xjw.qalbum.service.cleanup.rules.CleanupRunner
import com.xjw.qalbum.service.cleanup.rules.RuleScope
import com.xjw.qalbum.service.cleanup.rules.RuleSql
import com.xjw.qalbum.service.cleanup.rules.SqlPart
import com.xjw.qalbum.service.demo.DemoMode
import com.xjw.qalbum.ui.components.ScrollDirectory
import com.xjw.qalbum.ui.gallery.FileSize
import com.xjw.qalbum.ui.gallery.GalleryTimelineItem
import com.xjw.qalbum.ui.gallery.StackedSize
import com.xjw.qalbum.ui.gallery.dateDirectory
import com.xjw.qalbum.ui.gallery.sizeDirectory
import com.xjw.qalbum.ui.gallery.withDateHeaders
import com.xjw.qalbum.ui.gallery.withSizeHeaders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 规则预览页：列出 [ruleId] 命中的全部图片（按日期分组），可以切换参数。进来时什么都不选；
 * 长按或右上角全选后，对选中的图归档、压缩或移入回收站（后台执行，期间网格保持原样，结束后统一刷新一次）。
 */
class RulePreviewViewModel(
    ruleId: String,
    private val db: AppDatabase,
    private val runner: CleanupRunner,
    private val compression: CompressionCoordinator,
    compressor: OriginalCompressor,
    private val archive: ArchiveManager,
    private val imageFiles: ImageFileManager,
    private val settings: SettingsRepository,
    private val appScope: CoroutineScope,
    private val statsCache: StatsCache,
    private val demo: DemoMode,
) : ViewModel() {

    val rule: CleanupRule = CleanupRule.byId(ruleId) ?: CleanupRule.Thumbnails
    private val dao = db.cleanupRuleDao()

    /** 截止时间按今天零点固定：翻页期间不漂移，与清理页的统计同一个条件（缓存共用） */
    private val now = CleanupRule.today()

    val option: StateFlow<Int> = settings.cleanupRuleParam(rule.id)
        .map { it?.takeIf { value -> rule.option(value) != null } ?: rule.defaultOption }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, rule.defaultOption)

    private val part = option.map { rule.where(it, now) }

    val stats: StateFlow<RuleStats?> = part
        .flatMapLatest { p -> statsCache.observe(ruleStatsKey(p, rule.scope)) { dao.stats(RuleSql.stats(p, rule.scope)) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 排序（与聊天来源页同一套：日期 / 大小 × 正序 / 倒序），各规则共用 */
    val sort: StateFlow<SourceSort> = settings.cleanupRuleSort
        .stateIn(viewModelScope, SharingStarted.Eagerly, SourceSort.DATE_DESC)

    private data class Key(val part: SqlPart, val sort: SourceSort)

    /** 当前网格的分组计数（日期或大小）与排序：分组标题和快速滚动条共用 */
    private class Groups(val counts: StateFlow<Map<Long, Int>?>, val sort: SourceSort)

    private val groups = MutableStateFlow<Groups?>(null)

    /** 执行期间关上：网格保持原样，执行完统一刷新 */
    private val refreshGate = RefreshGate()

    val photos: Flow<PagingData<GalleryTimelineItem>> = combine(part, sort) { p, order -> Key(p, order) }
        .distinctUntilChanged()
        .flatMapLatest { key ->
            val pager = Pager(
                PagingConfig(
                    pageSize = 60, prefetchDistance = 120, initialLoadSize = 180,
                    enablePlaceholders = true, jumpThreshold = ImageRepository.JUMP_THRESHOLD,
                ),
                pagingSourceFactory = refreshGate.gated { dao.paging(RuleSql.page(key.part, rule.scope, key.sort)) },
            ).flow
            if (key.sort.byDate) {
                val counts = counts("rule-buckets:date:${key.part}") { dao.buckets(RuleSql.buckets(key.part)) }
                groups.value = Groups(counts, key.sort)
                pager.withDateHeaders(counts)
            } else {
                val counts = counts("rule-buckets:size:${rule.scope}:${key.part}") { dao.buckets(RuleSql.sizeBuckets(key.part, rule.scope)) }
                groups.value = Groups(counts, key.sort)
                // 整张图的规则按整组合计分组，与排序一致
                pager.withSizeHeaders(counts, if (rule.scope == RuleScope.PICTURE) StackedSize else FileSize)
            }
        }
        .cachedIn(viewModelScope)

    val directory: StateFlow<ScrollDirectory?> = groups
        .flatMapLatest { source ->
            source?.counts?.map { counts ->
                counts?.let {
                    if (source.sort.byDate) dateDirectory(it, ascending = !source.sort.descending)
                    else sizeDirectory(it, ascending = !source.sort.descending)
                }
            } ?: flowOf(null)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 分组计数经 StatsCache：再进来时直接用缓存，图片变了才重算 */
    private fun counts(key: String, query: suspend () -> List<ImageDao.BucketCountRow>): StateFlow<Map<Long, Int>?> =
        statsCache.observe(key, query).map { rows -> rows.associate { it.bucketKey to it.count } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val columns: StateFlow<Int> = settings.galleryColumns
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.DEFAULT_GALLERY_COLUMNS)

    val fastScroll: StateFlow<Boolean> = settings.fastScrollEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val exportSubdirs: StateFlow<List<String>> = settings.exportSubdirs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 本规则发起的任务正在执行时的进度 */
    val running: StateFlow<CleanupRunner.Running?> = runner.running
        .map { it?.takeIf { r -> r.ruleId == rule.id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 压缩替换了文件的图片（看图快照据此刷新大小与压缩状态） */
    val compressedChanges: SharedFlow<Set<Long>> = compressor.changes

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun consumeMessage() {
        _message.value = null
    }

    init {
        // 本规则开始执行就关上闸门，执行结束后网格刷新一次
        viewModelScope.launch {
            runner.running.map { it?.ruleId == rule.id }.distinctUntilChanged().collect { mine ->
                if (mine) launch { refreshGate.hold { runner.running.first { it?.ruleId != rule.id } } }
            }
        }
    }

    fun setOption(value: Int) {
        if (rule.option(value) == null) return
        appScope.launch { settings.setCleanupRuleParam(rule.id, value) }
    }

    /** 点分组标题全选：这一天 / 这个大小区间的全部命中项 */
    suspend fun sectionIds(section: TimelineSection): List<Long> {
        val where = rule.where(option.value, now)
        return when (section) {
            is TimelineSection.Day -> dao.ids(RuleSql.idsBetween(where, section.from, section.until))
            is TimelineSection.Size -> dao.ids(RuleSql.idsBySize(where, rule.scope, section.low, section.high))
        }
    }

    fun setSort(next: SourceSort) {
        appScope.launch { settings.setCleanupRuleSort(next) }
    }

    /** 右上角全选：当前参数下的全部命中项 */
    suspend fun allIds(): Set<Long> = dao.ids(RuleSql.ids(rule.where(option.value, now))).toSet()

    /** 移入回收站（整张图的规则连同其他版本，只删某个版本的规则只删选中的文件），后台执行；演示模式下不执行，返回 false */
    fun trash(ids: Collection<Long>): Boolean {
        if (demo.blocks()) return false
        runner.trash(ids, rule.title, linkedVersions = rule.scope == RuleScope.PICTURE, ruleId = rule.id)
        return true
    }

    /** 压缩选中的图（先对比，见 [CompressionCoordinator]） */
    fun compress(ids: Collection<Long>) = compression.start(ids)

    fun cancel() = runner.cancel()

    /** 把选中的图归档：之后任何清理都不会动它们（同时从本规则里消失）；演示模式下不执行，返回 false */
    fun archive(ids: Collection<Long>, onDone: () -> Unit = {}): Boolean {
        if (demo.blocks()) return false
        appScope.launch {
            val count = archive.archive(ids, from = "rule")
            _message.value = "已归档 $count 张，不会被清理"
            withContext(Dispatchers.Main) { onDone() }
        }
        return true
    }

    /** 看图快照里刷新一张（压缩后大小与状态变了） */
    suspend fun entity(id: Long): ImageEntity? = db.imageDao().getById(id)

    fun setColumns(next: Int) {
        viewModelScope.launch { settings.setGalleryColumns(next) }
    }

    fun createSubdir(name: String) = settings.addExportSubdir(name)

    fun share(id: Long, onReady: (ExternalViewTarget) -> Unit) {
        if (demo.blocks()) return
        viewModelScope.launch { imageFiles.prepareShare(id)?.let(onReady) }
    }

    fun save(id: Long, subdir: String?) {
        if (demo.blocks()) return
        appScope.launch {
            val ok = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q || imageFiles.canExportPreQ()) &&
                imageFiles.exportAll(listOf(id), subdir, from = "rule").isNotEmpty()
            _message.value = if (ok) "已保存到相册" else "保存失败"
        }
    }
}
