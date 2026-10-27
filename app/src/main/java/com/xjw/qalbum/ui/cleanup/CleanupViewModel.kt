package com.xjw.qalbum.ui.cleanup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xjw.qalbum.access.ChatFeatures
import com.xjw.qalbum.data.db.AppDatabase
import com.xjw.qalbum.data.db.dao.CleanupDao
import com.xjw.qalbum.data.db.dao.ImageDao
import com.xjw.qalbum.data.db.dao.RuleStats
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.data.db.StatsCache
import com.xjw.qalbum.service.cleanup.ArchiveManager
import com.xjw.qalbum.service.demo.DemoMode
import com.xjw.qalbum.service.cleanup.rules.CleanupRule
import com.xjw.qalbum.service.cleanup.rules.CleanupRunner
import com.xjw.qalbum.service.cleanup.rules.RuleScope
import com.xjw.qalbum.service.cleanup.rules.RuleSql
import com.xjw.qalbum.service.cleanup.rules.SqlPart
import com.xjw.qalbum.data.paging.calmDuringBulkWrites
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.LocalDate
import java.time.Year
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

/**
 * 跨 ViewModel 实例共享的操作状态。回收站批量操作跑在 App 级作用域（比页面长寿），busy / 进度 / 提示
 * 必须在页面重建之间保持一致；进度对话框与提示由清理页根部统一显示（见 CleanupScreen）。
 */
internal object CleanupOpState {
    private val busyCount = AtomicInteger(0)

    val busy = MutableStateFlow(false)
    val purgeProgress = MutableStateFlow<PurgeProgress?>(null)
    val message = MutableStateFlow<String?>(null)

    fun pushBusy() {
        busy.value = busyCount.incrementAndGet() > 0
    }

    fun popBusy() {
        busy.value = busyCount.decrementAndGet() > 0
    }
}

/** 存储概览：按版本分的占用（缩略图单独统计） */
data class StorageOverview(
    val originals: ImageDao.SourceBreakdownRow?,
    val previews: ImageDao.SourceBreakdownRow?,
    val others: ImageDao.SourceBreakdownRow?,
    val thumbs: ImageDao.ThumbnailTrashSummary,
) {
    val totalBytes: Long get() = (originals?.bytes ?: 0) + (previews?.bytes ?: 0) + (others?.bytes ?: 0) + thumbs.bytes
}

/** 清理页上的一条规则：当前参数与命中统计（统计还没出来时为 null） */
data class RuleCard(val rule: CleanupRule, val option: Int, val stats: RuleStats?)

/** 聊天来源的状态：读取过没有，最近一次完整读取的开始时间（0 = 没有过，「聊天记录已删除」要用） */
data class SourcesState(val read: Boolean = false, val completeReadAt: Long = 0)

/** 按时间清理的一个月 */
data class MonthBucket(val year: Int, val month: Int, val count: Int, val bytes: Long, val from: Long, val until: Long) {
    val label: String get() = "$year 年 $month 月"
}

/**
 * 清理页（规则列表、存储概览）与按时间清理共用。规则的预览与执行见 [RulePreviewViewModel]、[CleanupRunner]，
 * 回收站页见 [RecycleBinViewModel]。
 */
