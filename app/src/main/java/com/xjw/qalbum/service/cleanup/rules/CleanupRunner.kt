package com.xjw.qalbum.service.cleanup.rules

import com.xjw.qalbum.analytics.Analytics
import com.xjw.qalbum.analytics.compressed
import com.xjw.qalbum.analytics.deleted
import com.xjw.qalbum.analytics.elapsedMs
import com.xjw.qalbum.data.db.StatsCache
import com.xjw.qalbum.data.db.StatsCache.Companion.holding
import com.xjw.qalbum.service.cleanup.OriginalCompressor
import com.xjw.qalbum.service.cleanup.PictureDeleteOptions
import com.xjw.qalbum.service.cleanup.QuarantineManager
import com.xjw.qalbum.service.work.BackgroundTasks
import com.xjw.qalbum.ui.common.formatBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * 批量处理用户选中的图片：移入回收站或直接压缩，每批 [PAGE] 张。跑在 App 级作用域并登记为后台任务
 * （通知栏显示进度，离开页面、切到后台都不中断）；一次跑一个，后来的排队。
 */
class CleanupRunner(
    private val quarantine: QuarantineManager,
    private val compressor: OriginalCompressor,
    private val tasks: BackgroundTasks,
    private val appScope: CoroutineScope,
    /** 执行期间暂停清理页的统计重算，数据库先给清理用 */
    private val statsCache: StatsCache? = null,
    private val analytics: Analytics? = null,
) {
    /** 正在跑的任务；[ruleId] 是从哪条规则的预览页发起的（别处发起为 null） */
    data class Running(val ruleId: String?, val title: String, val action: RuleAction, val done: Int, val total: Int)

    private val _running = MutableStateFlow<Running?>(null)
    val running: StateFlow<Running?> = _running.asStateFlow()

    private val _results = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** 执行结果文案（页面用 Toast 展示） */
    val results: SharedFlow<String> = _results.asSharedFlow()

    private val queue = Mutex()

    @Volatile
    private var current: Job? = null

    /** 移入回收站；[linkedVersions] 为真时同一张图的其他版本一起（整张图），否则只处理选中的这个文件 */
    fun trash(ids: Collection<Long>, title: String, linkedVersions: Boolean, ruleId: String? = null) {
        val options = PictureDeleteOptions(linkedVersions = linkedVersions, includeShared = true)
        enqueue(ruleId, "清理：$title", RuleAction.TRASH, ids.toList(), { totals, result, ms ->
            analytics?.deleted("rule", false, ids.size, totals.done, totals.skipped, totals.failed, totals.bytes, ms, ruleId, result)
        }) { page, _ -> trashStep(page, options, title) }
    }

    /** 直接压缩并替换（不对比）；[from] direct（关了压缩对比）/ rest（对比页应用后其余的），统计用 */
    fun compress(ids: Collection<Long>, quality: Int, maxSide: Int, ruleId: String? = null, from: String = "direct") {
        enqueue(ruleId, "压缩原图", RuleAction.COMPRESS, ids.toList(), { totals, result, ms ->
            analytics?.compressed(from, ids.size, totals.done, totals.failed, totals.skipped, totals.saved, ms, quality, maxSide, null, result)
        }) { page, progress ->
            val outcome = compressor.compress(page, quality, maxSide, progress)
            Step(done = outcome.compressed, saved = outcome.savedBytes, skipped = outcome.skipped, failed = outcome.failed)
        }
    }

    private suspend fun trashStep(page: List<Long>, options: PictureDeleteOptions, title: String): Step {
        val result = quarantine.quarantinePictures(page, options, title)
        return Step(done = result.selectedProcessed, skipped = result.skipped, failed = result.partial, bytes = result.bytes)
    }

    /**
     * 定时清理用：排在前面的任务之后，把 [ids] 移入回收站，做完才返回移入的张数（结果不弹提示，由调用方汇总）。
     * 没有文件访问时返回 0。
     */
    suspend fun trashNow(ids: List<Long>, title: String, linkedVersions: Boolean): Int {
        if (ids.isEmpty()) return 0
        val options = PictureDeleteOptions(linkedVersions = linkedVersions, includeShared = true)
        return queue.withLock {
            current = currentCoroutineContext()[Job]
            try {
                run(null, "定时清理：$title", RuleAction.TRASH, ids, announce = false) { page, _ -> trashStep(page, options, title) }
            } finally {
                current = null
            }
        }
    }

    /** 停止正在跑的任务（排队的照常进行） */
    fun cancel() {
        current?.cancel()
    }

    /** 一批的结果：[done] 处理了的张数，[saved] 压缩省下的，[bytes] 移入回收站的文件大小 */
    private class Step(val done: Int, val saved: Long = 0, val skipped: Int = 0, val failed: Int = 0, val bytes: Long = 0)

    /** 整个任务累计（统计用） */
    private class Totals {
        var done = 0
        var saved = 0L
        var skipped = 0
        var failed = 0
        var bytes = 0L

        fun add(step: Step) {
            done += step.done
            saved += step.saved
            skipped += step.skipped
            failed += step.failed
            bytes += step.bytes
        }
    }

    private fun enqueue(
        ruleId: String?,
        title: String,
        action: RuleAction,
        ids: List<Long>,
        /** 结束时（结果 ok / cancelled / no_access / failed 与耗时） */
        finished: (Totals, String, Long) -> Unit,
        process: suspend (page: List<Long>, progress: (Int) -> Unit) -> Step,
    ) {
        if (ids.isEmpty()) return
        val queuedAt = System.currentTimeMillis()
        appScope.launch {
            queue.withLock {
                val waited = System.currentTimeMillis() - queuedAt
                if (waited > 1_000) Timber.i("CleanupRunner: %s waited %d ms for the previous task", title, waited)
                current = currentCoroutineContext()[Job]
                try {
                    run(ruleId, title, action, ids, finished = finished, process = process)
                } finally {
                    current = null
                }
            }
        }
    }

    /** 执行一个任务，返回成功处理的张数；[announce] 为假时不发结果文案 */
    private suspend fun run(
        ruleId: String?,
        title: String,
        action: RuleAction,
        ids: List<Long>,
        announce: Boolean = true,
        finished: ((Totals, String, Long) -> Unit)? = null,
        process: suspend (page: List<Long>, progress: (Int) -> Unit) -> Step,
    ): Int = statsCache.holding { tasks.track(title) { handle ->
        val total = ids.size
        var processed = 0
        var done = 0
        var saved = 0L
        val totals = Totals()
        val started = System.nanoTime()
        var result = "ok"
        fun report(position: Int) {
            _running.value = Running(ruleId, title, action, position, total)
            handle.update(detail = progressText(action, position, total), done = position.toLong(), total = total.toLong())
        }
        report(0)
        try {
            for (page in ids.chunked(PAGE)) {
                currentCoroutineContext().ensureActive()
                // 没有文件访问（未授权、Shizuku 中途断开）：停下说明原因，不把整批记成「未处理」
                quarantine.fileAccessError()?.let { error ->
                    if (announce) _results.tryEmit(if (processed == 0) error else resultText(action, done, processed, saved, stopped = true) + "；$error")
                    result = "no_access"
                    return@track done
                }
                val base = processed
                val step = process(page) { n -> report(base + n) }
                done += step.done
                saved += step.saved
                totals.add(step)
                processed = base + page.size
                report(processed)
            }
            if (announce) _results.tryEmit(resultText(action, done, processed, saved, stopped = false))
        } catch (e: CancellationException) {
            result = "cancelled"
            if (announce) _results.tryEmit(resultText(action, done, processed, saved, stopped = true))
            throw e
        } catch (e: Exception) {
            result = "failed"
            Timber.e(e, "CleanupRunner: $title failed")
            if (announce) _results.tryEmit("${title}未完成：${e.message ?: e.javaClass.simpleName}")
        } finally {
            _running.value = null
            finished?.invoke(totals, result, elapsedMs(started))
        }
        done
    } }

    private fun progressText(action: RuleAction, done: Int, total: Int): String {
        val verb = if (action == RuleAction.COMPRESS) "已压缩" else "已处理"
        return "$verb $done / $total 张"
    }

    private fun resultText(action: RuleAction, done: Int, processed: Int, saved: Long, stopped: Boolean): String {
        val prefix = if (stopped) "已停止。" else ""
        return when (action) {
            RuleAction.TRASH ->
                prefix + "已移入回收站 $done 张" + if (done < processed) "；${processed - done} 张因归档或文件变化未处理" else ""
            RuleAction.COMPRESS ->
                prefix + "已压缩 $done 张，省下 ${formatBytes(saved)}" + if (done < processed) "；${processed - done} 张不划算或未能处理" else ""
        }
    }

    private companion object {
        const val PAGE = 200
    }
}
