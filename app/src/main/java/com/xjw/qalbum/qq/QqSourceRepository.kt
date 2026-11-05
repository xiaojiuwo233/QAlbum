package com.xjw.qalbum.qq

import android.content.Context
import android.os.ParcelFileDescriptor
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import com.xjw.qalbum.analytics.Analytics
import com.xjw.qalbum.analytics.chatIndexCleared
import com.xjw.qalbum.analytics.chatRead
import com.xjw.qalbum.analytics.deleted
import com.xjw.qalbum.analytics.elapsedMs
import com.xjw.qalbum.data.db.AppDatabase
import com.xjw.qalbum.data.db.QqSourceStatsSql
import com.xjw.qalbum.data.db.StatsCache
import com.xjw.qalbum.data.db.StatsCache.Companion.holding
import com.xjw.qalbum.data.db.dao.QqContent
import com.xjw.qalbum.data.db.dao.QqReadBaseline
import com.xjw.qalbum.data.db.dao.QqScopeIds
import com.xjw.qalbum.data.db.dao.QqScopeSql
import com.xjw.qalbum.data.db.entity.QqReadMarkEntity
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.fileops.FileOpsClient
import com.xjw.qalbum.log.AppLog
import com.xjw.qalbum.service.cleanup.ArchiveManager
import com.xjw.qalbum.service.cleanup.QuarantineManager
import com.xjw.qalbum.service.cleanup.PictureDeleteOptions
import com.xjw.qalbum.service.work.BackgroundTasks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import timber.log.Timber

data class QqSourceScope(val groupKey: String? = null, val senderKey: String? = null)
data class QqSourceProgress(val label: String, val done: Long = 0, val total: Long = 0, val cancellable: Boolean = true)

