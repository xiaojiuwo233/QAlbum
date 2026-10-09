package com.xjw.qalbum.data.paging

import androidx.paging.PagingSource
import androidx.paging.PagingState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

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
     * 之后每代（数据变化失效后的刷新）在闸门关着时等待。
     */
    fun <T : Any> gated(create: () -> PagingSource<Int, T>): () -> PagingSource<Int, T> {
        var first = true
        return {
            GatedPagingSource(if (first) null else this, create).also { first = false }
        }
    }
}

/**
 * 包一层 Room 的分页源：闸门关着时，失效后的新一代先不加载——Paging 在新一代加载完成前一直显示旧数据，
 * 网格保持原样；闸门打开后再按当时的数据加载。新一代在开始加载前不创建 Room 分页源，期间的数据库变化
 * 也不会再触发失效。刷新位置与 Room 的 LimitOffsetPagingSource 相同（以锚点为中心）。
 */
internal class GatedPagingSource<T : Any>(
    private val gate: RefreshGate?,
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
        return source().load(params)
    }

    private fun source(): PagingSource<Int, T> = synchronized(this) {
        delegate ?: create().also { created ->
            delegate = created
            created.registerInvalidatedCallback { invalidate() }
            if (invalid) created.invalidate()
        }
    }
}
