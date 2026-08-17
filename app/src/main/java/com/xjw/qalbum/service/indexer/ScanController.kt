package com.xjw.qalbum.service.indexer

import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.domain.model.ScanProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
 * 状态一致性：协程取消是协作式的，旧 job 可能在新扫描启动后才真正收尾，
 * 因此所有 _running/_progress 写入都要在 [stateLock] 下校验「自己仍是
 * currentJob」——迟到的旧 job 收尾不允许打翻新扫描的状态。
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

    /** 守护 [currentJob] 与 _running/_progress 写入的一致性 */
    private val stateLock = Any()

    private var currentJob: Job? = null

    fun startIncrementalScan() = start(ScanMode.INCREMENTAL)

    fun startFullScan() = start(ScanMode.FULL)

    fun startDeepVerify() = start(ScanMode.DEEP_VERIFY)

    private fun start(mode: ScanMode) {
        // LAZY 启动：在锁内完成「判定 + 置 running + 记录 job」的原子发布，
        // 协程体开始执行时自身引用已可用于身份校验，消除 check-then-act 双开窗口
        synchronized(stateLock) {
            if (currentJob?.isActive == true) {
                Timber.w("ScanController.start($mode): another scan is running, ignore")
                return
            }
            val job = scope.launch(start = CoroutineStart.LAZY) {
                val self = coroutineContext[Job]
                try {
                    val root = settings.chatpicPath.first()
                    pipeline.scan(root, mode).collect { p -> setProgressIfCurrent(self, p) }
                } catch (ce: CancellationException) {
                    // 用户主动取消属正常流程：标记为「已取消」并交还协程框架，不展示为失败
                    setProgressIfCurrent(self, ScanProgress.Cancelled)
                    throw ce
                } catch (t: Throwable) {
                    Timber.e(t, "Scan($mode) failed")
                    setProgressIfCurrent(self, ScanProgress.Failed(t.message ?: "未知错误"))
                } finally {
                    synchronized(stateLock) {
                        if (currentJob === self) {
                            currentJob = null
                            _running.value = false
                        }
                    }
                }
            }
            currentJob = job
            _running.value = true
            job.start()
        }
    }

    /**
     * 请求取消当前扫描。不立即改 _running：管线在不可中断的 binder 调用/事务里
     * 时取消需要时间，立即置 false 会造成「已停止」假象并放行第二个扫描叠加；
     * 收尾统一由被取消 job 的 finally 在身份校验后完成。
     */
    fun cancel() {
        synchronized(stateLock) { currentJob }?.cancel()
    }

    /** 仅当 [self] 仍是当前任务时写进度，旧 job 迟到的进度/终态不覆盖新扫描 */
    private fun setProgressIfCurrent(self: Job?, value: ScanProgress) {
        synchronized(stateLock) {
            if (currentJob === self) _progress.value = value
        }
    }
}
