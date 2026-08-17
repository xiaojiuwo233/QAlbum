package com.xjw.qalbum.service.ocr

import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

class OcrModelPackageInstaller(private val modelsRoot: File) {

    fun install(zipFile: File, manifest: OcrModelManifest): OcrModelPaths {
        require(manifest.isValid()) { "Invalid OCR model manifest" }

        cleanupStale()
        val staging = File(modelsRoot, "$STAGING_PREFIX${System.currentTimeMillis()}")
        val current = File(modelsRoot, "current")
        staging.mkdirs()

        try {
            unzipSecurely(zipFile, staging)
            val paths = buildPaths(staging, manifest)
            requireModelFiles(paths)
            File(staging, MANIFEST_FILE).writeText(OcrModelManifestJson.encode(manifest), Charsets.UTF_8)

            activate(staging, current)
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

        cleanupStale()
        val staging = File(modelsRoot, "$STAGING_PREFIX${System.currentTimeMillis()}")
        val current = File(modelsRoot, "current")
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

            activate(staging, current)
            return buildPaths(current, manifest)
        } catch (t: Throwable) {
            staging.deleteRecursively()
            throw t
        }
    }

    /**
     * 清理历史 install 中断残留的 staging-* / current-old-* 目录:解压途中进程被杀不会走 catch,
     * 时间戳命名的目录若不主动扫,会以每次约 30MB 永久累积。
     */
    fun cleanupStale() {
        modelsRoot.listFiles()?.forEach { f ->
            if (f.isDirectory && (f.name.startsWith(STAGING_PREFIX) || f.name.startsWith(OLD_PREFIX))) {
                f.deleteRecursively()
            }
        }
    }

    /**
     * 三步换名激活:目录 rename 是原子操作且不受目录内容影响,任一步失败都保得住可用版本。
     * 旧方案「先删 current 再 rename」在部分删除失败或进程死亡时新旧模型同时丢失。
     */
    private fun activate(staging: File, current: File) {
        val retired = File(modelsRoot, "$OLD_PREFIX${System.currentTimeMillis()}")
        if (current.exists()) {
            require(current.renameTo(retired)) { "Failed to retire current OCR model package" }
        }
        if (!staging.renameTo(current)) {
            // 激活失败:把旧模型改回 current,保证已安装版本不丢;staging 由调用方 catch 清理
            if (retired.exists()) retired.renameTo(current)
            error("Failed to activate OCR model package")
        }
        retired.deleteRecursively()
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
                // ustar 长路径把前段放 prefix 字段(offset 345, 155B),只取前 100 字节会以截断名错位解压;
                // 仅认 POSIX magic "ustar\0"(GNU 旧格式 "ustar  " 的 345 处是 atime,不是路径)
                val isUstar = header.parseString(257, 6) == "ustar"
                val prefix = if (isUstar) header.parseString(345, 155) else ""
                val baseName = header.parseString(0, 100)
                val name = if (prefix.isEmpty()) baseName else "$prefix/$baseName"
                if (name.isBlank()) break
                val size = header.parseOctal(124, 12)
                val type = header[156].toInt().toChar()
                // GNU longname/longlink 与 PAX 扩展头承载的是「下一条目的真实路径」,当普通条目跳过
                // 会让后续条目以截断名落到错误位置,必须显式拒绝而不是错位解压
                require(type !in UNSUPPORTED_TAR_TYPES) {
                    "Unsupported OCR model tar feature (type '$type'): $name"
                }
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
            if (read < 0) {
                // 半个 header 会与复用 buffer 里上一条目的残留字节拼在一起被解析,只能报截断
                require(offset == 0) { "Truncated OCR model tar header" }
                return false
            }
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
        private const val STAGING_PREFIX = "staging-"
        private const val OLD_PREFIX = "current-old-"

        // 'L'/'K'=GNU longname/longlink,'x'=PAX 条目扩展头,'g'=PAX 全局扩展头
        private val UNSUPPORTED_TAR_TYPES = setOf('L', 'K', 'x', 'g')
    }
}
