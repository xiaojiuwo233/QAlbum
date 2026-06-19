package com.xjw.qalbum.service.ocr

import android.content.Context
import java.io.File

class FileOcrModelStore(context: Context) : OcrModelStore {

    private val modelsRoot = File(context.filesDir, "ocr_models/paddle")
    private val currentDir = File(modelsRoot, "current")
    private val manifestFile = File(currentDir, OcrModelPackageInstaller.MANIFEST_FILE)

    override fun currentStatus(): OcrModelStatus {
        if (!manifestFile.isFile) return OcrModelStatus.NotInstalled
        val manifest = runCatching { OcrModelManifest.parse(manifestFile.readText(Charsets.UTF_8)) }
            .getOrElse { return OcrModelStatus.Failed(it.message ?: "OCR 模型 manifest 读取失败") }
        if (!manifest.isValid()) return OcrModelStatus.Failed("OCR 模型 manifest 无效")
        val paths = buildPaths(manifest)
        if (
            !File(paths.detDir).isDirectory ||
            !File(paths.recDir).isDirectory ||
            !File(paths.detDir, OcrModelPackageInstaller.ONNX_MODEL_FILE).isFile ||
            !File(paths.recDir, OcrModelPackageInstaller.ONNX_MODEL_FILE).isFile ||
            !File(paths.dictionary).isFile
        ) {
            return OcrModelStatus.Failed("OCR 模型文件不完整")
        }
        return OcrModelStatus.Ready(
            manifest = manifest,
            installedBytes = currentDir.walkTopDown().filter { it.isFile }.sumOf { it.length() },
        )
    }

    override fun modelPaths(): OcrModelPaths? {
        val status = currentStatus()
        if (status !is OcrModelStatus.Ready) return null
        return buildPaths(status.manifest)
    }

    fun installer(): OcrModelPackageInstaller = OcrModelPackageInstaller(modelsRoot)

    fun clear() {
        currentDir.deleteRecursively()
    }

    private fun buildPaths(manifest: OcrModelManifest): OcrModelPaths =
        OcrModelPaths(
            rootDir = currentDir.absolutePath,
            detDir = File(currentDir, manifest.detDir).absolutePath,
            recDir = File(currentDir, manifest.recDir).absolutePath,
            clsDir = manifest.clsDir?.let { File(currentDir, it).absolutePath },
            dictionary = File(currentDir, manifest.dictionary).absolutePath,
        )
}
