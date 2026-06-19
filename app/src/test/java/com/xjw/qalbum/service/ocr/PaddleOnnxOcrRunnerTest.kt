package com.xjw.qalbum.service.ocr

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PaddleOnnxOcrRunnerTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun passesDownloadedModelPathsToPaddleSdkAndCombinesLines() = runBlocking {
        val root = temp.newFolder("models")
        val det = File(root, "det").apply { mkdirs() }
        val rec = File(root, "rec").apply { mkdirs() }
        File(det, "inference.onnx").writeText("det", Charsets.UTF_8)
        File(rec, "inference.onnx").writeText("rec", Charsets.UTF_8)
        File(rec, "inference.yml").writeText("dict", Charsets.UTF_8)
        val calls = mutableListOf<PaddleOcrModelFiles>()
        val runner = PaddleOnnxOcrRunner(
            factory = object : PaddleOcrFactory {
                override suspend fun create(modelFiles: PaddleOcrModelFiles): PaddleOcrAdapter {
                    calls += modelFiles
                    return object : PaddleOcrAdapter {
                        override suspend fun recognize(imageBytes: ByteArray): PaddleOcrRunner.Result =
                            PaddleOcrRunner.Result(
                                text = listOf("订单编号 12345", "金额 99.00").joinToString("\n"),
                                confidence = 0.88f,
                            )

                        override suspend fun release() = Unit
                    }
                }
            }
        )
        val paths = OcrModelPaths(
            rootDir = root.absolutePath,
            detDir = det.absolutePath,
            recDir = rec.absolutePath,
            clsDir = null,
            dictionary = File(rec, "inference.yml").absolutePath,
        )

        val result = runner.recognize(byteArrayOf(1), paths)

        assertEquals("订单编号 12345\n金额 99.00", result.text)
        assertEquals(0.88f, result.confidence, 0.0001f)
        assertEquals(1, calls.size)
        assertEquals(File(det, "inference.onnx").absolutePath, calls.single().detModelPath)
        assertEquals(File(rec, "inference.onnx").absolutePath, calls.single().recModelPath)
        assertEquals(File(rec, "inference.yml").absolutePath, calls.single().recConfigPath)
    }

    @Test
    fun rejectsModelPathsMissingOnnxFiles() = runBlocking {
        val runner = PaddleOnnxOcrRunner(
            factory = object : PaddleOcrFactory {
                override suspend fun create(modelFiles: PaddleOcrModelFiles): PaddleOcrAdapter {
                    error("factory should not be called")
                }
            }
        )
        val paths = OcrModelPaths(
            rootDir = "/models",
            detDir = "/models/det",
            recDir = "/models/rec",
            clsDir = null,
            dictionary = "/models/rec/inference.yml",
        )

        val result = runCatching { runner.recognize(byteArrayOf(1), paths) }

        assertTrue(result.exceptionOrNull() is OcrEngineUnavailableException)
    }
}
