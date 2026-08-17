package com.xjw.qalbum.service.ocr

import android.content.Context
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.PaddleOCR
import com.paddle.ocr.PaddleOCRConfig
import com.paddle.ocr.util.OpenCVUtils
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

data class PaddleOcrModelFiles(
    val detModelPath: String,
    val recModelPath: String,
    val recConfigPath: String,
)

interface PaddleOcrAdapter {
    suspend fun recognize(imageBytes: ByteArray): PaddleOcrRunner.Result
    suspend fun release()
}

interface PaddleOcrFactory {
    suspend fun create(modelFiles: PaddleOcrModelFiles): PaddleOcrAdapter
}

class AndroidPaddleOcrFactory(private val context: Context) : PaddleOcrFactory {
    override suspend fun create(modelFiles: PaddleOcrModelFiles): PaddleOcrAdapter {
        if (!OpenCVUtils.init(context)) {
            // 带上 dlopen 根因(缺失符号名等),否则文件日志只有一句「初始化失败」无从排查
            throw OcrEngineUnavailableException(
                "OpenCV native library failed to initialize",
                OpenCVUtils.lastError,
            )
        }
        val ocr = PaddleOCR.create(
            context = context,
            config = PaddleOCRConfig(
                recScoreThresh = 0.0f,
                // det 输入按官方 mobile 部署惯例限制最长边 960:SDK 默认 min/64 对普通照片不缩放,
                // 12MP 直推的单个 FloatArray 可达 144MB(大概率 OOM,未 OOM 单张也要数十秒)。
                detLimitType = "max",
                detLimitSideLen = 960,
                // 批量识别：一张图的多个文本行一次推理（原逐行 =1，文本多的图很慢）。
                // RecPreprocessor.preprocessBatch 会把批内不同宽度 padding 对齐；6 为 PaddleOCR 常用值。
                recBatchSize = 6,
            ),
            engineConfig = EngineConfig(numThreads = 4),
            detModelAssetPath = modelFiles.detModelPath,
            recModelAssetPath = modelFiles.recModelPath,
            recConfigAssetPath = modelFiles.recConfigPath,
        )
        return PaddleSdkAdapter(ocr)
    }
}

class PaddleOnnxOcrRunner(
    private val factory: PaddleOcrFactory,
) : PaddleOcrRunner {
    private val mutex = Mutex()
    private var cachedKey: EngineKey? = null
    private var cachedAdapter: PaddleOcrAdapter? = null

    /**
     * 缓存键:模型始终安装到固定的 current 目录,路径不变,仅比路径会让「删除后重下/升级」
     * 永远命中旧引擎;叠加模型文件 mtime/length 指纹感知同路径重装。
     */
    private data class EngineKey(
        val files: PaddleOcrModelFiles,
        val fingerprint: List<Long>,
    )

    override suspend fun recognize(imageBytes: ByteArray, modelPaths: OcrModelPaths): PaddleOcrRunner.Result {
        // recognize 全程持锁:分析管线本就逐图串行,串行化保证重建/invalidate 不会
        // release 正在推理的 native session(OrtSession.close 撞上在途 run 是 UB)。
        return mutex.withLock {
            val files = modelPaths.toModelFiles()
            val key = EngineKey(files, fingerprintOf(files))
            val current = cachedAdapter
            val adapter = if (current != null && cachedKey == key) {
                current
            } else {
                cachedAdapter?.release()
                cachedAdapter = null
                cachedKey = null
                factory.create(files).also {
                    cachedKey = key
                    cachedAdapter = it
                }
            }
            adapter.recognize(imageBytes)
        }
    }

    override suspend fun invalidate() {
        mutex.withLock {
            cachedAdapter?.release()
            cachedAdapter = null
            cachedKey = null
        }
    }

    private fun fingerprintOf(files: PaddleOcrModelFiles): List<Long> =
        listOf(files.detModelPath, files.recModelPath, files.recConfigPath).flatMap { path ->
            val f = File(path)
            listOf(f.lastModified(), f.length())
        }

    private fun OcrModelPaths.toModelFiles(): PaddleOcrModelFiles {
        val detModel = File(detDir, OcrModelPackageInstaller.ONNX_MODEL_FILE)
        val recModel = File(recDir, OcrModelPackageInstaller.ONNX_MODEL_FILE)
        val recConfig = File(dictionary)
        if (!detModel.isFile || !recModel.isFile || !recConfig.isFile) {
            throw OcrEngineUnavailableException("Paddle OCR model files are incomplete")
        }
        return PaddleOcrModelFiles(
            detModelPath = detModel.absolutePath,
            recModelPath = recModel.absolutePath,
            recConfigPath = recConfig.absolutePath,
        )
    }
}

private class PaddleSdkAdapter(private val ocr: PaddleOCR) : PaddleOcrAdapter {
    override suspend fun recognize(imageBytes: ByteArray): PaddleOcrRunner.Result {
        val result = ocr.recognize(imageBytes)
        val lines = result.results
            .map { it.text.trim() }
            .filter { it.isNotBlank() }
        val confidence = if (result.results.isEmpty()) {
            0f
        } else {
            result.results.map { it.confidence }.average().toFloat()
        }
        return PaddleOcrRunner.Result(
            text = lines.joinToString("\n"),
            confidence = confidence,
        )
    }

    override suspend fun release() {
        ocr.release()
    }
}
