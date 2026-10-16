package com.xjw.qalbum.ui.cleanup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.xjw.qalbum.data.db.AppDatabase
import com.xjw.qalbum.data.db.dao.CleanupDao
import com.xjw.qalbum.data.db.dao.ImageDao
import com.xjw.qalbum.data.db.dao.RuleStats
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.data.db.entity.QuarantineEntity
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.data.repository.ImageRepository
import com.xjw.qalbum.domain.model.AccessMode
import com.xjw.qalbum.service.cleanup.ArchiveManager
import com.xjw.qalbum.service.cleanup.CleanupController
import com.xjw.qalbum.service.cleanup.OriginalCompressor
import com.xjw.qalbum.service.cleanup.rules.CleanupRule
import com.xjw.qalbum.service.cleanup.rules.CleanupRunner
import com.xjw.qalbum.service.cleanup.rules.RuleScope
import com.xjw.qalbum.service.cleanup.rules.RuleSql
import com.xjw.qalbum.service.cleanup.rules.SqlPart
import com.xjw.qalbum.service.work.BackgroundTasks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.LocalDate
import java.time.Year
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

/**
 * 跨 ViewModel 实例共享的操作状态。回收站批量操作跑在 App 级作用域（比页面长寿），busy / 进度 / 提示
 * 必须在页面重建之间保持一致。
 */