class QqSourceRepository(
    context: Context,
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    client: FileOpsClient,
    private val quarantine: QuarantineManager,
    private val appScope: CoroutineScope,
    private val tasks: BackgroundTasks,
    private val archive: ArchiveManager,
    /** 读取期间暂停清理页的统计（几十万条来源写入时不来回重算） */
    private val statsCache: StatsCache? = null,
    private val analytics: Analytics? = null,
) {
    val dao = db.qqSourceDao()
    val sourcesComplete = settings.qqSourcesComplete
    private val reader by lazy { QqSnapshotReader(context, client) }
    private val _progress = MutableStateFlow<QqSourceProgress?>(null)
    val progress = _progress.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val _report = MutableStateFlow<String?>(null)
    val report = _report.asStateFlow()
    /** 手动读取时 QQ 正写着数据库、没读成：来源页问要不要强制停止 QQ 再读（值为那次是否完整读取）；null = 没有 */
    private val _forceStopOffer = MutableStateFlow<Boolean?>(null)
    val forceStopOffer = _forceStopOffer.asStateFlow()
    private var job: Job? = null
    private var statsJob: Job? = null
    private val stats = QqSourceStatsBuilder(db)
    @Volatile private var statsFailed = false

    /**
     * 统计维护：缓存标脏（第一次读取、升级、清除索引后等）且没有其他来源任务时，在后台分段重建。
     * 删图由触发器增量更新，读取来源后由 [refresh] 增量更新，都不会触发整体重建。
     */
    fun startFacetCache() {
        if (statsJob?.isActive == true) return
        statsJob = appScope.launch(Dispatchers.IO) {
            combine(dao.observeCacheDirty(), progress) { dirty, running -> dirty != false && running == null }
                .distinctUntilChanged()
                .collect { needed -> if (needed && !statsFailed) rebuildStats() }
        }
    }

    private suspend fun rebuildStats() {
        val label = "整理来源统计"
        if (!_progress.compareAndSet(null, QqSourceProgress(label, cancellable = false))) return
        val started = System.currentTimeMillis()
        try {
            mirrored(label, cancellable = false) {
                stats.rebuild { fraction ->
                    _progress.value = QqSourceProgress(label, ((fraction ?: 0f) * 1000).toLong(), if (fraction == null) 0 else 1000, false)
                }
            }
            Timber.i("QQ source stats rebuilt in %d ms", System.currentTimeMillis() - started)
            // 新读到的来源里，自动归档名单上的聊天的图一并归档
            runCatching { archive.applyAutoArchive() }.onFailure { Timber.w(it, "Auto archive failed") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 失败后不自动重试（避免循环），下次读取来源时再整理
            statsFailed = true
            logFailure(e, "stats_rebuild")
            _message.value = "来源统计整理失败，可重新读取来源后重试"
        } finally {
            _progress.value = null
        }
    }

    /**
     * 把 [_progress] 同步到后台任务（通知栏显示进度，切到后台也保持前台优先级）。
     * [cancellable] 时通知上的「停止」取消当前协程，与页面上的停止按钮效果相同。
     */
    private suspend fun <T> mirrored(title: String, cancellable: Boolean, block: suspend () -> T): T =
        tasks.track(title, cancellable) { handle ->
            coroutineScope {
                val mirror = launch {
                    progress.collect { p -> if (p != null) handle.update(p.label, p.done, p.total) }
                }
                try {
                    block()
                } finally {
                    mirror.cancel()
                }
            }
        }

    /**
     * 不与读取来源、整理统计同时进行地执行 [block]（精简数据库时用：期间不写来源）；已有来源任务在跑时返回 null。
     * 期间来源页显示 [label]。
     */
    suspend fun <T> exclusive(label: String, block: suspend () -> T): T? {
        if (!_progress.compareAndSet(null, QqSourceProgress(label, cancellable = false))) return null
        return try {
            block()
        } finally {
            _progress.value = null
        }
    }

    fun acknowledgeMessage() { _message.value = null }
    private fun report(text: String, snackbar: Boolean = true) { _report.value = text; if (snackbar) _message.value = text }
    fun cancel() { if (_progress.value?.cancellable == true) job?.cancel() }
    fun dismissForceStop() { _forceStopOffer.value = null }

    /** 弹窗里确认了：强制停止 QQ，再按那次的方式读一次（有别的来源任务在跑时先留着，跑完再问） */
    fun forceStopAndRefresh() {
        val full = _forceStopOffer.value ?: return
        refresh(full, forceStopQq = true)
    }

    /** 读取并等它结束（后台定时读取用）；已经在读时直接返回 */
    suspend fun refreshAndWait(full: Boolean = false) {
        refresh(full, automatic = true)?.join()
    }

    /**
     * 读取聊天来源。默认增量：一周内从头读过的消息表只解析之后的新消息；[full] 为真或超过一周的表从头读。
     * 所有账号、所有表都从头读完且没有读不了的记录，才算一次完整读取（记下 complete_read_at）。
     * 读完只给这次新登记的路径补关联、只把新增的来源与关联计入统计（见 QqSourceStatsSql.applyNew），
     * 不必把几十万张图与来源整体对一遍。已经有来源任务在跑时返回 null。[automatic] 是后台定时读取（统计用）。
     * [forceStopQq] 先强制停止 QQ（用户在弹窗里确认的，见 [forceStopOffer]）。
     */
    fun refresh(full: Boolean = false, automatic: Boolean = false, forceStopQq: Boolean = false): Job? {
        if (!_progress.compareAndSet(null, QqSourceProgress("获取 Root 权限"))) return null
        _forceStopOffer.value = null
        statsFailed = false
        startFacetCache()
        val started = appScope.launch(Dispatchers.IO) { statsCache.holding { mirrored("读取聊天来源", cancellable = true) {
            var bound: com.xjw.qalbum.fileops.BoundFileOps? = null
            var successful = 0
            var pictures = 0
            var skipped = 0
            var unreadable = 0
            var incompleteTables = 0
            var failed = 0
            var stage = "root_service"
            val failures = mutableListOf<String>()
            // 这次读取的开始时间：读到的消息都记成这个时间，完整读取后没再见到的就是 QQ 里删掉的
            val readStart = System.currentTimeMillis()
            // 有表只读了新消息（增量）时不算完整读取
            var fullPass = true
            var copyMs = 0L
            var importMs = 0L
            var linksMs = 0L
            var previewsMs = 0L
            var statsMs = 0L
            val renamed = mutableSetOf<Pair<Int, String>>()
            // 统计：账号数、结果（ok / partial / failed / cancelled）、出错的那一步与异常类型
            var accountCount = 0
            var outcome = "failed"
            var failedStage: String? = null
            var failure: Exception? = null
            // 有账号因为 QQ 正写着数据库没读成（手动读取时问要不要强制停止 QQ 再读）
            var qqBusy = false
            try {
                // 解析规则更新过（比如新认出表情子类型）：这次从头读，旧来源才补得上
                val reparse = settings.qqParseVersion.first() < QqPictureCodec.PARSE_VERSION
                val fromStart = full || reparse
                // 这次新写入的来源、路径、关联都在这个位置之后
                val baseline = readBaseline()
                bound = reader.bind()
                if (forceStopQq) {
                    _progress.value = QqSourceProgress("强制停止 QQ")
                    // 停不下来也照样读：复制时会核对
                    val stopped = try { reader.forceStopQq(bound) } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
                    Timber.i("QQ force-stopped before reading: ok=%s", stopped)
                }
                stage = "account_catalog"
                _progress.value = QqSourceProgress("读取 QQ 账号目录")
                val accounts = reader.accounts(bound)
                check(accounts.isNotEmpty()) { "QQ 未缓存可读取的账号数据库" }
                accountCount = accounts.size
                // 账号目录与 uid 只在详细日志里记（排查读不到某个账号时用）
                if (AppLog.detailed) Timber.i("QQ accounts: %s", accounts.joinToString { "${it.key} uid=${it.uid}" })
                settings.setQqSourcesComplete(false)
                val root = settings.chatpicPath.first()
                accounts.forEachIndexed { index, account ->
                    currentCoroutineContext().ensureActive()
                    var qqRunning = false
                    try {
                        stage = "database_snapshot"
                        val copyStarted = System.currentTimeMillis()
                        reader.snapshot(bound, account) { done, total ->
                            _progress.value = QqSourceProgress("读取账号 ${index + 1}/${accounts.size} 的只读副本", done, total)
                        }.use { snapshot ->
                            qqRunning = snapshot.qqRunning
                            stage = "source_import"
                            val importStarted = System.currentTimeMillis()
                            copyMs += importStarted - copyStarted
                            val existing = dao.readMarks(account.key).associateBy { it.tableName }
                            val startAfter = if (fromStart) emptyMap() else existing.values
                                .filter { readStart - it.fullReadAt < FULL_READ_INTERVAL_MS }
                                .associate { it.tableName to it.maxRowid }
                            if (startAfter.isNotEmpty()) fullPass = false
                            val result = QqSourceImporter(db).import(snapshot, account, index + 1, root, readStart, startAfter, allPaths = reparse) { done, total ->
                                _progress.value = QqSourceProgress("关联账号 ${index + 1}/${accounts.size} 的图片来源 · 已检查 $done 条记录", done.toLong(), total.toLong())
                            }
                            importMs += System.currentTimeMillis() - importStarted
                            renamed += result.renamed
                            // 新水位：从头读的表记下这次的时间，增量读的沿用上次从头读的时间；没读完整的表下次从头读
                            dao.putReadMarks(result.marks.map { (table, rowid) ->
                                QqReadMarkEntity(account.key, table, rowid,
                                    if (table in startAfter) existing.getValue(table).fullReadAt else readStart)
                            })
                            (existing.keys - result.marks.keys).takeIf { it.isNotEmpty() }?.let { dao.deleteReadMarks(account.key, it) }
                            pictures += result.pictures
                            skipped += result.skipped
                            unreadable += result.unreadable
                            incompleteTables += result.incompleteTables
                            successful++
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        failed++
                        // 复制时 QQ 在运行、副本读不了，多半也是 QQ 正写着（强制停止 QQ 再读就好）
                        if (e is QqDatabaseBusyException || qqRunning && (e is SQLiteException || e.message == DECRYPT_FAILED)) qqBusy = true
                        failures += "账号 ${index + 1}：${failureReason(e, stage)}"
                        logFailure(e, stage)
                        failedStage = stage
                        failure = e
                    }
                }
                if (successful > 0) {
                    // 新读到的消息按家族键关联已入库的图片（原图、预览图、缩略图、带后缀的别名、Temp 转码图都在同一家族）
                    stage = "links"
                    val linksStarted = System.currentTimeMillis()
                    _progress.value = QqSourceProgress("关联图片来源")
                    dao.linkAfter(baseline)
                    val previewsStarted = System.currentTimeMillis()
                    linksMs = previewsStarted - linksStarted
                    // 自己发的图多出的 chatimg 预览按 MD5 关联不上，按同一次发送的原图补上来源
                    stage = "sent_previews"
                    val label = "关联自己发的图的预览"
                    _progress.value = QqSourceProgress(label)
                    val ops = bound
                    QqSentPreviewLinker(db) { path ->
                        ops.callWithTimeout(onAbandoned = { it?.close() }) { it.openRead(path) }
                            ?.let(ParcelFileDescriptor::AutoCloseInputStream)
                    }.link(accounts.map { opaqueKey(it.key, "sender", it.uid) }) { done, total ->
                        _progress.value = QqSourceProgress(label, done.toLong(), total.toLong())
                    }
                    // 来源统计：没标脏时只把新增的来源与关联算进去（改了名的会话 / 发送人重算名称）；标了脏的等整体重建
                    stage = "source_stats"
                    val statsStarted = System.currentTimeMillis()
                    previewsMs = statsStarted - previewsStarted
                    _progress.value = QqSourceProgress("更新来源统计")
                    val incremental = stats.applyNew(baseline, renamed)
                    statsMs = System.currentTimeMillis() - statsStarted
                    settings.setQqReadBaseline(null)
                    // 新读到的来源里，自动归档名单上的聊天的图一并归档（标了脏的整体重建后再归档）
                    if (incremental) runCatching { archive.applyAutoArchive() }.onFailure { Timber.w(it, "Auto archive failed") }
                }
                // 个别解析不了的消息（多是没缓存内容的合并转发）和 QQ 库里本来就坏的几行每次都一样，不算没读完；
                // 只有账号读取失败、消息表没读完才算
                val complete = successful == accounts.size && incompleteTables == 0
                outcome = when {
                    successful == 0 -> "failed"
                    !complete -> "partial"
                    else -> "ok"
                }
                settings.setQqSourcesComplete(complete)
                if (complete && fullPass) {
                    dao.setCompleteReadAt(readStart)
                    settings.setQqParseVersion(QqPictureCodec.PARSE_VERSION)
                }
                dao.seenSummary(readStart).let { seen ->
                    Timber.i("QQ source sweep: origins=%d unseen=%d complete=%s fullPass=%s skipped=%d unreadable=%d incompleteTables=%d",
                        seen.total, seen.stale, complete, fullPass, skipped, unreadable, incompleteTables)
                }
                Timber.i("QQ source read: accounts=%d/%d pictures=%d copy=%dms import=%dms links=%dms previews=%dms stats=%dms total=%dms",
                    successful, accounts.size, pictures, copyMs, importMs, linksMs, previewsMs, statsMs, System.currentTimeMillis() - readStart)
                val summary = if (successful == 0) failures.joinToString("；").ifBlank { "没有可读取的图片来源" } else
                    (if (fullPass) "已读取" else "已读取新消息：") + "$successful 个账号、$pictures 条图片来源" +
                        (if (failed > 0) "；$failed 个账号读取失败" else "") +
                        (if (skipped > 0) "；$skipped 条记录未能解析" else "") +
                        (if (unreadable > 0) "；$unreadable 条记录无法读取" else "") +
                        (if (incompleteTables > 0) "；部分消息范围未能读取" else "") +
                        (if (!complete) "；部分来源未能读取，共用统计可能有遗漏" else "") +
                        (if (failures.isNotEmpty()) "；" + failures.joinToString("；") else "")
                // 要问强制停止 QQ 时弹窗就是提示，不再弹消息条
                report(summary, snackbar = !(qqBusy && offersForceStop(automatic, forceStopQq)))
            } catch (_: CancellationException) {
                outcome = "cancelled"
                report("已停止读取；已完成的账号索引保留")
            } catch (e: Exception) {
                outcome = "failed"
                failedStage = stage
                failure = e
                logFailure(e, stage)
                report(failureReason(e, stage))
            } finally {
                bound?.close()
                // 强制停止过还是不行就不再问
                if (qqBusy && outcome != "cancelled" && offersForceStop(automatic, forceStopQq)) _forceStopOffer.value = full
                _progress.value = null
                analytics?.chatRead(
                    automatic, if (fullPass) "full" else "incremental", outcome, System.currentTimeMillis() - readStart,
                    copyMs, importMs, linksMs + previewsMs + statsMs, accountCount, failed, pictures, failedStage, failure,
                    forceStopQq,
                )
            }
        } } }
        job = started
        return started
    }

    /** 读取前的位置；上次读取没走完（中途退出）时沿用那次的，这次一并补上它写入的来源与家族 */
    private suspend fun readBaseline(): QqReadBaseline {
        settings.qqReadBaseline.first()?.split(':')?.mapNotNull(String::toLongOrNull)?.takeIf { it.size == 4 }
            ?.let { (origins, families, paths, links) -> return QqReadBaseline(origins, families, paths, links) }
        return dao.readBaseline().also { settings.setQqReadBaseline("${it.origins}:${it.families}:${it.paths}:${it.links}") }
    }

    /** 手动读取、这次也没强制停止过 QQ 时才问 */
    private fun offersForceStop(automatic: Boolean, forceStopped: Boolean) = !automatic && !forceStopped

    private companion object {
        /** 从头读一张消息表的间隔：超过一周再完整读一次，发现 QQ 里删掉的消息 */
        const val FULL_READ_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000
        const val DECRYPT_FAILED = "QQ 数据库副本无法解密，当前版本可能尚不兼容"
    }

    private fun logFailure(error: Exception, stage: String) {
        // 异常消息/栈可能带 QQ 账号路径或 SQL：开了详细日志才记完整的，平时只记阶段、类型和出错的位置
        if (AppLog.detailed) {
            Timber.w(error, "QQ source failed: stage=%s", stage)
            return
        }
        Timber.w("QQ source failed: stage=%s type=%s", stage, error.javaClass.simpleName)
        error.stackTrace.firstOrNull { it.className.startsWith("com.xjw.qalbum.qq.") }?.let {
            Timber.w("QQ source failure location: class=%s method=%s line=%d", it.className.substringAfterLast('.'), it.methodName, it.lineNumber)
        }
    }

    private fun failureReason(error: Exception, stage: String): String {
        if (error is QqDatabaseBusyException) return "QQ 正在写入数据库，没能读取；原有索引仍保留"
        if (error is SQLiteDatabaseCorruptException) return "QQ 只读副本包含无法读取的数据页；已保留原有索引，请重新读取后重试"
        return when (error.message) {
            "未取得 QAlbum 的 Root 授权" -> "未取得 QAlbum 的 Root 授权，请在 Root 管理器中允许"
            "Root 已授权，但文件服务未能启动" -> "Root 已授权，但文件服务未能启动，请重试"
            "QQ 账号目录不可读" -> "Root 已授权，但 QQ 私有账号目录不可读，请重新启动 QAlbum 后重试"
            "QQ 未缓存可读取的账号数据库" -> "本机没有可读取的 QQ 账号缓存"
            "空间不足，无法创建 QQ 只读副本" -> "空间不足，无法创建 QQ 只读副本"
            DECRYPT_FAILED -> DECRYPT_FAILED
            "尚不支持此 QQ 数据库格式", "QQ 图片来源字段尚不兼容", "QQ 消息表尚不兼容" -> "当前 QQ 数据库格式尚不兼容"
            else -> when (stage) {
                "root_service" -> "Root 文件服务连接失败，请重试"
                "account_catalog" -> "QQ 账号目录读取失败"
                "database_snapshot" -> "QQ 只读副本创建失败；原有索引仍保留"
                "source_import" -> "图片来源解析失败；原有索引仍保留"
                else -> "转码图片关联失败，已读取的来源索引保留"
            }
        }
    }

    /**
     * 按来源范围整体移入回收站（归档 / 保留的图片跳过）。[includeShared] 决定是否包含也出现在其他会话 /
     * 发送人的图片，[contents] 决定清理图片、QQ 缩略图或两者；[linkedVersions] 同时处理同一张图的其他缓存版本；
     * [before] 只清理文件时间早于它的（null 不限）。
     */
    fun trash(
        scope: QqSourceScope,
        includeShared: Boolean,
        linkedVersions: Boolean = true,
        contents: Set<QqContent> = setOf(QqContent.IMAGES),
        before: Long? = null,
    ) {
        if (scope.groupKey == null && scope.senderKey == null || contents.isEmpty()) return
        if (!_progress.compareAndSet(null, QqSourceProgress("准备移入回收站", cancellable = false))) return
        job = appScope.launch(Dispatchers.IO) { mirrored("按来源移入回收站", cancellable = false) {
            var moved = 0
            var total = 0
            var skipped = 0
            var partial = 0
            var bytes = 0L
            var outcome = "ok"
            val started = System.nanoTime()
            try {
                quarantine.fileAccessError()?.let { reason ->
                    _message.value = reason
                    return@mirrored
                }
                val ids = scopeIds(scope) ?: run {
                    _message.value = "来源统计还在整理，请稍后再试"
                    return@mirrored
                }
                val counts = dao.queryCounts(QqScopeSql.counts(ids, contents, includeShared, before))
                val maxId = dao.maxImageId() ?: 0L
                var afterId = 0L
                var processed = 0L
                _progress.value = QqSourceProgress("移入回收站", 0, counts.removable.toLong(), false)
                total = counts.removable
                while (true) {
                    val batch = dao.queryIds(QqScopeSql.removableIds(ids, contents, includeShared, afterId, maxId, 200, before))
                    if (batch.isEmpty()) break
                    val result = quarantine.quarantinePictures(batch, PictureDeleteOptions(linkedVersions, includeShared), "按 QQ 图片来源清理", scope)
                    moved += result.selectedProcessed
                    skipped += result.skipped
                    partial += result.partial
                    bytes += result.bytes
                    processed += batch.size
                    _progress.value = QqSourceProgress("移入回收站", processed, counts.removable.toLong(), false)
                    afterId = batch.last()
                }
                _message.value = "已移入回收站 $moved 张" + if (moved < counts.removable) "；部分图片因共用、保护或文件变化未移动" else ""
            } catch (e: CancellationException) {
                outcome = "cancelled"
                throw e
            } catch (_: Exception) {
                outcome = "failed"
                _message.value = "已移入回收站 $moved 张，操作未全部完成"
            } finally {
                _progress.value = null
                if (total > 0) analytics?.deleted("chat_scope", false, total, moved, skipped, partial, bytes, elapsedMs(started), result = outcome)
            }
        } }
    }

    /** 来源键 → 分类 id；分类尚未整理出来时返回 null */
    suspend fun scopeIds(scope: QqSourceScope): QqScopeIds? {
        val conversation = scope.groupKey?.let { dao.facetId(0, it) ?: return null }
        val sender = scope.senderKey?.let { dao.facetId(1, it) ?: return null }
        return QqScopeIds(conversation, sender)
    }

    fun clear() {
        if (!_progress.compareAndSet(null, QqSourceProgress("清除来源索引", cancellable = false))) return
        job = appScope.launch(Dispatchers.IO) {
            try {
                settings.setQqSourcesComplete(false)
                // 清空后行号从头分配，上次没走完的读取位置不再适用
                settings.setQqReadBaseline(null)
                val sql = db.openHelper.writableDatabase
                db.withTransaction {
                    dao.clear()
                    QqSourceStatsSql.clearDerived.forEach(sql::execSQL)
                }
                _report.value = null
                _message.value = "已清除来源索引"
                analytics?.chatIndexCleared()
            } catch (_: Exception) {
                _message.value = "来源索引清除失败"
            } finally { _progress.value = null }
        }
    }
}
