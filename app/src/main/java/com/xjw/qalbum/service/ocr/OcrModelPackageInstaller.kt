package com.xjw.qalbum.service.ocr

import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

class OcrModelPackageInstaller(private val modelsRoot: File) {

    fun install(zipFile: File, manifest: OcrModelManifest): OcrModelPaths {
        require(manifest.isValid()) { "Invalid OCR model manifest" }

        val staging = File(modelsRoot, "staging-${System.currentTimeMillis()}")
        val current = File(modelsRoot, "current")
        if (staging.exists()) staging.deleteRecursively()
        staging.mkdirs()

        try {
            unzipSecurely(zipFile, staging)
            val paths = buildPaths(staging, manifest)
            requireModelFiles(paths)
            File(staging, MANIFEST_FILE).writeText(OcrModelManifestJson.encode(manifest), Charsets.UTF_8)

            if (current.exists()) current.deleteRecursively()
            require(staging.renameTo(current)) { "Failed to activate OCR model package" }
            return buildPaths(current, manifest)
        } catch (t: Throwable) {
            staging.deleteRecursively()
            throw t
        }
    }

    fun install(archives: List<DownloadedOcrModelAsset>, manifest: OcrModelManifest): OcrModelPaths {
        require(manifest.isValid()) { "Invalid OCR model manifest" }
        require(archives.map { it.asset.name }.toSet() == manifest.assets.map { it.name }.toSet()) {
            "OCR model archives do not match manifest"
        }

        val staging = File(modelsRoot, "staging-${System.currentTimeMillis()}")
        val current = File(modelsRoot, "current")
        if (staging.exists()) staging.deleteRecursively()
        staging.mkdirs()

        try {
            archives.forEach { downloaded ->
                val targetDir = File(staging, downloaded.asset.targetDir)
                when (downloaded.asset.archiveType) {
                    OcrModelAsset.ARCHIVE_ZIP -> unzipSecurely(downloaded.file, targetDir)
                    OcrModelAsset.ARCHIVE_TAR -> untarSecurely(downloaded.file, targetDir)
                    OcrModelAsset.ARCHIVE_FILE -> installFile(downloaded.file, targetDir)
                    else -> error("Unsupported OCR model archive: ${downloaded.asset.archiveType}")
                }
            }
            val paths = buildPaths(staging, manifest)
            requireModelFiles(paths)
            File(staging, MANIFEST_FILE).writeText(OcrModelManifestJson.encode(manifest), Charsets.UTF_8)

            if (current.exists()) current.deleteRecursively()
            require(staging.renameTo(current)) { "Failed to activate OCR model package" }
            return buildPaths(current, manifest)
        } catch (t: Throwable) {
            staging.deleteRecursively()
            throw t
        }
    }

    private fun unzipSecurely(zipFile: File, targetDir: File) {
        val targetRoot = targetDir.canonicalFile
        targetDir.mkdirs()
        ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val outFile = File(targetDir, entry.name).canonicalFile
                require(outFile.path.startsWith(targetRoot.path + File.separator) || outFile == targetRoot) {
                    "Unsafe OCR model zip entry: ${entry.name}"
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().buffered().use { out -> zip.copyTo(out) }
                }
                zip.closeEntry()
            }
        }
    }

    private fun untarSecurely(tarFile: File, targetDir: File) {
        val targetRoot = targetDir.canonicalFile
        targetDir.mkdirs()
        tarFile.inputStream().buffered().use { input ->
            val header = ByteArray(TAR_BLOCK)
            while (true) {
                if (!input.readFully(header)) break
                if (header.all { it == 0.toByte() }) break
                val name = header.parseString(0, 100)
                if (name.isBlank()) break
                val size = header.parseOctal(124, 12)
                val type = header[156].toInt().toChar()
                val outFile = File(targetDir, name).canonicalFile
                require(outFile.path.startsWith(targetRoot.path + File.separator) || outFile == targetRoot) {
                    "Unsafe OCR model tar entry: $name"
                }
                when (type) {
                    '5' -> outFile.mkdirs()
                    '0', '\u0000' -> {
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().buffered().use { out ->
                            input.copyExactly(out, size)
                        }
                    }
                    else -> input.skipExactly(size)
                }
                val padding = (TAR_BLOCK - (size % TAR_BLOCK)).let { if (it == TAR_BLOCK.toLong()) 0L else it }
                input.skipExactly(padding)
            }
        }
    }

    private fun installFile(file: File, target: File) {
        val targetRoot = modelsRoot.canonicalFile
        val outFile = target.canonicalFile
        require(outFile.path.startsWith(targetRoot.path + File.separator) || outFile == targetRoot) {
            "Unsafe OCR model file target: ${target.path}"
        }
        outFile.parentFile?.mkdirs()
        file.copyTo(outFile, overwrite = true)
    }

    private fun InputStream.readFully(buffer: ByteArray): Boolean {
        var offset = 0
        while (offset < buffer.size) {
            val read = read(buffer, offset, buffer.size - offset)
            if (read < 0) return offset != 0
            offset += read
        }
        return true
    }

    private fun InputStream.copyExactly(out: java.io.OutputStream, bytes: Long) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var remaining = bytes
        while (remaining > 0) {
            val read = read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            require(read >= 0) { "Unexpected EOF in OCR model tar" }
            out.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun InputStream.skipExactly(bytes: Long) {
        var remaining = bytes
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped <= 0) {
                require(read() >= 0) { "Unexpected EOF in OCR model tar" }
                remaining--
            } else {
                remaining -= skipped
            }
        }
    }

    private fun ByteArray.parseString(offset: Int, length: Int): String {
        val end = (offset until offset + length).firstOrNull { this[it] == 0.toByte() } ?: (offset + length)
        return copyOfRange(offset, end).toString(Charsets.UTF_8)
    }

    private fun ByteArray.parseOctal(offset: Int, length: Int): Long {
        val text = parseString(offset, length).trim().ifBlank { "0" }
        return text.toLong(8)
    }

    private fun buildPaths(root: File, manifest: OcrModelManifest): OcrModelPaths =
        OcrModelPaths(
            rootDir = root.absolutePath,
            detDir = File(root, manifest.detDir).absolutePath,
            recDir = File(root, manifest.recDir).absolutePath,
            clsDir = manifest.clsDir?.let { File(root, it).absolutePath },
            dictionary = File(root, manifest.dictionary).absolutePath,
        )

    private fun requireModelFiles(paths: OcrModelPaths) {
        require(File(paths.detDir).isDirectory) { "Missing detection model directory" }
        require(File(paths.recDir).isDirectory) { "Missing recognition model directory" }
        require(File(paths.detDir, ONNX_MODEL_FILE).isFile) { "Missing detection ONNX model" }
        require(File(paths.recDir, ONNX_MODEL_FILE).isFile) { "Missing recognition ONNX model" }
        require(File(paths.dictionary).isFile) { "Missing OCR dictionary" }
    }

    companion object {
        const val MANIFEST_FILE = "manifest.json"
        const val ONNX_MODEL_FILE = "inference.onnx"
        private const val TAR_BLOCK = 512
    }
}
