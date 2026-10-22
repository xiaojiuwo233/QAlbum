package com.xjw.qalbum.ui.cleanup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map as pagingMap
import com.xjw.qalbum.data.db.AppDatabase
import com.xjw.qalbum.data.db.RecycleBinSql
import com.xjw.qalbum.data.db.StatsCache
import com.xjw.qalbum.data.db.dao.BackupUnit
import com.xjw.qalbum.data.db.dao.CleanupDao
import com.xjw.qalbum.data.db.dao.TrashUnit
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.data.db.entity.QuarantineEntity
import com.xjw.qalbum.data.paging.RefreshGate
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.data.repository.ImageRepository
import com.xjw.qalbum.domain.model.SourceSort
import com.xjw.qalbum.domain.model.TimelineSection
import com.xjw.qalbum.service.cleanup.ArchiveManager
import com.xjw.qalbum.service.cleanup.OriginalCompressor
import com.xjw.qalbum.service.cleanup.QuarantineManager
import com.xjw.qalbum.service.work.BackgroundTasks
import com.xjw.qalbum.ui.components.ScrollDirectory
import com.xjw.qalbum.ui.gallery.GalleryTimelineItem
import com.xjw.qalbum.ui.gallery.dateDirectory
import com.xjw.qalbum.ui.gallery.sizeDirectory
import com.xjw.qalbum.ui.gallery.withDayHeaders
import com.xjw.qalbum.ui.gallery.withSizeBuckets
import com.xjw.qalbum.ui.imageloading.FileAccessPath
import com.xjw.qalbum.ui.imageloading.ImageEntityModel
import com.xjw.qalbum.data.paging.calmDuringBulkWrites
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

/** 回收站页显示哪类：移入回收站的图片，或压缩前保留的原图 */
enum class BinContent { DELETED, ORIGINALS }

/** 按删除原因筛选的一项：同一类原因合成一个（如「手动删除」），[reasons] 是库里记的原文 */
data class ReasonFilter(val label: String, val reasons: List<String>, val count: Int, val bytes: Long)

/**
 * 回收站页：移入回收站的图（同一次删除的同一张图的各版本算一组，组里最大的文件代表）与压缩前的原图。
 * 网格、排序、多选与图库一致，日期按移入回收站（压缩）的时间分组，标题带到期天数。恢复、恢复后归档、
 * 永久删除、还原原图、删除原图都在 App 级作用域里跑：期间网格保持原样，结束后统一刷新一次；
 * 进度与结果由清理页根部显示（见 [CleanupOpState]）。
 */