private object CleanupOpState {
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
 * 清理页（规则列表、存储概览）、按时间清理、回收站共用。规则的预览与执行见 [RulePreviewViewModel]、[CleanupRunner]。
 */
class CleanupViewModel(
    private val db: AppDatabase,
    private val cleanupController: CleanupController,
    private val runner: CleanupRunner,
    private val compressor: OriginalCompressor,
    private val imageRepository: ImageRepository,
    private val archive: ArchiveManager,
    private val settings: SettingsRepository,
    private val tasks: BackgroundTasks,
    private val appScope: CoroutineScope,
) : ViewModel() {

    private val ruleDao = db.cleanupRuleDao()

    // ---- 概览 ----

    val storage: StateFlow<StorageOverview?> = combine(
        imageRepository.observeSourceBreakdown(),
        imageRepository.observeThumbnailTrashSummary(),
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
        .flatMapLatest { imageRepository.observeArchiveCount(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** 聊天来源（按群聊 / 私聊清理）需要 Root 读取 QQ 数据库 */
    val rootMode: StateFlow<Boolean> = settings.accessMode.map { it == AccessMode.ROOT }
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
            .flatMapLatest { option ->
                val part = rule.where(option, System.currentTimeMillis())
                ruleDao.observeStats(RuleSql.stats(part, rule.scope))
                    .throttle()
                    .map<RuleStats, RuleCard> { RuleCard(rule, option, it) }
                    .onStartWith(RuleCard(rule, option, null))
            }

    val running: StateFlow<CleanupRunner.Running?> = runner.running

    fun cancelRunning() = runner.cancel()

    /** 原图压缩规则的预计节省按当前质量估算 */
    val compressQuality: StateFlow<Int> = settings.compressQuality
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.DEFAULT_COMPRESS_QUALITY)

    // ---- 按时间清理 ----

    /** 每个月的整张图张数与大小（新到旧）；按天汇总后在这里合成月份 */
    val months: StateFlow<List<MonthBucket>?> =
        ruleDao.observeDays(RuleSql.days(SqlPart(CleanupRule.PICTURE), RuleScope.PICTURE))
            .throttle()
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

    // ---- 回收站 ----

    val quarantine: Flow<PagingData<QuarantineEntity>> =
        cleanupController.pagingQuarantine().cachedIn(viewModelScope)

    val quarantineStats: StateFlow<CleanupDao.QuarantineStatsRow> =
        cleanupController.observeQuarantineStats()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CleanupDao.QuarantineStatsRow(0, 0))

    /** 压缩前的原图（列出压缩后的图片本身） */
    val backedUp: Flow<PagingData<ImageEntity>> = Pager(
        PagingConfig(pageSize = 60, prefetchDistance = 120, initialLoadSize = 60, enablePlaceholders = false),
        pagingSourceFactory = { db.originalBackupDao().pagingBackedUpImages() },
    ).flow.cachedIn(viewModelScope)

    /** 原图备份张数与占用 */
    val backupStats: StateFlow<CleanupDao.QuarantineStatsRow> = db.originalBackupDao().observeStats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CleanupDao.QuarantineStatsRow(0, 0))

    val busy: StateFlow<Boolean> = CleanupOpState.busy.asStateFlow()

    /** 清空回收站等操作的进度；为 null 表示当前没有在跑 */
    val purgeProgress: StateFlow<PurgeProgress?> = CleanupOpState.purgeProgress.asStateFlow()

    /** 操作结果 / 失败提示（Toast 展示后调 [consumeMessage] 清空）；进程级共享，重建不丢 */
    val message: StateFlow<String?> = CleanupOpState.message.asStateFlow()

    fun consumeMessage() {
        CleanupOpState.message.value = null
    }

    fun restore(quarantineId: Long) {
        // busy 置位 + QuarantineManager 的 inFlight 互斥，恢复期间的清空 / 同记录永久删除不会与移动交错
        launchGuarded("恢复") {
            val ok = cleanupController.restore(quarantineId)
            CleanupOpState.message.value =
                if (ok) "已恢复到原位置" else "恢复失败：原位置已有文件或无法访问，图片仍在回收站"
        }
    }

    fun purge(quarantineId: Long) {
        launchGuarded("永久删除") {
            val ok = cleanupController.purge(quarantineId)
            if (!ok) CleanupOpState.message.value = "永久删除失败，请稍后重试"
        }
    }

    fun purgeAllRecycleBin(onDone: (Int) -> Unit = {}) {
        launchGuarded("清空回收站") {
            val total = cleanupController.countQuarantine()
            CleanupOpState.purgeProgress.value = PurgeProgress(0, total)
            try {
                tasks.track("清空回收站", cancellable = false) { handle ->
                    var purged = 0
                    var processed = 0
                    var afterId = 0L
                    while (true) {
                        val page = cleanupController.listQuarantinePaged(afterId, PURGE_PAGE)
                        if (page.isEmpty()) break
                        afterId = page.last().id
                        purged += cleanupController.purgeByRecords(page)
                        processed += page.size
                        CleanupOpState.purgeProgress.value = PurgeProgress(processed.coerceAtMost(total), total)
                        handle.update("已删除 $processed / $total 项", processed.toLong(), total.toLong())
                    }
                    onMain { onDone(purged) }
                }
            } finally {
                CleanupOpState.purgeProgress.value = null
            }
        }
    }

    /** 还原压缩前的原图 */
    fun restoreOriginal(imageId: Long) {
        launchGuarded("还原原图") {
            CleanupOpState.message.value =
                if (compressor.restore(imageId)) "已还原原图" else "还原失败：文件已变化或无法访问"
        }
    }

    /** 删除压缩前的原图备份（释放空间） */
    fun deleteOriginal(imageId: Long) {
        launchGuarded("删除原图") {
            if (compressor.purgeByImageIds(listOf(imageId)) == 0) CleanupOpState.message.value = "原图删除失败，可稍后重试"
        }
    }

    fun purgeAllOriginals(onDone: (Int) -> Unit = {}) {
        launchGuarded("删除全部原图") {
            val total = backupStats.value.count
            CleanupOpState.purgeProgress.value = PurgeProgress(0, total, "正在删除原图…")
            try {
                tasks.track("删除压缩前的原图", cancellable = false) { handle ->
                    val purged = compressor.purgeAll { done ->
                        CleanupOpState.purgeProgress.value = PurgeProgress(done.coerceAtMost(total), total, "正在删除原图…")
                        handle.update("已删除 $done / $total 张", done.toLong(), total.toLong())
                    }
                    onMain { onDone(purged) }
                }
            } finally {
                CleanupOpState.purgeProgress.value = null
            }
        }
    }

    /**
     * 统一协程入口：磁盘将满时 Room 写入等异常必须兜住降级为可见错误，而不是穿透 launch 杀进程。
     * 文件操作跑在 App 级作用域，离开页面不中断。
     */
    private fun launchGuarded(label: String, block: suspend CoroutineScope.() -> Unit) {
        appScope.launch {
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
    }

    /** UI 回调统一回主线程：appScope 任务跑在 IO 线程，直接 Toast 会崩。 */
    private suspend fun onMain(block: () -> Unit) = withContext(Dispatchers.Main) { block() }

    private companion object {
        const val PURGE_PAGE = 200
    }
}

/** 批量操作进度：已处理 / 总数 */
data class PurgeProgress(val done: Int, val total: Int, val label: String = "正在清理…")

/**
 * 聚合观察流节流：清理进行中每批提交都会让统计重查，这里每 [periodMs] 至多下发一次（首个值立即下发）。
 */
internal fun <T> Flow<T>.throttle(periodMs: Long = 1_500L): Flow<T> = transform { value ->
    emit(value)
    delay(periodMs)
}

/** 先发一个占位值，再接上游 */
private fun <T> Flow<T>.onStartWith(value: T): Flow<T> = kotlinx.coroutines.flow.flow {
    emit(value)
    collect { emit(it) }
}
