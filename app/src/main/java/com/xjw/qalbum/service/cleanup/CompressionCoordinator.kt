package com.xjw.qalbum.service.cleanup

import com.xjw.qalbum.analytics.Analytics
import com.xjw.qalbum.analytics.compressDiscarded
import com.xjw.qalbum.analytics.compressPreview
import com.xjw.qalbum.analytics.compressed
import com.xjw.qalbum.analytics.elapsedMs
import com.xjw.qalbum.analytics.originalsRestored
import com.xjw.qalbum.data.db.AppDatabase
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.service.cleanup.OriginalCompressor.Prepared
import com.xjw.qalbum.service.cleanup.OriginalCompressor.Preview
import com.xjw.qalbum.service.cleanup.rules.CleanupRunner
import com.xjw.qalbum.service.demo.DemoMode
import com.xjw.qalbum.service.work.BackgroundTasks
import com.xjw.qalbum.ui.common.formatBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 压缩的统一入口：图库、看图页、清理规则、聊天来源都走这里。
 *
 * - 设置里开着「压缩后先对比」（默认）：先把选中的图压到缓存（超过 [FULL_LIMIT] 张时只压 [SAMPLE_SIZE] 张样张），
 *   界面根部的对比页显示前后效果和省下的空间，可以换个质量重压；点「应用」才替换原图（样张之外的按同样设置在后台压缩），
 *   「撤销」丢掉结果、原图不动。同时只有一组对比。
 * - 关闭对比：直接交给 [CleanupRunner] 在后台压缩。
 */
