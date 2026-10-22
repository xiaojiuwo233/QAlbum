package com.xjw.qalbum.data.paging

import androidx.paging.PagingSource
import androidx.paging.PagingState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 网格刷新闸门：批量删除等操作进行中关上，期间数据库的每次提交不再让网格逐批重排；
 * 操作结束打开后统一刷新一次。每个页面（ViewModel）一个，用 [hold] 包住操作，可重入。
 */
class RefreshGate {
    private val holds = MutableStateFlow(0)

    suspend fun awaitOpen() {
        holds.first { it == 0 }
    }

    suspend fun <T> hold(block: suspend () -> T): T {
        holds.update { it + 1 }
        try {
            return block()
        } finally {
            holds.update { it - 1 }
        }
    }

    /**
     * 包装 Pager 的分页源工厂（每个 Pager 调一次）：首代照常加载——新打开的列表、切换排序不必等删除做完；
     * 之后每代（数据变化失效后的刷新）在闸门关着时等待，有批量写入时也最多隔一会儿才刷新一次（见 [BulkWrites]）。
     */
    fun <T : Any> gated(create: () -> PagingSource<Int, T>): () -> PagingSource<Int, T> {
        var first = true
        val shown = PagerShown()
        return {
            GatedPagingSource(if (first) null else this, shown, create).also { first = false }
        }
    }
}

/** 同一个 Pager 是否已经显示过内容（还没显示过的不等批量写入） */
internal class PagerShown {
    @Volatile var value = false
}

/**
 * 进程里正在进行的批量写入（批量删除、恢复、清空回收站、读取聊天来源等，由 StatsCache.hold 登记）。
 * 期间每次提交都会让各页面的网格和统计跟着重查，几十万张图上一次就要几百毫秒，查询排起长队，看图、看来源都会卡住。
 * 所以期间各页面最多每 [REFRESH_MS] 刷新一次：网格先显示刚才的内容，统计先显示上一次的结果。
 */
object BulkWrites {
    private val holds = MutableStateFlow(0)

    /** 批量写入期间页面刷新的最短间隔 */
    const val REFRESH_MS = 3_000L

    val active: Boolean get() = holds.value > 0

    suspend fun <T> hold(block: suspend () -> T): T {
        holds.update { it + 1 }
        try {
            return block()
        } finally {
            holds.update { it - 1 }
        }
    }

    /** 有批量写入时等它结束，最多 [timeoutMs] */
    suspend fun awaitIdle(timeoutMs: Long = REFRESH_MS) {
        if (holds.value == 0) return
        withTimeoutOrNull(timeoutMs) { holds.first { it == 0 } }
    }
}

/**
 * Room 的观察流在批量写入期间放慢：发出一个结果后，有批量写入就先停一会儿（最多 [BulkWrites.REFRESH_MS]，写完马上接着查），
 * 期间的多次失效合并成一次重查（Room 的失效信号通道是合并的，下游不取值它就不会重查）。没有批量写入时照常。
 */
fun <T> Flow<T>.calmDuringBulkWrites(): Flow<T> = transform { value ->
    emit(value)
    BulkWrites.awaitIdle()
}

/**
 * 包一层 Room 的分页源：闸门关着时，失效后的新一代先不加载——Paging 在新一代加载完成前一直显示旧数据，
 * 网格保持原样；闸门打开后再按当时的数据加载。新一代在开始加载前不创建 Room 分页源，期间的数据库变化
 * 也不会再触发失效。刷新位置与 Room 的 LimitOffsetPagingSource 相同（以锚点为中心）。
 */
internal class GatedPagingSource<T : Any>(
    private val gate: RefreshGate?,
    private val shown: PagerShown,
    private val create: () -> PagingSource<Int, T>,
) : PagingSource<Int, T>() {
    private var delegate: PagingSource<Int, T>? = null

    init {
        registerInvalidatedCallback { synchronized(this) { delegate }?.invalidate() }
    }

    override val jumpingSupported: Boolean get() = true

    override fun getRefreshKey(state: PagingState<Int, T>): Int? =
        state.anchorPosition?.let { maxOf(0, it - state.config.initialLoadSize / 2) }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, T> {
        gate?.awaitOpen()
        // 批量写入期间的刷新：已经显示过内容的先显示着，等一会儿再查（滚动加载更多不等）
        if (params is LoadParams.Refresh && shown.value) BulkWrites.awaitIdle()
        return source().load(params).also { if (it is LoadResult.Page) shown.value = true }
    }

    private fun source(): PagingSource<Int, T> = synchronized(this) {
        delegate ?: create().also { created ->
            delegate = created
            created.registerInvalidatedCallback { invalidate() }
            if (invalid) created.invalidate()
        }
    }
}
