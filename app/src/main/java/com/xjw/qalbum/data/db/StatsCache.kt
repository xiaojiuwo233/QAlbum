package com.xjw.qalbum.data.db

import androidx.room.InvalidationTracker
import com.xjw.qalbum.data.paging.BulkWrites
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 统计查询的缓存：清理页、规则页的张数 / 大小 / 分组计数都是整表聚合，几十万张图上一次要几秒。
 *
 * - 结果按查询（[observe] 的 key，一般是 SQL + 参数）缓存在内存里，记下算它时的数据版本；数据版本由 Room 的失效
 *   通知推进（images 与聊天来源的几张表有写入）。页面订阅时先给缓存值（哪怕旧了），数据没变就不再查——切换页面、
 *   进出规则页都不重算，只在图片变化后才重算。
 * - 同一时间只跑一个统计查询，只算有页面在看的；写入集中的时候（批量删除、扫描）等安静 [QUIET_MS] 再算。
 * - [hold] 期间（清理执行中）已有的结果先不重算，正在算的取消（Room 会中断 SQLite 查询）：数据库线程留给清理，删除不会排在
 *   一串统计查询后面；结束后补算一次。还没算过的照算，期间打开的页面不至于一直「统计中」。期间各页面的网格与观察流也放慢刷新
 *   （见 [BulkWrites]）。
 */
class StatsCache(db: AppDatabase, private val scope: CoroutineScope) {

    private class Slot(val key: String, val query: suspend () -> Any) {
        val value = MutableStateFlow<Any?>(null)
        @Volatile var version = -1L
        val watchers = AtomicInteger(0)
    }

    private val slots = ConcurrentHashMap<String, Slot>()
    private val version = MutableStateFlow(0L)
    private val holds = MutableStateFlow(0)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var quiet: Job? = null

    init {
        db.invalidationTracker.addObserver(object : InvalidationTracker.Observer(TABLES) {
            override fun onInvalidated(tables: Set<String>) {
                version.update { it + 1 }
                // 等写入停下来再算：每次写入都重新计时
                quiet?.cancel()
                quiet = scope.launch {
                    delay(QUIET_MS)
                    wake.trySend(Unit)
                }
            }
        })
        scope.launch {
            for (signal in wake) refreshStale()
        }
    }

    /**
     * [key] 对应查询的结果：先给缓存值，数据变过（或从没算过）就在后台算一遍再给。[query] 用一次性的 suspend 查询，
     * 不要用 Room 的 Flow（那样每次写入都会各自重查）。
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> observe(key: String, query: suspend () -> T): Flow<T> {
        val slot = slots.getOrPut(key) { Slot(key, query) }
        return slot.value
            .onStart {
                slot.watchers.incrementAndGet()
                if (slot.version != version.value) wake.trySend(Unit)
            }
            .onCompletion { slot.watchers.decrementAndGet() }
            .filter { it != null }
            .map { it as T }
    }

    /** [key] 有没有算过的结果（可能已过期） */
    fun has(key: String): Boolean = slots[key]?.value?.value != null

    /** 执行 [block] 期间暂停统计（可嵌套）；结束后补算 */
    suspend fun <T> hold(block: suspend () -> T): T {
        holds.update { it + 1 }
        try {
            return BulkWrites.hold(block)
        } finally {
            holds.update { it - 1 }
            wake.trySend(Unit)
        }
    }

    /** 逐个算有页面在看、又过期了的结果；每个最多算一次（一直有写入时等下一次安静） */
    private suspend fun refreshStale() {
        val pending = slots.values.filter { it.watchers.get() > 0 && it.version != version.value }
        for (slot in pending) {
            val target = version.value
            if (slot.version == target) continue
            // 暂停期间只算还没有结果的；已有结果的（哪怕旧了）等暂停结束再算
            val missing = slot.value.value == null
            if (holds.value > 0 && !missing) continue
            val started = System.currentTimeMillis()
            val result = try {
                if (missing) slot.query() else cancellableByHold { slot.query() } ?: return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "统计查询失败")
                continue
            }
            slot.version = target
            slot.value.value = result
            Timber.i("Stats refreshed in %d ms: %s", System.currentTimeMillis() - started, slot.key.take(80))
        }
    }

    /** 执行 [block]，期间一旦有人 [hold] 就取消它并返回 null */
    private suspend fun <T> cancellableByHold(block: suspend () -> T): T? = coroutineScope {
        val work = async { block() }
        val watcher = launch {
            holds.first { it > 0 }
            work.cancel()
        }
        try {
            work.await()
        } catch (e: CancellationException) {
            if (!currentCoroutineContext().isActive) throw e
            null
        } finally {
            watcher.cancel()
        }
    }

    companion object {
        /** 有缓存时在 [StatsCache.hold] 里执行（测试等没有缓存的地方直接执行） */
        suspend fun <T> StatsCache?.holding(block: suspend () -> T): T = if (this == null) block() else hold(block)

        /** 统计依赖的表：图片，以及按聊天的规则用到的来源表 */
        private val TABLES = arrayOf("images", "image_qq_origins", "qq_origins", "qq_source_cache", "qq_preview_checks")

        /** 写入停下来多久后再算 */
        private const val QUIET_MS = 800L
    }
}
