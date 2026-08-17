package com.xjw.qalbum.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xjw.qalbum.data.db.dao.PendingAnalysisRow
import com.xjw.qalbum.data.db.entity.ImageEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImageDaoPaginationTest {

    @Test
    fun failedFirstPageStillAdvancesAllAnalysisQueries() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java,
        ).build()
        try {
            val dao = db.imageDao()
            val ids = (0 until 6).map { index ->
                dao.insert(image(index, mtime = if (index < 3) 1_000 else 2_000))
            }

            suspend fun assertAdvances(
                fetch: suspend (limit: Int, afterMtime: Long, afterId: Long) -> List<PendingAnalysisRow>,
            ) {
                val first = fetch(2, Long.MIN_VALUE, Long.MIN_VALUE)
                assertEquals(ids.take(2), first.map { it.id })

                // Do not mark either first-page row complete: both simulate persistent failures.
                val second = fetch(2, first.last().mtime, first.last().id)
                assertEquals(ids.drop(2).take(2), second.map { it.id })
            }

            assertAdvances { limit, mtime, id -> dao.pendingHashPage(limit, true, mtime, id) }
            assertAdvances { limit, mtime, id -> dao.pendingOcrPage(limit, true, mtime, id) }
            assertAdvances { limit, mtime, id -> dao.pendingAiTagPage(limit, true, mtime, id) }
            assertAdvances { limit, mtime, id -> dao.pendingEmbeddingPage(limit, true, mtime, id) }

            val newest = dao.pendingHashPage(2, false, Long.MAX_VALUE, Long.MAX_VALUE)
            assertEquals(ids.takeLast(2).reversed(), newest.map { it.id })
            val older = dao.pendingHashPage(2, false, newest.last().mtime, newest.last().id)
            assertEquals(listOf(ids[3], ids[2]), older.map { it.id })
        } finally {
            db.close()
        }
    }

    private fun image(index: Int, mtime: Long): ImageEntity = ImageEntity(
        path = "/chatpic/Cache_$index",
        filename = "Cache_$index",
        parentDir = "/chatpic",
        sourceType = "chatimg",
        cacheKey = "key-$index",
        size = 100,
        mtime = mtime,
        firstSeen = mtime,
        lastSeen = mtime,
        mime = "image/jpeg",
        width = 100,
        height = 100,
        hashDone = true,
    )
}
