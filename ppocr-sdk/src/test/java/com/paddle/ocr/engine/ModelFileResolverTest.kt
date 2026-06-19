package com.paddle.ocr.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ModelFileResolverTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun readsAbsoluteModelPathFromFileSystem() {
        val model = temp.newFile("inference.onnx")
        model.writeBytes(byteArrayOf(1, 2, 3))

        val bytes = ModelFileResolver.readBytes(model.absolutePath) { error("assets should not be used") }

        assertArrayEquals(byteArrayOf(1, 2, 3), bytes)
    }

    @Test
    fun readsRelativeModelPathFromAssets() {
        val bytes = ModelFileResolver.readBytes("models/det/inference.onnx") { path ->
            assertEquals("models/det/inference.onnx", path)
            byteArrayOf(4, 5)
        }

        assertArrayEquals(byteArrayOf(4, 5), bytes)
    }

    @Test
    fun readsAbsoluteConfigTextFromFileSystem() {
        val config = temp.newFile("inference.yml")
        config.writeText("PostProcess:", Charsets.UTF_8)

        val text = ModelFileResolver.readText(config.absolutePath) { error("assets should not be used") }

        assertEquals("PostProcess:", text)
    }

    @Test
    fun rejectsMissingAbsoluteFile() {
        val missing = File(temp.root, "missing.onnx")

        val result = runCatching {
            ModelFileResolver.readBytes(missing.absolutePath) { error("assets should not be used") }
        }

        assertEquals(true, result.exceptionOrNull() is java.io.FileNotFoundException)
    }
}
