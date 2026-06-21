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
            throw OcrEngineUnavailableException("OpenCV native library failed to initialize")
        }
        val ocr = PaddleOCR.create(
            context = context,
            config = PaddleOCRConfig(
                recScoreThresh = 0.0f,
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
    private var cachedFiles: PaddleOcrModelFiles? = null
    private var cachedAdapter: PaddleOcrAdapter? = null

    override suspend fun recognize(imageBytes: ByteArray, modelPaths: OcrModelPaths): PaddleOcrRunner.Result {
        val files = modelPaths.toModelFiles()
        val adapter = mutex.withLock {
            val current = cachedAdapter
            if (current != null && cachedFiles == files) {
                current
            } else {
                cachedAdapter?.release()
                factory.create(files).also {
                    cachedFiles = files
                    cachedAdapter = it
                }
            }
        }
        return adapter.recognize(imageBytes)
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
