package com.xjw.qalbum.service.ocr

interface PaddleOcrRunner {
    suspend fun recognize(imageBytes: ByteArray, modelPaths: OcrModelPaths): Result

    /** 释放缓存的 native 引擎并使缓存失效;模型安装成功/删除后由下载服务调用,无缓存实现为 no-op。 */
    suspend fun invalidate() {}

    data class Result(
        val text: String,
        val confidence: Float,
    )
}

class UnavailablePaddleOcrRunner : PaddleOcrRunner {
    override suspend fun recognize(imageBytes: ByteArray, modelPaths: OcrModelPaths): PaddleOcrRunner.Result {
        throw OcrEngineUnavailableException("Paddle OCR native runner is not linked yet")
    }
}

class OcrEngineUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
