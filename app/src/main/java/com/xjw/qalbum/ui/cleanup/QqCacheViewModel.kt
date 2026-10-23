package com.xjw.qalbum.ui.cleanup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xjw.qalbum.domain.model.AccessMode
import com.xjw.qalbum.qq.QqCacheTargets
import com.xjw.qalbum.service.cleanup.QqCacheCleaner
import com.xjw.qalbum.service.work.BackgroundTasks
import com.xjw.qalbum.ui.common.formatBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 「QQ 缓存」页：列出 QQ 清理缓存时会清空的目录（各自的文件数与大小，可展开看占用最大的几项、复制路径），
 * 勾选后清空。内部目录（cache/、code_cache/ 等）要 Root，只在 Root 模式下列出。
 * 清空跑在 App 级作用域，进度与结果由清理页根部显示。
 */
class QqCacheViewModel(
    private val cleaner: QqCacheCleaner,
    private val tasks: BackgroundTasks,
    private val appScope: CoroutineScope,
) : ViewModel() {

    data class Ui(
        val loading: Boolean = true,
        /** 文件访问没就绪（没连 Root / Shizuku） */
        val unavailable: Boolean = false,
        /** QQ 清理缓存时整个清空的三个目录（不管有没有） */
        val system: List<QqCacheCleaner.Entry> = emptyList(),
        /** QQ 旧版清理登记的目录里，这台设备上有内容的（大的在前） */
        val legacy: List<QqCacheCleaner.Entry> = emptyList(),
        val selected: Set<String> = emptySet(),
        val expanded: Set<String> = emptySet(),
        /** 展开的目录里占用最大的几项（还在读为 null） */
        val children: Map<String, List<QqCacheCleaner.Child>?> = emptyMap(),
    ) {
        val entries: List<QqCacheCleaner.Entry> get() = system + legacy
        val selectedEntries: List<QqCacheCleaner.Entry> get() = entries.filter { it.path in selected && it.clearable }
    }

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    val busy: StateFlow<Boolean> = CleanupOpState.busy.asStateFlow()

    /** 用户动过勾选后，重新统计不再自动全选 */
    private var touched = false
    private var loadJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch { load() }
    }

    private suspend fun load() {
        _ui.update { it.copy(loading = true) }
        val entries = try {
            cleaner.scan()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "统计 QQ 缓存失败")
            CleanupOpState.message.value = "统计失败：${e.message ?: e.javaClass.simpleName}"
            null
        }
        if (entries == null) {
            _ui.update { it.copy(loading = false, unavailable = true) }
            return
        }
        val rootMode = cleaner.mode() == AccessMode.ROOT
        val visible = if (rootMode) entries else entries.filter { it.target.area == QqCacheTargets.Area.EXTERNAL }
        val system = visible.filter { it.target.system }
        val legacy = visible.filter { !it.target.system && it.clearable }.sortedByDescending { it.bytes }
        val clearable = (system + legacy).filter { it.clearable }.mapTo(HashSet()) { it.path }
        _ui.update { old ->
            old.copy(
                loading = false,
                unavailable = false,
                system = system,
                legacy = legacy,
                selected = if (touched) old.selected.intersect(clearable) else clearable,
                expanded = old.expanded.intersect(clearable),
                children = emptyMap(),
            )
        }
        _ui.value.expanded.forEach(::loadChildren)
    }

    fun toggle(path: String) {
        touched = true
        _ui.update { it.copy(selected = if (path in it.selected) it.selected - path else it.selected + path) }
    }

    /** 整组勾选 / 取消 */
    fun setGroup(paths: Collection<String>, checked: Boolean) {
        touched = true
        _ui.update { it.copy(selected = if (checked) it.selected + paths else it.selected - paths.toSet()) }
    }

    /** 展开看占用最大的几项（再点收起） */
    fun toggleExpanded(path: String) {
        val open = path !in _ui.value.expanded
        _ui.update { it.copy(expanded = if (open) it.expanded + path else it.expanded - path) }
        if (open && _ui.value.children[path] == null) loadChildren(path)
    }

    private fun loadChildren(path: String) {
        viewModelScope.launch {
            _ui.update { it.copy(children = it.children + (path to null)) }
            val children = runCatching { cleaner.children(path) }.getOrElse { if (it is CancellationException) throw it else emptyList() }
            _ui.update { it.copy(children = it.children + (path to children)) }
        }
    }

    /** 清空选中的目录 */
    fun clear(paths: List<String>) {
        if (paths.isEmpty()) return
        val label = "清理 QQ 缓存"
        appScope.launchCleanupOp(label) {
            CleanupOpState.purgeProgress.value = PurgeProgress(0, paths.size, "正在清理 QQ 缓存…")
            val report = try {
                tasks.track(label, cancellable = false) { handle ->
                    cleaner.clear(paths) { done, total ->
                        CleanupOpState.purgeProgress.value = PurgeProgress(done, total, "正在清理 QQ 缓存…")
                        handle.update("已处理 $done / $total 个目录", done.toLong(), total.toLong())
                    }
                }
            } finally {
                CleanupOpState.purgeProgress.value = null
            }
            CleanupOpState.message.value = when {
                report == null -> "文件访问未授权，先在「设置 → 文件访问」里授权"
                report.failed > 0 || report.refused > 0 ->
                    "已清理 ${formatBytes(report.bytes)}，${report.failed + report.refused} 项没删掉（QQ 正在用或没有权限）"
                else -> "已清理 ${formatBytes(report.bytes)}"
            }
            withContext(Dispatchers.Main) { refresh() }
        }
    }
}
