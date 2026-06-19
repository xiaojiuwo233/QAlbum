package com.xjw.qalbum.service.ocr

object DefaultPaddleOcrModelManifest {
    private const val BASE = OcrModelDownloadService.PADDLE_MODEL_BASE

    val value = OcrModelManifest(
        schema = OcrModelManifest.SCHEMA,
        id = "paddle-pp-ocrv6-small-zh-onnx",
        displayName = "PaddleOCR PP-OCRv6 Small 中文 ONNX",
        version = "PP-OCRv6-small-onnx",
        engine = OcrModelManifest.ENGINE_PADDLE,
        assets = listOf(
            OcrModelAsset(
                name = "det",
                url = "$BASE/PP-OCRv6_small_det_onnx_infer.tar",
                sha256 = "d218f6fbf0f1c23d2161bd6ac7f5eaa6104fa89955c09290497e31008e2618e4",
                sizeBytes = 9_891_840L,
                targetDir = "det",
                archiveType = OcrModelAsset.ARCHIVE_TAR,
            ),
            OcrModelAsset(
                name = "rec",
                url = "$BASE/PP-OCRv6_small_rec_onnx_infer.tar",
                sha256 = "d267ab077a44a0eedb1ea8f8c542d263f211de8e9d7a029bf9fcfff7e5a88fb1",
                sizeBytes = 21_319_680L,
                targetDir = "rec",
                archiveType = OcrModelAsset.ARCHIVE_TAR,
            ),
        ),
        detDir = "det/PP-OCRv6_small_det_onnx_infer",
        recDir = "rec/PP-OCRv6_small_rec_onnx_infer",
        clsDir = null,
        dictionary = "rec/PP-OCRv6_small_rec_onnx_infer/inference.yml",
    )
}
