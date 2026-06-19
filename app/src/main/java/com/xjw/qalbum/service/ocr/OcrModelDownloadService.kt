package com.xjw.qalbum.service.ocr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.security.MessageDigest

class OcrModelDownloadService(
    private val modelStore: FileOcrModelStore,
    private val okHttpClient: OkHttpClient,
    private val appScope: CoroutineScope,
) {

    private val _downloadState = MutableStateFlow<OcrModelDownloadState>(OcrModelDownloadState.Idle)
    val downloadState: StateFlow<OcrModelDownloadState> = _downloadState.asStateFlow()

    fun refreshStatus(): OcrModelStatus = modelStore.currentStatus()

    fun startDownload() {
        if (_downloadState.value is OcrModelDownloadState.Downloading) return
        appScope.launch {
            _downloadState.value = OcrModelDownloadState.Downloading(progress = 0f, message = "准备模型清单")
            try {
                val manifest = defaultManifest()
                _downloadState.value = OcrModelDownloadState.Downloading(progress = 0.05f, message = "下载模型包")
                val archives = downloadAssets(manifest)
                _downloadState.value = OcrModelDownloadState.Downloading(progress = 0.9f, message = "安装模型")
                modelStore.installer().install(archives, manifest)
                archives.forEach { it.file.delete() }
                _downloadState.value = OcrModelDownloadState.Done(modelStore.currentStatus())
            } catch (t: Throwable) {
                Timber.w(t, "OCR model download failed")
                _downloadState.value = OcrModelDownloadState.Failed(t.message ?: "OCR 模型下载失败")
            }
        }
    }

    fun clearModel() {
        modelStore.clear()
        _downloadState.value = OcrModelDownloadState.Idle
    }

    fun defaultManifest(): OcrModelManifest = DefaultPaddleOcrModelManifest.value

    private suspend fun downloadAssets(manifest: OcrModelManifest): List<DownloadedOcrModelAsset> = withContext(Dispatchers.IO) {
        val totalBytes = manifest.assets.sumOf { it.sizeBytes }.coerceAtLeast(1L)
        var finishedBytes = 0L
        manifest.assets.map { asset ->
            val target = File.createTempFile("qalbum-ocr-${asset.name}-", ".${asset.archiveType}")
            val req = Request.Builder().url(asset.url).get().build()
            var assetCopied = 0L
            okHttpClient.newCall(req).execute().use { resp ->
                require(resp.isSuccessful) { "下载 OCR 模型 ${asset.name} 失败：HTTP ${resp.code}" }
                val body = resp.body ?: error("OCR 模型 ${asset.name} 响应为空")
                body.byteStream().use { input ->
                    target.outputStream().buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            assetCopied += read
                            val progress = ((finishedBytes + assetCopied).toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                            _downloadState.value = OcrModelDownloadState.Downloading(
                                progress = 0.05f + progress * 0.85f,
                                message = "下载 ${asset.name}",
                            )
                        }
                    }
                }
            }
            val actualSha = sha256(target)
            require(actualSha.equals(asset.sha256, ignoreCase = true)) { "OCR 模型 ${asset.name} 校验失败" }
            finishedBytes += assetCopied
            DownloadedOcrModelAsset(asset, target)
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val PADDLE_MODEL_BASE =
            "https://paddle-model-ecology.bj.bcebos.com/paddlex/official_inference_model/paddle3.0.0"
    }
}

data class DownloadedOcrModelAsset(
    val asset: OcrModelAsset,
    val file: File,
)

sealed class OcrModelDownloadState {
    data object Idle : OcrModelDownloadState()
    data class Downloading(val progress: Float, val message: String) : OcrModelDownloadState()
    data class Done(val status: OcrModelStatus) : OcrModelDownloadState()
    data class Failed(val reason: String) : OcrModelDownloadState()
}
