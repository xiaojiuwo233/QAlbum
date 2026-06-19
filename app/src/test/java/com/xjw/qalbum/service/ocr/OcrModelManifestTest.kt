package com.xjw.qalbum.service.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrModelManifestTest {

    @Test
    fun parsesBundledManifestJson() {
        val manifest = OcrModelManifest.parse(
            """
            {
              "schema": 1,
              "id": "paddle-pp-ocrv6-mobile-zh",
              "displayName": "PaddleOCR PP-OCRv6 Mobile 中文",
              "version": "2026.06.19",
              "engine": "paddle",
              "assets": [
                {
                  "name": "det",
                  "url": "https://paddle-model-ecology.bj.bcebos.com/paddlex/official_inference_model/paddle3.0.0/PP-OCRv6_small_det_infer.tar",
                  "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                  "sizeBytes": 10485760,
                  "targetDir": "det",
                  "archiveType": "tar"
                }
              ],
              "detDir": "det",
              "recDir": "rec",
              "clsDir": "cls",
              "dictionary": "ppocr_keys_v6.txt"
            }
            """.trimIndent()
        )

        assertEquals("paddle-pp-ocrv6-mobile-zh", manifest.id)
        assertEquals("PaddleOCR PP-OCRv6 Mobile 中文", manifest.displayName)
        assertEquals("2026.06.19", manifest.version)
        assertEquals("paddle", manifest.engine)
        assertEquals(1, manifest.assets.size)
        assertEquals("det", manifest.assets.first().targetDir)
        assertEquals("det", manifest.detDir)
        assertEquals("rec", manifest.recDir)
        assertEquals("cls", manifest.clsDir)
        assertEquals("ppocr_keys_v6.txt", manifest.dictionary)
    }

    @Test
    fun acceptsOnlyHexSha256With64Characters() {
        assertTrue(OcrModelManifest.isValidSha256("a".repeat(64)))
        assertTrue(OcrModelManifest.isValidSha256("ABCDEF0123456789".repeat(4)))
        assertFalse(OcrModelManifest.isValidSha256("a".repeat(63)))
        assertFalse(OcrModelManifest.isValidSha256("g".repeat(64)))
    }

    @Test
    fun defaultManifestUsesPaddleSourcesAndNoQalbumReleaseManifest() {
        val manifest = DefaultPaddleOcrModelManifest.value

        assertTrue(manifest.isValid())
        assertEquals("paddle-pp-ocrv6-small-zh-onnx", manifest.id)
        assertEquals("PaddleOCR PP-OCRv6 Small 中文 ONNX", manifest.displayName)
        assertEquals(2, manifest.assets.size)
        assertTrue(manifest.assets.all { it.url.contains("paddle-model-ecology.bj.bcebos.com") })
        assertTrue(manifest.assets.all { it.archiveType == OcrModelAsset.ARCHIVE_TAR })
        assertFalse(manifest.assets.any { it.url.contains("xiaojiuwo233/QAlbum") })
        assertEquals("det/PP-OCRv6_small_det_onnx_infer", manifest.detDir)
        assertEquals("rec/PP-OCRv6_small_rec_onnx_infer", manifest.recDir)
        assertEquals("rec/PP-OCRv6_small_rec_onnx_infer/inference.yml", manifest.dictionary)
    }
}
