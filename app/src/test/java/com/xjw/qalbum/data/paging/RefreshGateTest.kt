package com.xjw.qalbum.data.paging

import androidx.paging.PagingSource
import androidx.paging.PagingState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 批量删除期间网格不逐批刷新：失效后的新一代等闸门打开才加载 */
class RefreshGateTest {
    private class FakeSource : PagingSource<Int, Int>() {
        var loads = 0
        override fun getRefreshKey(state: PagingState<Int, Int>): Int? = null
        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Int> {
            loads++
            return LoadResult.Page(listOf(1, 2, 3), prevKey = null, nextKey = null)
        }
    }

    private val refresh = PagingSource.LoadParams.Refresh<Int>(key = null, loadSize = 3, placeholdersEnabled = true)

    @Test
    fun `关闸期间首代照常加载，之后的代等到放开`() = runBlocking {
        val gate = RefreshGate()
        val created = mutableListOf<FakeSource>()
        val factory = gate.gated { FakeSource().also { created += it } }
        val first = factory()
        val second = factory()
        val release = CompletableDeferred<Unit>()
        val holding = launch { gate.hold { release.await() } }
        yield()

        assertTrue(first.load(refresh) is PagingSource.LoadResult.Page)
        val pending = async { second.load(refresh) }
        yield()
        assertFalse(pending.isCompleted)
        // 等待期间还没建 Room 分页源，数据库变化不会再触发失效
        assertEquals(1, created.size)

        release.complete(Unit)
        assertTrue(pending.await() is PagingSource.LoadResult.Page)
        assertEquals(listOf(1, 1), created.map { it.loads })
        holding.join()
    }

    @Test
    fun `失效双向传递`() = runBlocking {
        val gate = RefreshGate()
        val created = mutableListOf<FakeSource>()
        val factory = gate.gated { FakeSource().also { created += it } }

        val first = factory()
        first.load(refresh)
        created.last().invalidate()
        assertTrue(first.invalid)

        val second = factory()
        second.load(refresh)
        second.invalidate()
        assertTrue(created.last().invalid)

        // 加载前已失效：建出来的 Room 分页源随即失效
        val third = factory()
        third.invalidate()
        third.load(refresh)
        assertTrue(created.last().invalid)
    }
}