class RecycleBinViewModel(
    private val db: AppDatabase,
    private val quarantine: QuarantineManager,
    private val compressor: OriginalCompressor,
    private val archive: ArchiveManager,
    private val settings: SettingsRepository,
    private val tasks: BackgroundTasks,
    private val appScope: CoroutineScope,
    private val statsCache: StatsCache,
) : ViewModel() {
    private val dao = db.cleanupDao()

    private val _content = MutableStateFlow(BinContent.DELETED)
    val content: StateFlow<BinContent> = _content.asStateFlow()

    /** 选中的删除原因（标签），null = 全部 */
    private val _reason = MutableStateFlow<String?>(null)
    val reason: StateFlow<String?> = _reason.asStateFlow()

    val sort: StateFlow<SourceSort> = settings.recycleBinSort
        .stateIn(viewModelScope, SharingStarted.Eagerly, SourceSort.DATE_DESC)

    val columns: StateFlow<Int> = settings.galleryColumns
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.DEFAULT_GALLERY_COLUMNS)

    val fastScroll: StateFlow<Boolean> = settings.fastScrollEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val deletedStats: StateFlow<CleanupDao.QuarantineStatsRow> = dao.observeQuarantineStats().calmDuringBulkWrites()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CleanupDao.QuarantineStatsRow(0, 0))

    val originalStats: StateFlow<CleanupDao.QuarantineStatsRow> = db.originalBackupDao().observeStats().calmDuringBulkWrites()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CleanupDao.QuarantineStatsRow(0, 0))

    /** 回收站里的删除原因，按张数从多到少 */
    val reasons: StateFlow<List<ReasonFilter>> = dao.observeTrashReasons(RecycleBinSql.trashReasons()).calmDuringBulkWrites()
        .throttle()
        .map { rows ->
            rows.groupBy { trashReasonLabel(it.reason) }
                .map { (label, list) -> ReasonFilter(label, list.map { it.reason }, list.sumOf { it.count }, list.sumOf { it.bytes }) }
                .sortedByDescending { it.count }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val busy: StateFlow<Boolean> = CleanupOpState.busy.asStateFlow()

    fun setContent(next: BinContent) {
        _content.value = next
    }

    fun setReason(label: String?) {
        _reason.value = label
    }

    fun setSort(next: SourceSort) {
        appScope.launch { settings.setRecycleBinSort(next) }
    }

    fun setColumns(next: Int) {
        viewModelScope.launch { settings.setGalleryColumns(next) }
    }

    private data class Key(val content: BinContent, val sort: SourceSort, val reasons: List<String>?)

    /** 当前列表的条件；筛选的那类原因已经没有了就当全部 */
    private val key = combine(_content, settings.recycleBinSort, _reason, reasons) { content, order, label, all ->
        val chosen = if (content == BinContent.DELETED) label?.let { l -> all.firstOrNull { it.label == l }?.reasons } else null
        Key(content, order, chosen)
    }.distinctUntilChanged().shareIn(viewModelScope, SharingStarted.Eagerly, replay = 1)

    /** 执行期间关上：网格保持原样，执行完统一刷新 */
    private val refreshGate = RefreshGate()

    /** 看图时取到的各组的各个版本：图片 id → 回收站记录（每次取版本时更新） */
    private val members = ConcurrentHashMap<Long, TrashUnit>()

    /** 当前网格的分组计数（日期或大小）与排序：分组标题和快速滚动条共用 */
    private class Groups(val counts: StateFlow<Map<Long, Int>?>, val sort: SourceSort)

    private val groups = MutableStateFlow<Groups?>(null)

    val photos: Flow<PagingData<GalleryTimelineItem>> = key
        .flatMapLatest { k ->
            val deleted = k.content == BinContent.DELETED
            val buckets = if (deleted) RecycleBinSql.trashBuckets(k.sort.byDate, k.reasons) else RecycleBinSql.backupBuckets(k.sort.byDate)
            groups.value = Groups(
                dao.observeBinBuckets(buckets).calmDuringBulkWrites().map { rows -> rows.associate { it.bucketKey to it.count } }
                    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null),
                k.sort,
            )
            // 分组标题上的张数每次刷新时现查：刚恢复 / 删除完，标题与网格一致
            val counts = flow { emit(dao.binBuckets(buckets).associate { it.bucketKey to it.count }) }
            val pages: Flow<PagingData<GalleryTimelineItem.Photo>> = if (deleted) {
                Pager(PAGING, pagingSourceFactory = refreshGate.gated { dao.pagingTrash(RecycleBinSql.trashPage(k.sort, k.reasons)) })
                    .flow.map { data -> data.pagingMap { unit -> GalleryTimelineItem.Photo(unit.display(), unit) } }
            } else {
                Pager(PAGING, pagingSourceFactory = refreshGate.gated { dao.pagingBackups(RecycleBinSql.backupPage(k.sort)) })
                    .flow.map { data -> data.pagingMap { backup -> GalleryTimelineItem.Photo(backup.image, backup) } }
            }
            if (k.sort.byDate) pages.withDayHeaders(counts, note = ::expiryNote) { it.binTime() }
            else pages.withSizeBuckets(counts) { it.binBytes() }
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

    /** 点分组标题全选：这一天 / 这个大小区间的全部 */
    suspend fun sectionIds(section: TimelineSection): List<Long> = ids(section)

    /** 右上角全选：当前筛选下的全部 */
    suspend fun allIds(): Set<Long> = ids(null).toSet()

    private suspend fun ids(section: TimelineSection?): List<Long> {
        val k = key.first()
        return dao.binIds(if (k.content == BinContent.DELETED) RecycleBinSql.trashIds(k.reasons, section) else RecycleBinSql.backupIds(section))
    }

    /** 格子：回收站里的图显示移入时生成的缩略图（没有时读回收站里的文件），压缩前的原图显示压缩后的图片 */
    fun tileModel(photo: GalleryTimelineItem.Photo): Any = when (val tag = photo.tag) {
        is TrashUnit -> tag.thumbnail?.let(::File) ?: FileAccessPath(tag.file)
        else -> ImageEntityModel(photo.image, tile = true)
    }

    /** 看图读哪个文件：回收站里的图读回收站里的文件，其余（压缩前的原图）看图片本身 */
    fun viewerModel(image: ImageEntity, unit: TrashUnit?): Any = unit?.let { FileAccessPath(it.file) } ?: ImageEntityModel(image)

    /** 看图时取到的某个版本的回收站记录 */
    fun member(imageId: Long): TrashUnit? = members[imageId]

    /** 看图时这组的各个版本（不止一个才有版本条） */
    suspend fun versionsOf(unit: TrashUnit?): List<ImageEntity> {
        val group = unit?.takeIf { it.files >= 2 }?.groupId ?: return emptyList()
        val rows = dao.trashUnits(RecycleBinSql.trashMembers(group))
        rows.forEach { members[it.image.id] = it }
        return rows.map { it.display() }.takeIf { it.size >= 2 }.orEmpty()
    }

    // ---- 操作 ----

    /** 原位置已经有文件（多是 QQ 又下载了一份）、没有恢复的组（回收站记录 id）：页面问要不要用现有的文件 */
    data class RestoreConflict(val quarantineIds: List<Long>, val thenArchive: Boolean)

    private val _conflict = MutableStateFlow<RestoreConflict?>(null)
    val conflict: StateFlow<RestoreConflict?> = _conflict.asStateFlow()

    fun dismissConflict() {
        _conflict.value = null
    }

    /** 恢复选中的图（整组恢复）；[thenArchive] 恢复后再归档，之后不会被清理 */
    fun restore(imageIds: Collection<Long>, thenArchive: Boolean, onDone: () -> Unit = {}) {
        if (imageIds.isEmpty()) return
        val label = if (thenArchive) "恢复并归档" else "恢复"
        appScope.launchCleanupOp(label) {
            quarantine.fileAccessError()?.let { error ->
                CleanupOpState.message.value = error
                return@launchCleanupOp
            }
            val records = records(imageIds)
            val report = restoreGroups("正在$label…", records.map { it.id }, keepExisting = false, thenArchive)
            val failed = records.size - report.groups.size - report.occupied.size
            CleanupOpState.message.value = listOfNotNull(
                restoredText(report.groups.size, thenArchive),
                if (failed > 0) "$failed 张没能恢复，可稍后重试" else null,
            ).joinToString("；").ifEmpty { null }
            if (report.occupied.isNotEmpty()) _conflict.value = RestoreConflict(report.occupied, thenArchive)
            onMain(onDone)
        }
    }

    /** 原位置已经有文件的那几组：用现有的文件，回收站里的这几份删掉，其余照常恢复 */
    fun keepExisting(conflict: RestoreConflict) {
        _conflict.value = null
        val label = if (conflict.thenArchive) "恢复并归档" else "恢复"
        appScope.launchCleanupOp(label) {
            val report = restoreGroups("正在$label…", conflict.quarantineIds, keepExisting = true, conflict.thenArchive)
            val failed = conflict.quarantineIds.size - report.groups.size
            CleanupOpState.message.value = listOfNotNull(
                restoredText(report.groups.size, conflict.thenArchive),
                if (failed > 0) "$failed 张没能恢复，可稍后重试" else null,
            ).joinToString("；").ifEmpty { null }
        }
    }

    private suspend fun restoreGroups(label: String, quarantineIds: List<Long>, keepExisting: Boolean, thenArchive: Boolean) =
        progress(label, quarantineIds.size) { report ->
            refreshGate.hold {
                quarantine.restoreAll(quarantineIds, keepExisting, report).also { result ->
                    if (thenArchive && result.restored.isNotEmpty()) archive.archive(result.restored)
                }
            }
        }

    /** 恢复了几张；归档的图默认不在图库里显示，告诉用户去哪里找 */
    private suspend fun restoredText(count: Int, thenArchive: Boolean): String? = when {
        count == 0 -> null
        !thenArchive -> "已恢复 $count 张"
        settings.archiveVisible.first() -> "已恢复并归档 $count 张"
        else -> "已恢复并归档 $count 张，可在「归档」里查看"
    }

    /** 永久删除选中的图（整组） */
    fun purge(imageIds: Collection<Long>, onDone: () -> Unit = {}) {
        if (imageIds.isEmpty()) return
        appScope.launchCleanupOp("永久删除") {
            val records = records(imageIds)
            var purged = 0
            progress("正在永久删除…", records.size) { report ->
                refreshGate.hold {
                    var done = 0
                    records.chunked(PAGE).forEach { page ->
                        purged += quarantine.purgeByRecords(page)
                        done += page.size
                        report(done, records.size)
                    }
                }
            }
            CleanupOpState.message.value =
                if (purged == records.size) "已永久删除 $purged 张" else "已永久删除 $purged 张，${records.size - purged} 张没删掉，可稍后重试"
            onMain(onDone)
        }
    }

    /** 清空回收站 */
    fun purgeAll() {
        appScope.launchCleanupOp("清空回收站") {
            val total = dao.countQuarantine()
            var purged = 0
            progress("正在清空回收站…", total) { report ->
                refreshGate.hold {
                    var processed = 0
                    var afterId = 0L
                    while (true) {
                        val page = dao.listQuarantinePaged(afterId, PAGE)
                        if (page.isEmpty()) break
                        afterId = page.last().id
                        purged += quarantine.purgeByRecords(page)
                        processed += page.size
                        report(processed.coerceAtMost(total), total)
                    }
                }
            }
            CleanupOpState.message.value = "已永久删除 $purged 张"
        }
    }

    /** 还原压缩前的原图（换回压缩前的文件） */
    fun restoreOriginals(imageIds: Collection<Long>, onDone: () -> Unit = {}) {
        if (imageIds.isEmpty()) return
        appScope.launchCleanupOp("还原原图") {
            val ids = imageIds.toList()
            var restored = 0
            progress("正在还原原图…", ids.size) { report ->
                refreshGate.hold {
                    ids.forEachIndexed { index, id ->
                        if (compressor.restore(id)) restored++
                        report(index + 1, ids.size)
                    }
                }
            }
            CleanupOpState.message.value =
                if (restored == ids.size) "已还原 $restored 张原图" else "已还原 $restored 张，${ids.size - restored} 张没能还原：文件已变化或无法访问"
            onMain(onDone)
        }
    }

    /** 删除压缩前的原图（压缩后的图片保留） */
    fun deleteOriginals(imageIds: Collection<Long>, onDone: () -> Unit = {}) {
        if (imageIds.isEmpty()) return
        appScope.launchCleanupOp("删除原图") {
            val ids = imageIds.toList()
            var deleted = 0
            progress("正在删除原图…", ids.size) { report ->
                refreshGate.hold {
                    var done = 0
                    ids.chunked(PAGE).forEach { page ->
                        deleted += compressor.purgeByImageIds(page)
                        done += page.size
                        report(done, ids.size)
                    }
                }
            }
            CleanupOpState.message.value = "已删除 $deleted 张原图"
            onMain(onDone)
        }
    }

    fun deleteAllOriginals() {
        appScope.launchCleanupOp("删除全部原图") {
            val total = originalStats.value.count
            val deleted = progress("正在删除原图…", total) { report ->
                refreshGate.hold { compressor.purgeAll { done -> report(done.coerceAtMost(total), total) } }
            }
            CleanupOpState.message.value = "已删除 $deleted 张原图"
        }
    }

    /** 批量操作：清理页根部显示进度对话框，同时登记为后台任务（通知栏显示进度，切到后台不中断） */
    private suspend fun <T> progress(label: String, total: Int, block: suspend (report: (Int, Int) -> Unit) -> T): T {
        CleanupOpState.purgeProgress.value = PurgeProgress(0, total, label)
        try {
            return statsCache.hold {
                tasks.track(label.removePrefix("正在").removeSuffix("…"), cancellable = false) { handle ->
                    block { done, all ->
                        CleanupOpState.purgeProgress.value = PurgeProgress(done, all, label)
                        handle.update("已处理 $done / $all 张", done.toLong(), all.toLong())
                    }
                }
            }
        } finally {
            CleanupOpState.purgeProgress.value = null
        }
    }

    /** 选中的图（每组的代表）对应的回收站记录 */
    private suspend fun records(imageIds: Collection<Long>): List<QuarantineEntity> =
        imageIds.distinct().chunked(SQL_CHUNK).flatMap { dao.readyByImageIds(it) }

    private suspend fun onMain(block: () -> Unit) = withContext(Dispatchers.Main) { block() }

    private companion object {
        const val PAGE = 200
        const val SQL_CHUNK = 500
        const val DAY_MS = 24L * 60 * 60 * 1000
        val PAGING = PagingConfig(
            pageSize = 60, prefetchDistance = 120, initialLoadSize = 180,
            enablePlaceholders = true, jumpThreshold = ImageRepository.JUMP_THRESHOLD,
        )
    }

    /** 日期分组标题后附上到期天数 */
    private fun expiryNote(photo: GalleryTimelineItem.Photo): String? {
        val expires = when (val tag = photo.tag) {
            is TrashUnit -> tag.expiresAt
            is BackupUnit -> tag.expiresAt
            else -> return null
        }
        val days = ceil((expires - System.currentTimeMillis()) / DAY_MS.toDouble()).toLong()
        return when {
            days > 3650 -> null
            days <= 0 -> "即将删除"
            else -> "$days 天后删除"
        }
    }
}

