package com.xjw.qalbum.service.ocr

import android.content.Context
import java.io.File

class FileOcrModelStore(context: Context) : OcrModelStore {

    private val modelsRoot = File(context.filesDir, "ocr_models/paddle")
    private val currentDir = File(modelsRoot, "current")
    private val manifestFile = File(currentDir, OcrModelPackageInstaller.MANIFEST_FILE)
    private val downloadTempRoot = File(context.cacheDir, "ocr_downloads")

    /** [modelPaths] 的失效感知缓存:键为 manifest 文件 (mtime, length),重装会重写 manifest 自动失效。 */
    @Volatile
    private var cachedPaths: CachedPaths? = null

    private data class CachedPaths(
        val manifestMtime: Long,
        val manifestLength: Long,
        val paths: OcrModelPaths?,
    )

    override fun currentStatus(): OcrModelStatus {
        if (!manifestFile.isFile) return OcrModelStatus.NotInstalled
        val manifest = runCatching { OcrModelManifest.parse(manifestFile.readText(Charsets.UTF_8)) }
            .getOrElse { return OcrModelStatus.Failed(it.message ?: "OCR 模型 manifest 读取失败") }
        if (!manifest.isValid()) return OcrModelStatus.Failed("OCR 模型 manifest 无效")
        val paths = buildPaths(manifest)
        if (!keyFilesPresent(paths)) {
            return OcrModelStatus.Failed("OCR 模型文件不完整")
        }
        return OcrModelStatus.Ready(
            manifest = manifest,
            installedBytes = currentDir.walkTopDown().filter { it.isFile }.sumOf { it.length() },
        )
    }

    /**
     * 识别路径的轻量查询:分析管线逐图调用,不做 [currentStatus] 的整目录 walk 求和
     * (installedBytes 只有设置页需要),并按 manifest 指纹缓存,逐图开销降为两次 stat。
     */
    override fun modelPaths(): OcrModelPaths? {
        val mtime = manifestFile.lastModified()
        val length = manifestFile.length()
        if (mtime == 0L && length == 0L) {
            // manifest 不存在(未安装或已删除)
            cachedPaths = null
            return null
        }
        cachedPaths?.let { cached ->
            if (cached.manifestMtime == mtime && cached.manifestLength == length) return cached.paths
        }
        val computed = computeModelPaths()
        cachedPaths = CachedPaths(mtime, length, computed)
        return computed
    }

    fun installer(): OcrModelPackageInstaller = OcrModelPackageInstaller(modelsRoot)

    fun clear() {
        currentDir.deleteRecursively()
        // 顺带清掉历史 install 中断残留的 staging-* / current-old-* 孤儿目录
        installer().cleanupStale()
        cachedPaths = null
    }

    /** 模型下载专用临时目录(app cache 下);下载服务每次开始前整体清空,失败或进程被杀的残留不会累积。 */
    fun downloadTempDir(): File = downloadTempRoot.apply { mkdirs() }

    private fun computeModelPaths(): OcrModelPaths? {
        if (!manifestFile.isFile) return null
        val manifest = runCatching { OcrModelManifest.parse(manifestFile.readText(Charsets.UTF_8)) }
            .getOrNull() ?: return null
        if (!manifest.isValid()) return null
        val paths = buildPaths(manifest)
        return if (keyFilesPresent(paths)) paths else null
    }

    private fun keyFilesPresent(paths: OcrModelPaths): Boolean =
        File(paths.detDir).isDirectory &&
            File(paths.recDir).isDirectory &&
            File(paths.detDir, OcrModelPackageInstaller.ONNX_MODEL_FILE).isFile &&
            File(paths.recDir, OcrModelPackageInstaller.ONNX_MODEL_FILE).isFile &&
            File(paths.dictionary).isFile

    private fun buildPaths(manifest: OcrModelManifest): OcrModelPaths =
        OcrModelPaths(
            rootDir = currentDir.absolutePath,
            detDir = File(currentDir, manifest.detDir).absolutePath,
            recDir = File(currentDir, manifest.recDir).absolutePath,
            clsDir = manifest.clsDir?.let { File(currentDir, it).absolutePath },
            dictionary = File(currentDir, manifest.dictionary).absolutePath,
        )
}
