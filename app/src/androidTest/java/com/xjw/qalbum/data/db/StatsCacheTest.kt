package com.xjw.qalbum.data.db

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xjw.qalbum.TestDatabase
import com.xjw.qalbum.data.paging.BulkWrites
import com.xjw.qalbum.imageRow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** 统计缓存：再次订阅直接给缓存不重查；图片变了才重查；清理执行期间（hold）不查，结束后补上 */
@RunWith(AndroidJUnit4::class)
class StatsCacheTest {
    @get:Rule val database = TestDatabase("stats-cache-test.db")
    private val db get() = database.db
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queries = AtomicInteger(0)

    @After
    fun tearDown() = scope.cancel()

    private fun image(index: Int) = imageRow("/p/$index", "chatimg")

    private suspend fun count(): Int {
        queries.incrementAndGet()
        return db.query("SELECT COUNT(*) FROM images", null).use { it.moveToFirst(); it.getInt(0) }
    }

    @Test
    fun cachedResultsAreReusedUntilImagesChange() = runBlocking {
        val cache = StatsCache(db, scope)
        db.imageDao().insertAll(listOf(image(1)))
        withTimeout(5_000) { assertEquals(1, cache.observe("count") { count() }.first()) }
        assertEquals(1, queries.get())

        // 再次订阅（切换页面回来）：直接给缓存，不重查
        withTimeout(5_000) { assertEquals(1, cache.observe("count") { count() }.first()) }
        delay(300)
        assertEquals(1, queries.get())

        // 图片变了：有人在看就重查
        val values = CompletableDeferred<List<Int>>()
        val watcher = scope.launch { values.complete(cache.observe("count") { count() }.take(2).toList()) }
        delay(200)
        db.imageDao().insertAll(listOf(image(2)))
        assertEquals(listOf(1, 2), withTimeout(10_000) { values.await() })
        watcher.cancel()
    }

    @Test
    fun resultsNeverComputedArriveEvenWhileHeld() = runBlocking {
        val cache = StatsCache(db, scope)
        db.imageDao().insertAll(listOf(image(1)))
        val released = CompletableDeferred<Unit>()
        val holder = scope.launch { cache.hold { released.await() } }
        delay(100)
        assertTrue(BulkWrites.active)
        // 删除进行中才打开的页面：还没有结果的照算，不至于一直「统计中」
        withTimeout(5_000) { assertEquals(1, cache.observe("count-during-hold") { count() }.first()) }
        released.complete(Unit)
        holder.join()
        assertFalse(BulkWrites.active)
    }

    @Test
    fun nothingIsRecomputedWhileHeld() = runBlocking {
        val cache = StatsCache(db, scope)
        val values = mutableListOf<Int>()
        val watcher = scope.launch { cache.observe("count") { count() }.collect { synchronized(values) { values += it } } }
        withTimeout(5_000) { while (synchronized(values) { values.isEmpty() }) delay(50) }

        val released = CompletableDeferred<Unit>()
        val holder = scope.launch {
            cache.hold {
                db.imageDao().insertAll(listOf(image(1)))
                db.imageDao().insertAll(listOf(image(2)))
                delay(2_000)
                // 执行期间不重算
                assertEquals(listOf(0), synchronized(values) { values.toList() })
                released.complete(Unit)
            }
        }
        withTimeout(10_000) { released.await() }
        holder.join()
        withTimeout(10_000) { while (synchronized(values) { values.last() } != 2) delay(50) }
        assertTrue(synchronized(values) { values.toList() }.first() == 0)
        watcher.cancel()
    }
}
