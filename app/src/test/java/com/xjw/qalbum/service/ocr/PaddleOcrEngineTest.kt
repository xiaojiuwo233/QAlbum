package com.xjw.qalbum.service.ocr

import com.xjw.qalbum.access.FileAccessProvider
import com.xjw.qalbum.domain.model.AccessMode
import com.xjw.qalbum.domain.model.FileInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class PaddleOcrEngineTest {

    @Test
    fun returnsUnavailableWhenModelIsNotInstalled() = runBlocking {
        val engine = PaddleOcrEngine(
            modelStore = object : OcrModelStore {
                override fun currentStatus(): OcrModelStatus = OcrModelStatus.NotInstalled
                override fun modelPaths(): OcrModelPaths? = null
            },
            runner = FakeRunner(),
        )

        val result = engine.recognize(FakeProvider(), "/tmp/a.jpg")

        assertEquals(OcrResult.Unavailable, result)
    }

    @Test
    fun detectsSensitiveSignalsFromRunnerText() = runBlocking {
        val engine = PaddleOcrEngine(
            modelStore = object : OcrModelStore {
                override fun currentStatus(): OcrModelStatus = OcrModelStatus.Ready(
                    manifest = manifest(),
                    installedBytes = 10L,
                )

                override fun modelPaths(): OcrModelPaths = OcrModelPaths(
                    rootDir = "/models",
                    detDir = "/models/det",
                    recDir = "/models/rec",
                    clsDir = "/models/cls",
                    dictionary = "/models/ppocr_keys_v6.txt",
                )
            },
            runner = FakeRunner(
                PaddleOcrRunner.Result(
                    text = "订单号 123456789012345 手机 13800138000 金额 ¥12.30",
                    confidence = 0.92f,
                )
            ),
        )

        val result = engine.recognize(FakeProvider("jpeg".toByteArray()), "/tmp/a.jpg")

        assertTrue(result is OcrResult.Success)
        result as OcrResult.Success
        assertTrue(result.hasPhone)
        assertTrue(result.hasAmount)
        assertTrue(result.hasOrderNo)
        assertEquals(0.92f, result.confidence, 0.0001f)
    }

    @Test
    fun returnsUnavailableWhenNativeRunnerIsNotLinked() = runBlocking {
        val engine = PaddleOcrEngine(
            modelStore = readyStore(),
            runner = UnavailablePaddleOcrRunner(),
        )

        val result = engine.recognize(FakeProvider("jpeg".toByteArray()), "/tmp/a.jpg")

        assertEquals(OcrResult.Unavailable, result)
    }

    @Test(expected = CancellationException::class)
    fun cancellationIsNotReportedAsRecognitionFailure(): Unit = runBlocking {
        val engine = PaddleOcrEngine(readyStore(), object : PaddleOcrRunner {
            override suspend fun recognize(imageBytes: ByteArray, modelPaths: OcrModelPaths): PaddleOcrRunner.Result {
                throw CancellationException("cancelled")
            }
        })
        engine.recognize("image".toByteArray())
        Unit
    }

    private class FakeProvider(private val bytes: ByteArray = ByteArray(0)) : FileAccessProvider {
        override fun getAccessMode(): AccessMode = AccessMode.SHIZUKU
        override suspend fun isAvailable(): Boolean = true
        override suspend fun exists(path: String): Boolean = true
        override fun listFiles(dir: String): Flow<FileInfo> = emptyFlow()
        override suspend fun readFile(path: String): InputStream = ByteArrayInputStream(bytes)
        override suspend fun readHeader(path: String, n: Int): ByteArray? = bytes.take(n).toByteArray()
        override suspend fun getFileInfo(path: String): FileInfo? = null
        override suspend fun deleteFile(path: String): Boolean = true
        override suspend fun moveFile(src: String, dst: String): Boolean = true
    }

    private class FakeRunner(
        private val result: PaddleOcrRunner.Result = PaddleOcrRunner.Result("", 0f),
    ) : PaddleOcrRunner {
        override suspend fun recognize(imageBytes: ByteArray, modelPaths: OcrModelPaths): PaddleOcrRunner.Result = result
    }

    private fun readyStore() = object : OcrModelStore {
        override fun currentStatus(): OcrModelStatus = OcrModelStatus.Ready(
            manifest = manifest(),
            installedBytes = 10L,
        )

        override fun modelPaths(): OcrModelPaths = OcrModelPaths(
            rootDir = "/models",
            detDir = "/models/det",
            recDir = "/models/rec",
            clsDir = "/models/cls",
            dictionary = "/models/ppocr_keys_v6.txt",
        )
    }

    private fun manifest() = OcrModelManifest(
        schema = 1,
        id = "paddle-pp-ocrv6-mobile-zh",
        displayName = "PaddleOCR PP-OCRv6 Mobile 中文",
        version = "2026.06.19",
        engine = "paddle",
        assets = listOf(
            OcrModelAsset(
                name = "det",
                url = "https://paddle-model-ecology.bj.bcebos.com/paddlex/official_inference_model/paddle3.0.0/PP-OCRv6_small_det_infer.tar",
                sha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                sizeBytes = 10L,
                targetDir = "det",
            )
        ),
        detDir = "det",
        recDir = "rec",
        clsDir = "cls",
        dictionary = "ppocr_keys_v6.txt",
    )
}