/** 网格与看图里显示的图片：路径换回原位置（回收站里的图片行存的是内部占位），版本数与大小按整组 */
internal fun TrashUnit.display(): ImageEntity = image.copy(path = originalPath, variantCount = files, variantBytes = bytes)

/** 分组用的时间：移入回收站 / 压缩的时间 */
private fun GalleryTimelineItem.Photo.binTime(): Long = when (val tag = tag) {
    is TrashUnit -> tag.quarantinedAt
    is BackupUnit -> tag.createdAt
    else -> image.mtime
}

/** 分组用的大小：整组合计 / 原图备份的大小 */
private fun GalleryTimelineItem.Photo.binBytes(): Long = when (val tag = tag) {
    is TrashUnit -> tag.bytes
    is BackupUnit -> tag.originalSize
    else -> image.size
}

/** 删除原因的显示名：手动删除、聊天来源、按时间清理合成一类，规则名照用 */
internal fun trashReasonLabel(reason: String): String = when (reason) {
    "用户批量删除", "用户手动删除" -> "手动删除"
    "保存后移入回收站" -> "保存后删除"
    "按 QQ 来源手动删除", "按 QQ 图片来源清理" -> "聊天来源"
    else -> when {
        reason.isBlank() -> "其他"
        TIME_RANGE.matches(reason) -> "按时间清理"
        else -> reason
    }
}

private val TIME_RANGE = Regex("\\d{4} 年( \\d{1,2} 月)?")
