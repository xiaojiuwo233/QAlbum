package com.xjw.qalbum.service.indexer

import com.xjw.qalbum.analytics.Analytics
import com.xjw.qalbum.analytics.elapsedMs
import com.xjw.qalbum.analytics.scanFinished
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.domain.model.ScanProgress
import com.xjw.qalbum.service.work.BackgroundTasks
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
    private val tasks: BackgroundTasks,
    private val sizeBackfill: ImageSizeBackfill,
    private val analytics: Analytics? = null,
) {
    // SupervisorJob：单个扫描失败不影响 controller 后续工作
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _progress = MutableStateFlow<ScanProgress?>(null)
    val progress: StateFlow<ScanProgress?> = _progress.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _automatic = MutableStateFlow(false)

    /** 当前（或最近一次）扫描是后台定时的自动扫描（界面上标出来，不让用户以为是自己点的） */
    val automatic: StateFlow<Boolean> = _automatic.asStateFlow()

    /** 守护 [currentJob] 与 _running/_progress 写入的一致性 */
    private val stateLock = Any()

    private var currentJob: Job? = null

    fun startIncrementalScan() = start(ScanMode.INCREMENTAL)

    fun startFullScan() = start(ScanMode.FULL)

    fun startDeepVerify() = start(ScanMode.DEEP_VERIFY)

    private fun start(mode: ScanMode) {
        launch(mode, automatic = false)
    }

    /**
     * 后台定时的自动扫描（Worker 里调用，扫完才返回）：与手动扫描共用进度，打开着 App 时概览和扫描页都看得到；
     * 不登记前台服务（后台起不了）。已有扫描在跑就跳过，返回 null；否则返回最后的进度。
     * 调用方被取消（Worker 被停：关了自动扫描、电量低）时扫描跟着停。
     */
    suspend fun runAutomatic(): ScanProgress? {
        val job = launch(ScanMode.INCREMENTAL, automatic = true) ?: return null
        try {
            job.join()
        } catch (ce: CancellationException) {
            job.cancel()
            throw ce
        }
        return synchronized(stateLock) { _progress.value }
    }

    private fun launch(mode: ScanMode, automatic: Boolean): Job? {
        // LAZY 启动：在锁内完成「判定 + 置 running + 记录 job」的原子发布，
        // 协程体开始执行时自身引用已可用于身份校验，消除 check-then-act 双开窗口
        synchronized(stateLock) {
            if (currentJob?.isActive == true) {
                Timber.w("ScanController.start($mode): another scan is running, ignore")
                return null
            }
            val job = scope.launch(start = CoroutineStart.LAZY) {
                val self = coroutineContext[Job]
                val started = System.nanoTime()
                var last: ScanProgress? = null
                try {
                    val root = settings.chatpicPath.first()
                    if (automatic) {
                        pipeline.scan(root, mode).collect { p ->
                            last = p
                            setProgressIfCurrent(self, p)
                        }
                    } else {
                        // 登记为后台任务：通知栏显示进度，切到后台也保持前台优先级
                        tasks.track(if (mode == ScanMode.INCREMENTAL) "扫描图片" else "全量扫描图片") { handle ->
                            pipeline.scan(root, mode).collect { p ->
                                last = p
                                setProgressIfCurrent(self, p)
                                when (p) {
                                    is ScanProgress.Enumerating -> handle.update("正在比对目录… 已发现 ${p.total} 个")
                                    is ScanProgress.Indexing -> handle.update("已扫描 ${p.processed} / ${p.total} 个目录", p.processed.toLong(), p.total.toLong())
                                    else -> Unit
                                }
                            }
                        }
                    }
                    // 以前入库、还没有宽高的图在后台补读一次（没有就什么也不做）
                    sizeBackfill.startIfNeeded()
                } catch (ce: CancellationException) {
                    // 用户主动取消属正常流程：标记为「已取消」并交还协程框架，不展示为失败
                    last = ScanProgress.Cancelled
                    setProgressIfCurrent(self, ScanProgress.Cancelled)
                    throw ce
                } catch (t: Throwable) {
                    Timber.e(t, "Scan($mode) failed")
                    val failed = ScanProgress.Failed(t.message ?: "未知错误", t.javaClass.simpleName)
                    last = failed
                    setProgressIfCurrent(self, failed)
                } finally {
                    synchronized(stateLock) {
                        if (currentJob === self) {
                            currentJob = null
                            _running.value = false
                        }
                    }
                    analytics?.scanned(mode, automatic, last, elapsedMs(started))
                    if (last is ScanProgress.Done) analytics?.refreshLibrarySize()
                }
            }
            currentJob = job
            _running.value = true
            _automatic.value = automatic
            _progress.value = null
            job.start()
            return job
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

    /** 统计：扫描的方式、结果（失败记原因代码）、耗时与新增或更新的张数 */
    private fun Analytics.scanned(mode: ScanMode, automatic: Boolean, last: ScanProgress?, durationMs: Long) {
        val (result, reason) = when (last) {
            is ScanProgress.Done -> "ok" to null
            is ScanProgress.Failed -> "failed" to last.code
            ScanProgress.Cancelled -> "cancelled" to null
            else -> "failed" to "unfinished"
        }
        val key = when (mode) {
            ScanMode.INCREMENTAL -> "incremental"
            ScanMode.FULL -> "full"
            ScanMode.DEEP_VERIFY -> "verify"
        }
        scanFinished(key, automatic, result, durationMs, (last as? ScanProgress.Done)?.total, reason)
    }

    /** 仅当 [self] 仍是当前任务时写进度，旧 job 迟到的进度/终态不覆盖新扫描 */
    private fun setProgressIfCurrent(self: Job?, value: ScanProgress) {
        synchronized(stateLock) {
            if (currentJob === self) _progress.value = value
        }
    }
}