class CompressionCoordinator(
    private val db: AppDatabase,
    private val compressor: OriginalCompressor,
    private val runner: CleanupRunner,
    private val settings: SettingsRepository,
    private val tasks: BackgroundTasks,
    private val appScope: CoroutineScope,
    /** 演示模式下不压缩、不还原 */
    private val demo: DemoMode? = null,
    private val analytics: Analytics? = null,
) {
    /** 对比页上的一张：压缩结果（或不能压缩的原因）与是否要应用 */
    data class Entry(val preview: Preview, val checked: Boolean) {
        val prepared: Prepared? get() = (preview as? Preview.Ready)?.prepared
    }

    sealed interface Session {
        val quality: Int

        /** 选中的图里能压缩的张数与总大小 */
        val eligible: Int
        val eligibleBytes: Long

        /** 选中的图里不能压缩的张数（动图、PNG、缩略图、已压缩过的） */
        val skipped: Int

        data class Preparing(
            override val quality: Int, override val eligible: Int, override val eligibleBytes: Long, override val skipped: Int,
            val done: Int, val total: Int,
        ) : Session

        /** [remaining]：只压了样张时，应用后要在后台压缩的其余图片 */
        data class Review(
            override val quality: Int, override val eligible: Int, override val eligibleBytes: Long, override val skipped: Int,
            val entries: List<Entry>, val remaining: List<Long>, val maxSide: Int,
        ) : Session {
            val sampled: Boolean get() = remaining.isNotEmpty()
            val chosen: List<Prepared> get() = entries.filter { it.checked }.mapNotNull { it.prepared }
        }
    }

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** 结果提示（界面根部用 Toast 显示） */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** 一组对比要压的图：样张、样张以外的其余图片与列表里显示的不能压缩的图 */
    private class Plan(
        val targets: List<ImageEntity>,
        val remaining: List<Long>,
        val unavailable: List<Preview.Unavailable>,
        val eligible: Int,
        val eligibleBytes: Long,
        val skipped: Int,
        val maxSide: Int,
    )

    private val lock = Any()
    private var plan: Plan? = null
    private var job: Job? = null

    /** 每次重新压缩（换质量）或结束对比都加一：过期的压缩结果不再显示、直接删掉 */
    private var generation = 0

    /** 压缩这些图（只处理其中能压缩的） */
    fun start(ids: Collection<Long>) {
        if (ids.isEmpty() || demo?.blocks() == true) return
        appScope.launch {
            if (_session.value != null) {
                _messages.tryEmit("先处理完上一组压缩")
                return@launch
            }
            val images = load(ids)
            val unavailable = images.mapNotNull { image -> compressor.unsupportedReason(image)?.let { Preview.Unavailable(image, it) } }
            val eligible = images.filter { compressor.unsupportedReason(it) == null }
            if (eligible.isEmpty()) {
                _messages.tryEmit(
                    unavailable.singleOrNull()?.let { "不能压缩：${it.reason}" }
                        ?: "选中的图都不能压缩（只支持没压缩过的 JPEG、PNG 原图）",
                )
                return@launch
            }
            val quality = settings.compressQuality.first()
            val maxSide = settings.compressMaxSide.first()
            if (!settings.compressCompare.first()) {
                runner.compress(eligible.map { it.id }, quality, maxSide, from = "direct")
                _messages.tryEmit(if (eligible.size == 1) "正在压缩…" else "正在后台压缩 ${eligible.size} 张")
                return@launch
            }
            compressor.accessError()?.let { error ->
                _messages.tryEmit(error)
                return@launch
            }
            val targets = if (eligible.size <= FULL_LIMIT) eligible else sample(eligible)
            val chosen = targets.map { it.id }.toSet()
            synchronized(lock) {
                plan = Plan(
                    targets = targets,
                    remaining = eligible.map { it.id }.filterNot { it in chosen },
                    // 张数不多时把不能压缩的也列出来，说明原因
                    unavailable = if (images.size <= FULL_LIMIT) unavailable else emptyList(),
                    eligible = eligible.size,
                    eligibleBytes = eligible.sumOf { it.size },
                    skipped = unavailable.size,
                    maxSide = maxSide,
                )
            }
            prepare(quality)
        }
    }

    /** 换个质量重新压缩 */
    fun setQuality(quality: Int) {
        val current = _session.value ?: return
        if (current.quality == quality) return
        (current as? Session.Review)?.let { review ->
            synchronized(lock) { generation++ }
            compressor.discard(review.entries.mapNotNull { it.prepared })
        }
        prepare(quality)
    }

    /** 对比页上勾选 / 取消一张 */
    fun toggle(imageId: Long) {
        synchronized(lock) {
            val review = _session.value as? Session.Review ?: return
            _session.value = review.copy(
                entries = review.entries.map { if (it.preview.image.id == imageId && it.prepared != null) it.copy(checked = !it.checked) else it },
            )
        }
    }

    /** 应用：勾选的替换原图，样张之外的在后台压缩 */
    fun apply() {
        if (demo?.blocks() == true) return
        val review = synchronized(lock) {
            val current = _session.value as? Session.Review ?: return
            generation++
            plan = null
            _session.value = null
            current
        }
        val chosen = review.chosen
        compressor.discard(review.entries.mapNotNull { it.prepared }.filterNot { it in chosen })
        appScope.launch {
            val started = System.nanoTime()
            val outcome = if (chosen.isEmpty()) {
                OriginalCompressor.Outcome()
            } else {
                tasks.track("应用压缩", cancellable = false) { handle ->
                    compressor.apply(chosen) { n -> handle.update("已替换 $n / ${chosen.size} 张", n.toLong(), chosen.size.toLong()) }
                }
            }
            if (chosen.isNotEmpty()) analytics?.compressed(
                "compare", chosen.size, outcome.compressed, outcome.failed, outcome.skipped, outcome.savedBytes, elapsedMs(started),
                review.quality, review.maxSide, settings.compressKeepOriginal.first(),
            )
            if (review.remaining.isNotEmpty()) runner.compress(review.remaining, review.quality, review.maxSide, from = "rest")
            _messages.tryEmit(applyText(outcome, chosen.size, review.remaining.size))
        }
    }

    /** 撤销：丢掉压缩结果，原图不动 */
    fun dismiss() {
        val old = synchronized(lock) {
            generation++
            plan = null
            _session.value.also { _session.value = null }
        }
        job?.cancel()
        (old as? Session.Review)?.let { review -> compressor.discard(review.entries.mapNotNull { it.prepared }) }
        old?.let { analytics?.compressDiscarded(it.eligible, it.quality) }
    }

    /** 详情里的「还原原图」 */
    fun restoreOriginal(imageId: Long) {
        if (demo?.blocks() == true) return
        appScope.launch {
            val restored = compressor.restore(imageId)
            analytics?.originalsRestored("detail", 1, if (restored) 1 else 0)
            _messages.tryEmit(if (restored) "已还原原图" else "还原失败：文件已变化或无法访问")
        }
    }

    private fun prepare(quality: Int) {
        val (current, gen) = synchronized(lock) {
            val current = plan ?: return
            generation++
            _session.value = Session.Preparing(quality, current.eligible, current.eligibleBytes, current.skipped, 0, current.targets.size)
            current to generation
        }
        job?.cancel()
        job = appScope.launch {
            val started = System.nanoTime()
            val previews = tasks.track("压缩对比", cancellable = false) {
                compressor.prepare(current.targets, quality, current.maxSide) { done ->
                    publish(gen, Session.Preparing(quality, current.eligible, current.eligibleBytes, current.skipped, done, current.targets.size))
                }
            }
            val entries = previews.map { Entry(it, checked = it is Preview.Ready) } + current.unavailable.map { Entry(it, checked = false) }
            val review = Session.Review(quality, current.eligible, current.eligibleBytes, current.skipped, entries, current.remaining, current.maxSide)
            // 期间换了质量或撤销：这批结果作废
            if (!publish(gen, review)) {
                compressor.discard(entries.mapNotNull { it.prepared })
                return@launch
            }
            val ready = previews.mapNotNull { (it as? Preview.Ready)?.prepared }
            analytics?.compressPreview(
                current.targets.size, ready.size, current.eligible, quality, current.maxSide, elapsedMs(started),
                ready.sumOf { it.image.size }, ready.sumOf { it.savedBytes },
            )
        }
    }

    private fun publish(gen: Int, session: Session): Boolean = synchronized(lock) {
        if (gen != generation) return false
        _session.value = session
        true
    }

    /** 按选择的顺序取图片（分批查，避免超出 SQL 参数上限） */
    private suspend fun load(ids: Collection<Long>): List<ImageEntity> {
        val dao = db.imageDao()
        val byId = ids.distinct().chunked(500).flatMap { dao.getAnyByIds(it) }.associateBy { it.id }
        return ids.distinct().mapNotNull { byId[it] }
    }

    /** 大批量时在选择里均匀取样张 */
    private fun sample(images: List<ImageEntity>): List<ImageEntity> =
        List(SAMPLE_SIZE) { index -> images[index * images.size / SAMPLE_SIZE] }.distinctBy { it.id }

    private fun applyText(outcome: OriginalCompressor.Outcome, chosen: Int, remaining: Int): String {
        val parts = ArrayList<String>()
        if (outcome.compressed > 0) parts += "已压缩 ${outcome.compressed} 张，省下 ${formatBytes(outcome.savedBytes)}"
        val missed = chosen - outcome.compressed
        if (missed > 0) parts += "$missed 张未替换（文件已变化或无法访问）"
        if (remaining > 0) parts += "其余 $remaining 张在后台压缩"
        return parts.joinToString("；").ifEmpty { "没有替换任何图片" }
    }

    companion object {
        /** 不超过这么多张时全部压出来对比 */
        const val FULL_LIMIT = 20

        /** 超过时只压这么多张样张 */
        const val SAMPLE_SIZE = 6
    }
}
