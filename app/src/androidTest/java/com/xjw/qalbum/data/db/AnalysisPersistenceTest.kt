package com.xjw.qalbum.data.db

import android.content.Context
import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xjw.qalbum.data.db.dao.OcrWrite
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.data.db.entity.OcrTextEntity
import com.xjw.qalbum.service.ai.SemanticIndex
import com.xjw.qalbum.service.analysis.QualityAnalyzer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnalysisPersistenceTest {
    @Test
    fun tinyImageQualityAnalysisDoesNotRecycleTheCallersBitmap() {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        try {
            assertTrue(QualityAnalyzer.analyzeBitmap(bitmap).isBlank)
            assertFalse(bitmap.isRecycled)
        } finally { bitmap.recycle() }
    }

    @Test
    fun ocrCommitInvalidatesVectorAndRefusesStaleOrInactiveResults() = runBlocking {
        val db = database()
        try {
            val dao = db.imageDao()
            val active = dao.insert(image(1).copy(embeddingDone = true, isArchived = true, userKeep = true))
            val changed = dao.insert(image(2).copy(size = 200))
            val inactive = dao.insert(image(3).copy(status = "quarantined"))
            val index = SemanticIndex(db.embeddingDao())
            index.upsert(active, "model", floatArrayOf(1f, 0f))
            db.ocrDao().saveOcrResultsBatch(listOf(active, changed, inactive).map {
                OcrWrite(it, 100, 1000, OcrTextEntity(it, "receipt 12.30", 0.9f, hasAmount = true, updatedAt = 1))
            })
            val saved = dao.getById(active)!!
            assertTrue(saved.ocrDone)
            assertFalse(saved.embeddingDone)
            assertTrue(saved.isArchived)
            assertEquals(true, saved.userKeep)
            assertNull(db.embeddingDao().getByImageId(active))
            assertNotNull(db.ocrDao().getByImageId(active))
            assertNull(db.ocrDao().getByImageId(changed))
            assertNull(db.ocrDao().getByImageId(inactive))
        } finally { db.close() }
    }

    @Test
    fun semanticSearchDoesNotMixModelsOrAllowInvisibleRowsToConsumeTopK() = runBlocking {
        val db = database()
        try {
            val dao = db.imageDao()
            val active = dao.insert(image(1).copy(embeddingDone = true))
            val otherModel = dao.insert(image(2).copy(embeddingDone = true))
            val inactive = dao.insert(image(3).copy(embeddingDone = true, status = "quarantined"))
            val pending = dao.insert(image(4))
            val index = SemanticIndex(db.embeddingDao())
            index.upsert(active, "A", floatArrayOf(0.8f, 0.2f))
            index.upsert(otherModel, "B", floatArrayOf(1f, 0f))
            index.upsert(inactive, "A", floatArrayOf(1f, 0f))
            index.upsert(pending, "A", floatArrayOf(1f, 0f))
            assertEquals(listOf(active), index.searchTopK(floatArrayOf(1f, 0f), 1, "A").map { it.first })
            assertEquals(listOf(otherModel), index.searchTopK(floatArrayOf(1f, 0f), 1, "B").map { it.first })
            assertTrue(index.searchTopK(floatArrayOf(Float.NaN, 1f), 10, "A").isEmpty())
        } finally { db.close() }
    }

    @Test
    fun changingEmbeddingModelSchedulesPreviouslyCompletedImages() = runBlocking {
        val db = database()
        try {
            val id = db.imageDao().insert(image(1).copy(embeddingDone = true))
            SemanticIndex(db.embeddingDao()).upsert(id, "old", floatArrayOf(1f))
            assertTrue(db.imageDao().pendingEmbeddingPage(10, true, Long.MIN_VALUE, Long.MIN_VALUE, "old").isEmpty())
            assertEquals(listOf(id), db.imageDao().pendingEmbeddingPage(10, true, Long.MIN_VALUE, Long.MIN_VALUE, "new").map { it.id })
        } finally { db.close() }
    }

    private fun database() = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java,
    ).build()

    private fun image(index: Int) = ImageEntity(
        path = "/chatpic/Cache_$index", filename = "Cache_$index", parentDir = "/chatpic",
        sourceType = "chatimg", cacheKey = "key-$index", size = 100, mtime = 1000,
        firstSeen = 1000, lastSeen = 1000, mime = "image/jpeg", width = 100, height = 100, hashDone = true,
    )
}
