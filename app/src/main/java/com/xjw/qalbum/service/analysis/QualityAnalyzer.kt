package com.xjw.qalbum.service.analysis

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.xjw.qalbum.access.FileAccessProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.math.abs

/**
 * 图片质量分析：模糊度、纯色/空白、损坏检测
 *
 * - **模糊度**：Laplacian 方差。方差越低越模糊。
 *   - < 100 → 非常模糊
 *   - < 500 → 较模糊
 *   - > 1000 → 清晰
 * - **纯色**：缩放到 8x8 后所有像素方差极小
 * - **损坏**：BitmapFactory 解码返回 null 或宽高 <= 0
 */
object QualityAnalyzer {

    data class QualityResult(
        val width: Int,
        val height: Int,
        val isCorrupt: Boolean,
        val isBlank: Boolean,
        val blurScore: Float?,    // null 表示无法计算（损坏 / 太小）
    )

    suspend fun analyze(provider: FileAccessProvider, path: String): QualityResult {
        return withContext(Dispatchers.IO) {
            try {
                val bitmap = provider.readFile(path).use { decode(it) }
                if (bitmap == null || bitmap.width <= 0 || bitmap.height <= 0) {
                    return@withContext QualityResult(0, 0, isCorrupt = true, isBlank = false, blurScore = null)
                }
                val blank = isBlank(bitmap)
                val blur = if (!blank) computeBlur(bitmap) else 0f
                val w = bitmap.width
                val h = bitmap.height
                bitmap.recycle()
                QualityResult(width = w, height = h, isCorrupt = false, isBlank = blank, blurScore = blur)
            } catch (t: Throwable) {
                Timber.w(t, "QualityAnalyzer failed for $path")
                QualityResult(0, 0, isCorrupt = true, isBlank = false, blurScore = null)
            }
        }
    }

    /** Laplacian 方差，越低越模糊 */
    private fun computeBlur(bitmap: Bitmap): Float {
        // 缩到 256 像素以下，避免大图遍历过慢
        val sample = (maxOf(bitmap.width, bitmap.height) / 256).coerceAtLeast(1)
        val w = bitmap.width / sample
        val h = bitmap.height / sample
        if (w < 16 || h < 16) return 0f
        val gray = IntArray(w * h)
        val sampleBitmap = if (sample == 1) bitmap else Bitmap.createScaledBitmap(bitmap, w, h, true)
        val pixels = IntArray(w * h)
        sampleBitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            // luma 整数近似
            gray[i] = (r * 77 + g * 150 + b * 29) shr 8
        }
        if (sampleBitmap !== bitmap) sampleBitmap.recycle()

        // 3x3 Laplacian: [0,1,0; 1,-4,1; 0,1,0]
        var sum = 0.0
        var sumSq = 0.0
        var count = 0
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val center = gray[y * w + x]
                val laplacian = -4 * center +
                    gray[(y - 1) * w + x] + gray[(y + 1) * w + x] +
                    gray[y * w + (x - 1)] + gray[y * w + (x + 1)]
                sum += laplacian
                sumSq += laplacian.toDouble() * laplacian
                count++
            }
        }
        if (count == 0) return 0f
        val mean = sum / count
        val variance = (sumSq / count) - (mean * mean)
        return variance.toFloat()
    }

    /** 8x8 像素方差判定纯色 */
    private fun isBlank(bitmap: Bitmap): Boolean {
        val small = Bitmap.createScaledBitmap(bitmap, 8, 8, true)
        val pixels = IntArray(64)
        small.getPixels(pixels, 0, 8, 0, 0, 8, 8)
        small.recycle()

        val refR = (pixels[0] shr 16) and 0xFF
        val refG = (pixels[0] shr 8) and 0xFF
        val refB = pixels[0] and 0xFF
        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            if (abs(r - refR) > BLANK_TOLERANCE) return false
            if (abs(g - refG) > BLANK_TOLERANCE) return false
            if (abs(b - refB) > BLANK_TOLERANCE) return false
        }
        return true
    }

    private fun decode(stream: java.io.InputStream): Bitmap? {
        val bytes = stream.readBytes()
        if (bytes.isEmpty()) return null

        val headerOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, headerOpts)
        if (headerOpts.outWidth <= 0 || headerOpts.outHeight <= 0) return null

        var sample = 1
        var w = headerOpts.outWidth
        var h = headerOpts.outHeight
        // 缩放到不超过 768，质量分析不需要原图
        while (w > 768 || h > 768) {
            sample *= 2
            w /= 2
            h /= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private const val BLANK_TOLERANCE = 3
}
