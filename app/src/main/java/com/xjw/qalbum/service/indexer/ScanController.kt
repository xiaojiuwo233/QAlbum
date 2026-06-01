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
 * - 持有当前扫描进度 [progress]，UI 多页面可以共享订阅（Dashboard / Settings / 扫描提示等）
 * - 保证同一时间只有一个扫描任务在跑（[currentJob]）
 *
 * 真正的扫描逻辑委托给 [ImageIndexer] / [IncrementalScanner]。
 */
class ScanController(
    private val settings: SettingsRepository,
    private val indexer: ImageIndexer,
    private val incrementalScanner: IncrementalScanner,
) {
    // SupervisorJob：单个扫描失败不影响 controller 后续工作
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _progress = MutableStateFlow<ScanProgress?>(null)
    val progress: StateFlow<ScanProgress?> = _progress.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private var currentJob: Job? = null

    fun startFullScan() {
        if (_running.value) {
            Timber.w("ScanController.startFullScan: another scan is running, ignore")
            return
        }
        currentJob = scope.launch {
            _running.value = true
            try {
                val root = settings.chatpicPath.first()
                indexer.fullScan(root, force = true).collect { _progress.value = it }
            } catch (t: Throwable) {
                Timber.e(t, "Full scan failed")
                _progress.value = ScanProgress.Failed(t.message ?: "未知错误")
            } finally {
                _running.value = false
            }
        }
    }

    fun startIncrementalScan() {
        if (_running.value) {
            Timber.w("ScanController.startIncrementalScan: another scan is running, ignore")
            return
        }
        currentJob = scope.launch {
            _running.value = true
            try {
                val root = settings.chatpicPath.first()
                incrementalScanner.scan(root).collect { _progress.value = it }
            } catch (t: Throwable) {
                Timber.e(t, "Incremental scan failed")
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
