package com.xjw.qalbum.service.cleanup

import android.content.Context
import com.xjw.qalbum.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * 设置页的压缩质量演示：内置一张照片（金边中央市场，Mx. Granger 摄，CC0，来自 Wikimedia Commons），
 * 按所选质量用与实际压缩相同的编码压一次，显示前后对比与大小。结果按质量缓存。
 */
class CompressDemo(private val context: Context) {

    /** [original] / [compressed] 是 JPEG 数据，[width] / [height] 为两者共同的尺寸 */
    class Result(val quality: Int, val original: ByteArray, val compressed: ByteArray, val width: Int, val height: Int)

    private val cache = ConcurrentHashMap<Int, Result>()

    private val original: ByteArray by lazy {
        context.resources.openRawResource(R.raw.compress_demo).use { it.readBytes() }
    }

    suspend fun render(quality: Int): Result? = withContext(Dispatchers.Default) {
        cache[quality] ?: try {
            OriginalCompressor.encode(original, quality, maxSide = 0)
                ?.let { Result(quality, original, it.bytes, it.width, it.height) }
                ?.also { cache[quality] = it }
        } catch (e: Exception) {
            Timber.w(e, "CompressDemo: render failed")
            null
        }
    }
}
