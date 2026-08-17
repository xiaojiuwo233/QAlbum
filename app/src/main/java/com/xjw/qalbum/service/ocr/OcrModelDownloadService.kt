package com.xjw.qalbum.service.ocr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

class OcrModelDownloadService(
    private val modelStore: FileOcrModelStore,
    private val okHttpClient: OkHttpClient,
    private val appScope: CoroutineScope,
    private val runner: PaddleOcrRunner,
) {

    private val _downloadState = MutableStateFlow<OcrModelDownloadState>(OcrModelDownloadState.Idle)
    val downloadState: StateFlow<OcrModelDownloadState> = _downloadState.asStateFlow()

    private val _modelStatus = MutableStateFlow<OcrModelStatus>(OcrModelStatus.NotInstalled)

    /** 模型安装状态。初值 NotInstalled 占位,构造时即异步刷新;UI 收集此流,避免主线程磁盘 IO。 */
    val modelStatus: StateFlow<OcrModelStatus> = _modelStatus.asStateFlow()

    /** 重入抢占标志:检查与置位必须原子,快速重复调用不能并发跑两条「下载+安装」链。 */
    private val downloading = AtomicBoolean(false)
    private val clearing = AtomicBoolean(false)
    private val modelMutationMutex = Mutex()
    private val jobLock = Any()

    @Volatile
    private var downloadJob: Job? = null

    init {
        refreshStatus()
    }

    /** 异步刷新模型状态(manifest 解析与目录遍历都是磁盘 IO),结果经 [modelStatus] 回推。 */
    fun refreshStatus() {
        appScope.launch(Dispatchers.IO) {
            modelMutationMutex.withLock {
                _modelStatus.value = modelStore.currentStatus()
            }
        }
    }

    fun startDownload() {
        if (clearing.get()) return
        if (!downloading.compareAndSet(false, true)) return
        if (clearing.get()) {
            downloading.set(false)
            return
        }
        _downloadState.value = OcrModelDownloadState.Downloading(progress = 0f, message = "准备模型清单")
        val job = appScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            val ownJob = coroutineContext[Job]
            var archives: List<DownloadedOcrModelAsset> = emptyList()
            try {
                modelMutationMutex.withLock {
                    currentCoroutineContext().ensureActive()
                    if (clearing.get()) throw CancellationException("OCR 模型正在清除")
                    val manifest = defaultManifest()
                    _downloadState.value = OcrModelDownloadState.Downloading(progress = 0.05f, message = "下载模型包")
                    archives = downloadAssets(manifest)
                    _downloadState.value = OcrModelDownloadState.Downloading(progress = 0.9f, message = "安装模型")
                    modelStore.installer().install(archives, manifest)
                    // 释放缓存的旧 native 引擎:安装目录固定为 current,不失效会继续用旧权重识别
                    runner.invalidate()
                    val status = modelStore.currentStatus()
                    _modelStatus.value = status
                    _downloadState.value = OcrModelDownloadState.Done(status)
                }
            } catch (ce: CancellationException) {
                _downloadState.value = OcrModelDownloadState.Idle
                throw ce
            } catch (t: Throwable) {
                Timber.w(t, "OCR model download failed")
                _downloadState.value = OcrModelDownloadState.Failed(t.message ?: "OCR 模型下载失败")
            } finally {
                // 成功失败都清临时包:安装是拷贝式的,tar 用完即弃,失败残留会随重试线性累积
                archives.forEach { it.file.delete() }
                downloading.set(false)
                synchronized(jobLock) {
                    if (downloadJob === ownJob) downloadJob = null
                }
            }
        }
        val accepted = synchronized(jobLock) {
            if (clearing.get()) false else {
                downloadJob = job
                true
            }
        }
        if (accepted) {
            job.start()
        } else {
            job.cancel()
            downloading.set(false)
            _downloadState.value = OcrModelDownloadState.Idle
        }
    }

    /** 删除已安装模型。递归删约 31MB 目录属磁盘 IO,切后台执行,并释放缓存的 native 引擎。 */
    fun clearModel() {
        if (!clearing.compareAndSet(false, true)) return
        appScope.launch(Dispatchers.IO) {
            try {
                val activeDownload = synchronized(jobLock) { downloadJob }
                activeDownload?.cancelAndJoin()
                modelMutationMutex.withLock {
                    modelStore.clear()
                    runner.invalidate()
                    _modelStatus.value = modelStore.currentStatus()
                    _downloadState.value = OcrModelDownloadState.Idle
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Timber.w(t, "OCR model clear failed")
                _downloadState.value = OcrModelDownloadState.Failed(t.message ?: "OCR 模型删除失败")
            } finally {
                clearing.set(false)
            }
        }
    }

    fun defaultManifest(): OcrModelManifest = DefaultPaddleOcrModelManifest.value

    private suspend fun downloadAssets(manifest: OcrModelManifest): List<DownloadedOcrModelAsset> = withContext(Dispatchers.IO) {
        val tempDir = modelStore.downloadTempDir()
        // 上一次失败/进程被杀的残留在此统一回收(重入已由 downloading 标志挡住,清空是安全的)
        tempDir.listFiles()?.forEach { it.deleteRecursively() }
        val totalBytes = manifest.assets.sumOf { it.sizeBytes }.coerceAtLeast(1L)
        var finishedBytes = 0L
        val downloaded = mutableListOf<DownloadedOcrModelAsset>()
        try {
            manifest.assets.forEach { asset ->
                val target = File.createTempFile("qalbum-ocr-${asset.name}-", ".${asset.archiveType}", tempDir)
                // 先登记再写入,任一环节抛出时 catch 能连同进行中的文件一起删掉
                downloaded += DownloadedOcrModelAsset(asset, target)
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
            }
            downloaded.toList()
        } catch (t: Throwable) {
            downloaded.forEach { it.file.delete() }
            throw t
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
