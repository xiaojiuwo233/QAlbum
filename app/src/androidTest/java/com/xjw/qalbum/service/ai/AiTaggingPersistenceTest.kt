package com.xjw.qalbum.service.ai

import android.content.Context
import android.graphics.Bitmap
import androidx.room.Room
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xjw.qalbum.access.ImageBytesReader
import com.xjw.qalbum.data.db.AppDatabase
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.data.db.entity.ImageTagEntity
import com.xjw.qalbum.data.db.entity.TagEntity
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.data.remote.OpenAiCompatClient
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AiTaggingPersistenceTest {
    @Test
    fun successfulResponseStoresDescriptionAndPreservesManualTagSource() = runBlocking {
        withService(rewriteDuringRequest = false) { db, result ->
            assertTrue(result is AiTaggingService.Result.Success)
            assertEquals("a receipt", db.aiAnalysisDao().getByImageId(1)?.description)
            val image = db.imageDao().getById(1)!!
            assertTrue(image.aiDone)
            assertFalse(image.embeddingDone)
            assertTrue(image.isFavorite)
            assertEquals(true, image.userKeep)
            assertNull(db.embeddingDao().getByImageId(1))
            val cursor = db.openHelper.readableDatabase.query("SELECT source FROM image_tags WHERE image_id = 1 AND tag_id = 1")
            cursor.use { assertTrue(it.moveToFirst()); assertEquals("user", it.getString(0)) }
        }
    }

    @Test
    fun rewrittenImageRefusesAllPartsOfStaleAiResult() = runBlocking {
        withService(rewriteDuringRequest = true) { db, result ->
            assertTrue(result is AiTaggingService.Result.Failed)
            assertNull(db.aiAnalysisDao().getByImageId(1))
            assertFalse(db.imageDao().getById(1)!!.aiDone)
            assertEquals(listOf("receipt"), db.tagDao().getTagsForImage(1).map { it.name })
        }
    }

    private suspend fun withService(rewriteDuringRequest: Boolean, check: suspend (AppDatabase, AiTaggingService.Result) -> Unit) {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val files = File(base.cacheDir, "ai-test-${UUID.randomUUID()}").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = PreferenceDataStoreFactory.create(scope = scope) { File(files, "test.preferences_pb") }
        val settings = SettingsRepository(base, scope, store)
        val db = Room.inMemoryDatabaseBuilder(base, AppDatabase::class.java).build()
        try {
            settings.setAiEndpoint("http://127.0.0.1:12345/v1")
            settings.setAiEnabled(true)
            db.imageDao().insert(ImageEntity(
                id = 1, path = "/fixture.png", filename = "fixture", parentDir = "/", sourceType = "chatimg",
                cacheKey = "fixture", size = 100, mtime = 1000, firstSeen = 1000, lastSeen = 1000,
                mime = "image/png", width = 100, height = 100, hashDone = true, embeddingDone = true,
                isFavorite = true, userKeep = true,
            ))
            db.tagDao().insertTag(TagEntity(id = 1, name = "receipt", type = "user"))
            db.tagDao().linkTag(ImageTagEntity(1, 1, source = "user"))
            SemanticIndex(db.embeddingDao()).upsert(1, "old", floatArrayOf(1f))
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                if (rewriteDuringRequest) runBlocking {
                    db.imageDao().updateFromScanByPath("/fixture.png", "fixture", "/", "chatimg", "fixture", 200, 2000, 2000, "image/png", false)
                }
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body("""{"choices":[{"message":{"content":"{\"content_tags\":[\"receipt\"],\"risk_tags\":[\"amount\"],\"description\":\"a receipt\",\"info_type\":\"document\"}"}}]}""".toResponseBody()).build()
            }.build()
            val bitmap = Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888)
            val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            bitmap.recycle()
            val service = AiTaggingService(OpenAiCompatClient(settings, http), db, db.imageDao(), db.tagDao(), settings)
            check(db, service.tagImage(ImageBytesReader { _, _ -> bytes }, 1))
        } finally {
            db.close()
            scope.cancel()
        }
    }
}
