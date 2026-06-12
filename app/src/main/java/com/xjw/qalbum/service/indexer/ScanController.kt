package com.xjw.qalbum.service.indexer

import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.domain.model.ScanProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import timber.log.Timber

/**
 * 扫描调度器（应用级单例）
 *
 * - 持有当前扫描进度 [progress]，UI 多页面共享订阅（Dashboard / Settings 等）
 * - 保证同一时间只有一个扫描任务在跑（[currentJob]）
 *
 * 三档入口共用 [ScanPipeline]：
 * - [startIncrementalScan]：目录剪枝，常态秒级；
 * - [startFullScan]：所有目录做文件级 diff（只对新增/变化读文件头）；
 * - [startDeepVerify]：全量基础上重读所有文件头，修历史误判（低频、耗时较长）。
 */
class ScanController(
    private val settings: SettingsRepository,
    private val pipeline: ScanPipeline,
) {
    // SupervisorJob：单个扫描失败不影响 controller 后续工作
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _progress = MutableStateFlow<ScanProgress?>(null)
    val progress: StateFlow<ScanProgress?> = _progress.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private var currentJob: Job? = null

    fun startIncrementalScan() = start(ScanMode.INCREMENTAL)

    fun startFullScan() = start(ScanMode.FULL)

    fun startDeepVerify() = start(ScanMode.DEEP_VERIFY)

    private fun start(mode: ScanMode) {
        if (_running.value) {
            Timber.w("ScanController.start($mode): another scan is running, ignore")
            return
        }
        currentJob = scope.launch {
            _running.value = true
            try {
                val root = settings.chatpicPath.first()
                pipeline.scan(root, mode).collect { _progress.value = it }
            } catch (t: Throwable) {
                Timber.e(t, "Scan($mode) failed")
                _progress.value = ScanProgress.Failed(t.message ?: "未知错误")
            } finally {
                _running.value = false
            }
        }
    }

    fun cancel() {
        currentJob?.cancel()
        currentJob = null
        _running.value = false
    }
}
