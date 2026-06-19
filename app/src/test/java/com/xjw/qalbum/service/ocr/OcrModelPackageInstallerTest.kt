package com.xjw.qalbum.service.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Rule
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class OcrModelPackageInstallerTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun installsManifestAndModelPathsFromZip() {
        val zip = temp.newFile("model.zip")
        writeZip(
            zip,
            "det/inference.onnx" to "det-model",
            "rec/inference.onnx" to "rec-model",
            "rec/inference.yml" to "字典",
        )
        val root = temp.newFolder("models")
        val manifest = manifest()

        val paths = OcrModelPackageInstaller(root).install(zip, manifest)

        assertEquals(File(root, "current").absolutePath, paths.rootDir)
        assertTrue(File(paths.detDir, "inference.onnx").exists())
        assertTrue(File(paths.recDir, "inference.onnx").exists())
        assertTrue(File(paths.dictionary).exists())
        assertTrue(File(root, "current/manifest.json").exists())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsZipSlipEntries() {
        val zip = temp.newFile("evil.zip")
        writeZip(zip, "../escape.txt" to "bad")

        OcrModelPackageInstaller(temp.newFolder("models")).install(zip, manifest())
    }

    @Test
    fun installsTarAssetsIntoTargetDirs() {
        val det = temp.newFile("det.tar")
        val rec = temp.newFile("rec.tar")
        writeTar(det, "PP-OCRv6_small_det_onnx_infer/inference.onnx" to "det-model")
        writeTar(rec, "PP-OCRv6_small_rec_onnx_infer/inference.onnx" to "rec-model", "PP-OCRv6_small_rec_onnx_infer/inference.yml" to "字典")
        val root = temp.newFolder("models")
        val manifest = manifest(
            assets = listOf(
                OcrModelAsset(
                    name = "det",
                    url = "https://paddle-model-ecology.bj.bcebos.com/paddlex/official_inference_model/paddle3.0.0/PP-OCRv6_small_det_infer.tar",
                    sha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                    sizeBytes = det.length(),
                    targetDir = "det",
                    archiveType = OcrModelAsset.ARCHIVE_TAR,
                ),
                OcrModelAsset(
                    name = "rec",
                    url = "https://paddle-model-ecology.bj.bcebos.com/paddlex/official_inference_model/paddle3.0.0/PP-OCRv6_small_rec_infer.tar",
                    sha256 = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
                    sizeBytes = rec.length(),
                    targetDir = "rec",
                    archiveType = OcrModelAsset.ARCHIVE_TAR,
                ),
            ),
            detDir = "det/PP-OCRv6_small_det_onnx_infer",
            recDir = "rec/PP-OCRv6_small_rec_onnx_infer",
            dictionary = "rec/PP-OCRv6_small_rec_onnx_infer/inference.yml",
        )

        val paths = OcrModelPackageInstaller(root).install(
            listOf(
                DownloadedOcrModelAsset(manifest.assets[0], det),
                DownloadedOcrModelAsset(manifest.assets[1], rec),
            ),
            manifest,
        )

        assertTrue(File(paths.detDir, "inference.onnx").exists())
        assertTrue(File(paths.recDir, "inference.onnx").exists())
        assertTrue(File(paths.dictionary).exists())
    }

    private fun writeZip(file: File, vararg entries: Pair<String, String>) {
        ZipOutputStream(file.outputStream()).use { out ->
            entries.forEach { (name, content) ->
                out.putNextEntry(ZipEntry(name))
                out.write(content.toByteArray())
                out.closeEntry()
            }
        }
    }

    private fun writeTar(file: File, vararg entries: Pair<String, String>) {
        FileOutputStream(file).use { out ->
            entries.forEach { (name, content) ->
                val bytes = content.toByteArray()
                val header = ByteArray(512)
                val nameBytes = name.toByteArray()
                System.arraycopy(nameBytes, 0, header, 0, nameBytes.size.coerceAtMost(100))
                writeOctal(header, 100, 8, 0b110100100)
                writeOctal(header, 108, 8, 0)
                writeOctal(header, 116, 8, 0)
                writeOctal(header, 124, 12, bytes.size)
                writeOctal(header, 136, 12, 0)
                for (i in 148 until 156) header[i] = ' '.code.toByte()
                header[156] = '0'.code.toByte()
                val magic = "ustar".toByteArray()
                System.arraycopy(magic, 0, header, 257, magic.size)
                var sum = 0
                header.forEach { sum += it.toUByte().toInt() }
                writeOctal(header, 148, 8, sum)
                out.write(header)
                out.write(bytes)
                val padding = (512 - bytes.size % 512) % 512
                if (padding > 0) out.write(ByteArray(padding))
            }
            out.write(ByteArray(1024))
        }
    }

    private fun writeOctal(header: ByteArray, offset: Int, length: Int, value: Int) {
        val raw = value.toString(8).padStart(length - 1, '0').takeLast(length - 1)
        raw.forEachIndexed { index, c -> header[offset + index] = c.code.toByte() }
        header[offset + length - 1] = 0
    }

    private fun manifest(
        assets: List<OcrModelAsset> = listOf(
            OcrModelAsset(
                name = "det",
                url = "https://paddle-model-ecology.bj.bcebos.com/paddlex/official_inference_model/paddle3.0.0/PP-OCRv6_small_det_onnx_infer.tar",
                sha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                sizeBytes = 10L,
                targetDir = "det",
            )
        ),
        detDir: String = "det",
        recDir: String = "rec",
        dictionary: String = "rec/inference.yml",
    ) = OcrModelManifest(
        schema = 1,
        id = "paddle-pp-ocrv6-small-zh-onnx",
        displayName = "PaddleOCR PP-OCRv6 Small 中文 ONNX",
        version = "PP-OCRv6-small-onnx",
        engine = "paddle",
        assets = assets,
        detDir = detDir,
        recDir = recDir,
        clsDir = null,
        dictionary = dictionary,
    )
}
