package com.xjw.qalbum.qq

import android.content.Context
import android.database.sqlite.SQLiteDatabaseCorruptException
import androidx.room.withTransaction
import com.xjw.qalbum.data.db.AppDatabase
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.fileops.FileOpsClient
import com.xjw.qalbum.service.cleanup.QuarantineManager
import com.xjw.qalbum.service.cleanup.PictureDeleteOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.collectLatest
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
    private var job: Job? = null
    private var facetJob: Job? = null

    fun startFacetCache() {
        if (facetJob?.isActive == true) return
        facetJob = appScope.launch(Dispatchers.IO) {
            dao.ensureCache()
            // 导入/回收站处理中不反复聚合；变更只标记脏位，完成后一次刷新持久化统计。
            combine(dao.observeCacheDirty(), progress) { dirty, running -> dirty == true && running == null }
                .debounce(400).collectLatest { refresh ->
                    if (refresh) db.withTransaction {
                        if (dao.cacheDirty() == true) {
                            dao.clearFacets()
                            dao.cacheConversations()
                            dao.cacheSenders()
                            dao.finishCache()
                        }
                    }
                }
        }
    }

    fun acknowledgeMessage() { _message.value = null }
    private fun report(text: String) { _report.value = text; _message.value = text }
    fun cancel() { if (_progress.value?.cancellable == true) job?.cancel() }

    fun refresh() {
        if (!_progress.compareAndSet(null, QqSourceProgress("获取 Root 权限"))) return
        job = appScope.launch(Dispatchers.IO) {
            var bound: com.xjw.qalbum.fileops.BoundFileOps? = null
            var successful = 0
            var pictures = 0
            var skipped = 0
            var unreadable = 0
            var incompleteTables = 0
            var failed = 0
            var stage = "root_service"
            val failures = mutableListOf<String>()
            try {
                bound = reader.bind()
                stage = "account_catalog"
                _progress.value = QqSourceProgress("读取 QQ 账号目录")
                val accounts = reader.accounts(bound)
                check(accounts.isNotEmpty()) { "QQ 未缓存可读取的账号数据库" }
                settings.setQqSourcesComplete(false)
                val root = settings.chatpicPath.first()
                accounts.forEachIndexed { index, account ->
                    currentCoroutineContext().ensureActive()
                    try {
                        stage = "database_snapshot"
                        reader.snapshot(bound, account) { done, total ->
                            _progress.value = QqSourceProgress("读取账号 ${index + 1}/${accounts.size} 的只读副本", done, total)
                        }.use { snapshot ->
                            stage = "source_import"
                            val result = QqSourceImporter(db).import(snapshot, account, index + 1, root) { done, total ->
                                _progress.value = QqSourceProgress("关联账号 ${index + 1}/${accounts.size} 的图片来源 · 已检查 $done 条记录", done.toLong(), total.toLong())
                            }
                            pictures += result.pictures
                            skipped += result.skipped
                            unreadable += result.unreadable
                            incompleteTables += result.incompleteTables
                            successful++
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        failed++
                        failures += "账号 ${index + 1}：${failureReason(e, stage)}"
                        logFailure(e, stage)
                    }
                }
                if (successful > 0) {
                    stage = "converted_cache"
                    _progress.value = QqSourceProgress("关联 QQ 转码图片")
                    var offset = 0
                    val base = root.trimEnd('/')
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val rows = bound.callWithTimeout { it.listFiles("$base/Temp", offset, 512) }
                        val entries = rows.filter { it != "+" }
                        db.withTransaction {
                            entries.forEach { row ->
                                val filename = row.split('|', limit = 3).getOrNull(2) ?: return@forEach
                                val reference = QqCachePaths.convertedThumbnailSource(filename) ?: return@forEach
                                dao.registerDerivedPath("$base/Temp/$filename", "$base/$reference")
                            }
                        }
                        offset += entries.size
                        if ("+" !in rows || entries.isEmpty()) break
                    }
                    dao.reconcileLinks()
                }
                val complete = successful == accounts.size && skipped == 0 && unreadable == 0 && incompleteTables == 0
                settings.setQqSourcesComplete(complete)
                report(if (successful == 0) failures.joinToString("；").ifBlank { "没有可读取的图片来源" } else
                    "已读取 $successful 个账号、$pictures 条图片来源" +
                        (if (failed > 0) "；$failed 个账号读取失败" else "") +
                        (if (skipped > 0) "；$skipped 条记录未能解析" else "") +
                        (if (unreadable > 0) "；$unreadable 条记录无法读取" else "") +
                        (if (incompleteTables > 0) "；部分消息范围未能读取" else "") +
                        (if (!complete) "；部分来源未能读取，共用统计可能有遗漏" else "") +
                        (if (failures.isNotEmpty()) "；" + failures.joinToString("；") else ""))
            } catch (_: CancellationException) {
                report("已停止读取；已完成的账号索引保留")
            } catch (e: Exception) {
                logFailure(e, stage)
                report(failureReason(e, stage))
            } finally {
                bound?.close()
                _progress.value = null
            }
        }
    }

    private fun logFailure(error: Exception, stage: String) {
        // 异常消息/栈可能带 QQ 账号路径或 SQL；仅记录阶段及类型。
        Timber.w("QQ source failed: stage=%s type=%s", stage, error.javaClass.simpleName)
        error.stackTrace.firstOrNull { it.className.startsWith("com.xjw.qalbum.qq.") }?.let {
            Timber.w("QQ source failure location: class=%s method=%s line=%d", it.className.substringAfterLast('.'), it.methodName, it.lineNumber)
        }
    }

    private fun failureReason(error: Exception, stage: String): String {
        if (error is SQLiteDatabaseCorruptException) return "QQ 只读副本包含无法读取的数据页；已保留原有索引，请重新读取后重试"
        return when (error.message) {
            "未取得 QAlbum 的 Root 授权" -> "未取得 QAlbum 的 Root 授权，请在 Root 管理器中允许"
            "Root 已授权，但文件服务未能启动" -> "Root 已授权，但文件服务未能启动，请重试"
            "QQ 账号目录不可读" -> "Root 已授权，但 QQ 私有账号目录不可读，请重新启动 QAlbum 后重试"
            "QQ 未缓存可读取的账号数据库" -> "本机没有可读取的 QQ 账号缓存"
            "空间不足，无法创建 QQ 只读副本" -> "空间不足，无法创建 QQ 只读副本"
            "QQ 数据库副本无法解密，当前版本可能尚不兼容" -> "QQ 数据库副本无法解密，当前版本可能尚不兼容"
            "QQ 数据库正在更新，请稍后重试；原有来源索引仍保留", "QQ 数据库在读取期间发生变化" -> "QQ 数据库正在更新，请稍后重试；原有索引仍保留"
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

    /** 按来源范围整体移入回收站（收藏 / 保留的图片跳过；[includeShared] 决定是否包含也出现在其他会话的图片） */
    fun trash(scope: QqSourceScope, includeShared: Boolean, linkedVersions: Boolean = true) {
        if (scope.groupKey == null && scope.senderKey == null) return
        if (!_progress.compareAndSet(null, QqSourceProgress("准备移入回收站", cancellable = false))) return
        job = appScope.launch(Dispatchers.IO) {
            var moved = 0
            try {
                val counts = dao.counts(scope.groupKey, scope.senderKey, includeShared)
                val maxId = dao.maxImageId() ?: 0L
                var afterId = 0L
                var processed = 0L
                while (true) {
                    val ids = dao.removableIds(scope.groupKey, scope.senderKey, includeShared, afterId, maxId, 200)
                    if (ids.isEmpty()) break
                    moved += quarantine.quarantinePictures(ids,PictureDeleteOptions(linkedVersions,includeShared),"按 QQ 图片来源清理",scope).selectedProcessed
                    _progress.value = QqSourceProgress("移入回收站", processed + ids.size, counts.removable.toLong(), false)
                    processed += ids.size
                    afterId = ids.last()
                }
                _message.value = "已移入回收站 $moved 张" + if (moved < counts.removable) "；部分图片未移动，仍保留在原处" else ""
            } catch (_: Exception) {
                _message.value = "已移入回收站 $moved 张，操作未全部完成"
            } finally {
                _progress.value = null
            }
        }
    }

    fun clear() {
        if (!_progress.compareAndSet(null, QqSourceProgress("清除来源索引", cancellable = false))) return
        job = appScope.launch(Dispatchers.IO) {
            try {
                settings.setQqSourcesComplete(false)
                db.withTransaction { dao.clear() }
                _report.value = null
                _message.value = "已清除来源索引"
            } catch (_: Exception) {
                _message.value = "来源索引清除失败"
            } finally { _progress.value = null }
        }
    }
}
