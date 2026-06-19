package com.xjw.qalbum.service.ocr

interface PaddleOcrRunner {
    suspend fun recognize(imageBytes: ByteArray, modelPaths: OcrModelPaths): Result

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

class OcrEngineUnavailableException(message: String) : RuntimeException(message)