class CleanupViewModel(
    private val db: AppDatabase,
    private val runner: CleanupRunner,
    private val archive: ArchiveManager,
    private val settings: SettingsRepository,
    private val statsCache: StatsCache,
    chatFeatures: ChatFeatures,
) : ViewModel() {

    private val ruleDao = db.cleanupRuleDao()
    private val imageDao = db.imageDao()

    // ---- 概览（统计都经 StatsCache：切换页面不重算，图片变了才重算） ----

    val storage: StateFlow<StorageOverview?> = combine(
        statsCache.observe("source-breakdown") { imageDao.sourceBreakdown() },
        statsCache.observe("thumbnail-summary") { imageDao.thumbnailTrashSummary() },
    ) { rows, thumbs ->
        StorageOverview(
            originals = rows.firstOrNull { it.source == "chatraw" },
            previews = rows.firstOrNull { it.source == "chatimg" },
            others = rows.filter { it.source != "chatraw" && it.source != "chatimg" }
                .takeIf { it.isNotEmpty() }
                ?.let { list -> ImageDao.SourceBreakdownRow("other", list.sumOf { it.count }, list.sumOf { it.bytes }) },
            thumbs = thumbs,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val archivedCount: StateFlow<Int> = archive.pageStatuses
        .flatMapLatest { statuses -> statsCache.observe("archive-count:$statuses") { imageDao.archiveCount(statuses) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** 显示「按聊天」一组（Shizuku 模式下没读过来源时不显示） */
    val chatAvailable: StateFlow<Boolean> = chatFeatures.available
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // ---- 规则 ----

    val rules: StateFlow<List<RuleCard>> = combine(CleanupRule.listed.map(::cardFlow)) { it.toList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CleanupRule.listed.map { RuleCard(it, it.defaultOption, null) })

    /** 按聊天的规则（群聊旧图、聊天记录已删除）：没读取过来源时命中为 0 */
    val chatRules: StateFlow<List<RuleCard>> = combine(CleanupRule.chat.map(::cardFlow)) { it.toList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CleanupRule.chat.map { RuleCard(it, it.defaultOption, null) })

    val sources: StateFlow<SourcesState> = combine(
        db.qqSourceDao().observeAccounts(),
        db.qqSourceDao().observeCompleteReadAt(),
    ) { accounts, completeReadAt -> SourcesState(accounts.isNotEmpty(), completeReadAt) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SourcesState())

    private fun cardFlow(rule: CleanupRule): Flow<RuleCard> =
        settings.cleanupRuleParam(rule.id)
            .map { it?.takeIf { value -> rule.option(value) != null } ?: rule.defaultOption }
            .distinctUntilChanged()
            .flatMapLatest { option ->
                val part = rule.where(option, CleanupRule.today())
                val key = ruleStatsKey(part, rule.scope)
                statsCache.observe(key) { ruleDao.stats(RuleSql.stats(part, rule.scope)) }
                    .map<RuleStats, RuleCard> { RuleCard(rule, option, it) }
                    // 还没算过（换了参数）先显示「统计中」；算过的立刻给缓存值
                    .onStart { if (!statsCache.has(key)) emit(RuleCard(rule, option, null)) }
            }

    val running: StateFlow<CleanupRunner.Running?> = runner.running

    fun cancelRunning() = runner.cancel()

    /** 原图压缩规则的预计节省按当前质量估算 */
    val compressQuality: StateFlow<Int> = settings.compressQuality
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.DEFAULT_COMPRESS_QUALITY)

    // ---- 按时间清理 ----

    /** 每个月的整张图张数与大小（新到旧）；按天汇总后在这里合成月份 */
    val months: StateFlow<List<MonthBucket>?> =
        statsCache.observe("days:" + SqlPart(CleanupRule.PICTURE)) { ruleDao.days(RuleSql.days(SqlPart(CleanupRule.PICTURE), RuleScope.PICTURE)) }
            .map { rows ->
                val zone = ZoneId.systemDefault()
                rows.filter { it.bucketKey > 1000 }.groupBy { row ->
                    // date_bucket = year * 1000 + dayOfYear（本地时区）
                    val year = (row.bucketKey / 1000).toInt()
                    val day = (row.bucketKey % 1000).toInt().coerceIn(1, Year.of(year).length())
                    year to LocalDate.ofYearDay(year, day).monthValue
                }.map { (key, list) ->
                    val (year, month) = key
                    val start = LocalDate.of(year, month, 1)
                    MonthBucket(
                        year = year, month = month,
                        count = list.sumOf { it.count }, bytes = list.sumOf { it.bytes },
                        from = start.atStartOfDay(zone).toInstant().toEpochMilli(),
                        until = start.plusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli(),
                    )
                }.sortedWith(compareByDescending<MonthBucket> { it.year }.thenByDescending { it.month })
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // ---- 回收站入口 ----

    val quarantineStats: StateFlow<CleanupDao.QuarantineStatsRow> = db.cleanupDao().observeQuarantineStats().calmDuringBulkWrites()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CleanupDao.QuarantineStatsRow(0, 0))

    /** 原图备份张数与占用 */
    val backupStats: StateFlow<CleanupDao.QuarantineStatsRow> = db.originalBackupDao().observeStats().calmDuringBulkWrites()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CleanupDao.QuarantineStatsRow(0, 0))

    /** 视频页上次扫到的视频个数与占用（不包含收到的文件时只算聊天视频） */
    val videoStats: StateFlow<CleanupDao.QuarantineStatsRow> = combine(db.videoDao().observeSources(), settings.videoIncludeFiles) { rows, include ->
        rows.filter { include || it.source == com.xjw.qalbum.data.db.entity.VideoEntity.SOURCE_CHAT }
    }
        .map { rows -> CleanupDao.QuarantineStatsRow(rows.sumOf { it.count }, rows.sumOf { it.bytes }) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CleanupDao.QuarantineStatsRow(0, 0))

    /** 回收站批量操作的进度；为 null 表示当前没有在跑 */
    val purgeProgress: StateFlow<PurgeProgress?> = CleanupOpState.purgeProgress.asStateFlow()

    /** 操作结果 / 失败提示（Toast 展示后调 [consumeMessage] 清空）；进程级共享，重建不丢 */
    val message: StateFlow<String?> = CleanupOpState.message.asStateFlow()

    fun consumeMessage() {
        CleanupOpState.message.value = null
    }
}

/** 批量操作进度：已处理 / 总数 */
data class PurgeProgress(val done: Int, val total: Int, val label: String = "正在清理…")

/**
 * 回收站等页面的文件操作：跑在 App 级作用域，离开页面也不中断；期间算 busy，磁盘将满时 Room 写入等异常
 * 兜住转成可见提示，而不是穿透 launch 杀进程。演示模式（[demo]）下不执行，返回 false。
 */
internal fun CoroutineScope.launchCleanupOp(label: String, demo: DemoMode, block: suspend CoroutineScope.() -> Unit): Boolean {
    if (demo.blocks()) return false
    launchCleanupOp(label, block)
    return true
}

private fun CoroutineScope.launchCleanupOp(label: String, block: suspend CoroutineScope.() -> Unit) = launch {
    CleanupOpState.pushBusy()
    try {
        block()
    } catch (ce: CancellationException) {
        throw ce
    } catch (t: Throwable) {
        Timber.e(t, "%s 失败", label)
        CleanupOpState.message.value = "$label 失败：${t.message ?: t.javaClass.simpleName}"
    } finally {
        CleanupOpState.popBusy()
    }
}

/**
 * 聚合观察流节流：清理进行中每批提交都会让统计重查，这里每 [periodMs] 至多下发一次（首个值立即下发）。
 */
internal fun <T> Flow<T>.throttle(periodMs: Long = 1_500L): Flow<T> = transform { value ->
    emit(value)
    delay(periodMs)
}

/** 规则命中统计的缓存键：清理页的规则卡片与规则页的标题共用（同一条件只算一次） */
internal fun ruleStatsKey(part: SqlPart, scope: RuleScope): String = "rule-stats:$scope:$part"
