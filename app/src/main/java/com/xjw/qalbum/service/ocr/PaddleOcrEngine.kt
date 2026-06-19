package com.xjw.qalbum.service.ocr

import com.xjw.qalbum.access.FileAccessProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

class PaddleOcrEngine(
    private val modelStore: OcrModelStore,
    private val runner: PaddleOcrRunner,
) {

    suspend fun recognize(provider: FileAccessProvider, path: String): OcrResult =
        withContext(Dispatchers.IO) {
            val modelPaths = modelStore.modelPaths()
            if (modelPaths == null || modelStore.currentStatus() !is OcrModelStatus.Ready) {
                return@withContext OcrResult.Unavailable
            }

            try {
                val bytes = provider.readFile(path).use { it.readBytes() }
                if (bytes.isEmpty()) return@withContext OcrResult.Empty

                val result = runner.recognize(bytes, modelPaths)
                val full = result.text.trim()
                if (full.isBlank()) return@withContext OcrResult.Empty

                OcrResult.Success(
                    text = full,
                    confidence = result.confidence.coerceIn(0f, 1f),
                    hasPhone = SensitivePatterns.Phone.containsMatchIn(full),
                    hasAmount = SensitivePatterns.Amount.containsMatchIn(full),
                    hasAddress = SensitivePatterns.Address.containsMatchIn(full),
                    hasIdNumber = SensitivePatterns.IdNumber.containsMatchIn(full),
                    hasOrderNo = SensitivePatterns.OrderNo.containsMatchIn(full),
                )
            } catch (t: OcrEngineUnavailableException) {
                Timber.i(t, "Paddle OCR unavailable for $path")
                OcrResult.Unavailable
            } catch (t: Throwable) {
                Timber.w(t, "Paddle OCR failed for $path")
                OcrResult.Failed(t.message ?: "未知错误")
            }
        }
}

sealed class OcrResult {
    data class Success(
        val text: String,
        val confidence: Float,
        val hasPhone: Boolean,
        val hasAmount: Boolean,
        val hasAddress: Boolean,
        val hasIdNumber: Boolean,
        val hasOrderNo: Boolean,
    ) : OcrResult()

    data object Empty : OcrResult()
    data object Unavailable : OcrResult()
    data class Failed(val reason: String) : OcrResult()
}

private object SensitivePatterns {
    val Phone = Regex("""(?<!\d)1[3-9]\d{9}(?!\d)""")
    val Amount = Regex("""[¥￥$]\s*\d+(?:[.,]\d+)?|(?<!\d)\d+\.\d{2}(?:元|块|RMB|CNY)?""")
    val Address = Regex("""[\u4e00-\u9fa5]{2,}(省|市|区|县|镇|乡|村|街道|路|街|号|栋|楼|室|单元)""")
    val IdNumber = Regex("""(?<!\d)\d{17}[\dXx](?!\d)""")
    val OrderNo = Regex("""(?<!\d)\d{15,30}(?!\d)""")
}
