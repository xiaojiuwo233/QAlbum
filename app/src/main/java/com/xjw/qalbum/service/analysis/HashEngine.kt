package com.xjw.qalbum.service.analysis

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.xjw.qalbum.access.FileAccessProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.openhft.hashing.LongHashFunction
import timber.log.Timber
import java.io.InputStream
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * 哈希引擎：xxHash（精确去重） + pHash（视觉相似检测）
 *
 * xxHash 使用 [LongHashFunction.xx3]，~5 GB/s 吞吐量；
 * pHash 自实现 DCT-based 算法：
 *   1. 解码到 32×32 灰度
 *   2. 二维 DCT-II
 *   3. 取左上 8×8 低频块
 *   4. 大于均值置 1，否则置 0 → 64-bit hash
 *
 * 设计选择：xxHash 流式实现 zero-allocation-hashing 库不支持，
 * 这里一次性读到 byte[]。QQ chatpic 单图普遍 < 5MB，可接受。
 */
object HashEngine {

    private val xxh3 = LongHashFunction.xx3()

    // ---- xxHash ----

    /** 对文件内容计算 xxh3 64-bit，返回 16 进制字符串（小写） */
    suspend fun computeXxHash(provider: FileAccessProvider, path: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                provider.readFile(path).use { stream ->
                    val bytes = stream.readBytesCapped(MAX_HASH_BYTES)
                    if (bytes.isEmpty()) return@use null
                    val h = xxh3.hashBytes(bytes)
                    h.toULong().toString(16).padStart(16, '0')
                }
            } catch (t: Throwable) {
                Timber.w(t, "xxHash failed for $path")
                null
            }
        }
    }

    // ---- pHash ----

    /** 通过 FileAccessProvider 读流并计算 pHash，失败返回 null */
    suspend fun computePHash(provider: FileAccessProvider, path: String): Long? {
        return withContext(Dispatchers.IO) {
            try {
                val bitmap = provider.readFile(path).use { decodeScaled(it, MAX_PHASH_SIDE) }
                bitmap?.let { computePHashFromBitmap(it) }
            } catch (t: Throwable) {
                Timber.w(t, "pHash failed for $path")
                null
            }
        }
    }

    /** 从 Bitmap 计算 pHash（用于已经在内存里的 Bitmap） */
    fun computePHashFromBitmap(bitmap: Bitmap): Long {
        // Step 1: 缩放到 32×32 灰度
        val scaled = Bitmap.createScaledBitmap(bitmap, PHASH_SCALE, PHASH_SCALE, true)
        val gray = Array(PHASH_SCALE) { y ->
            DoubleArray(PHASH_SCALE) { x ->
                val pixel = scaled.getPixel(x, y)
                // luminance: 0.299R + 0.587G + 0.114B
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                0.299 * r + 0.587 * g + 0.114 * b
            }
        }
        if (scaled !== bitmap) scaled.recycle()

        // Step 2: 二维 DCT-II
        val dct = dct2D(gray)

        // Step 3: 取左上 8×8 低频块（跳过 [0][0] DC 分量后的 8×8 中的均值）
        val block = Array(PHASH_LOW) { y -> DoubleArray(PHASH_LOW) { x -> dct[y][x] } }

        // Step 4: 阈值化（用除 DC 分量外的均值）
        var sum = 0.0
        var count = 0
        for (y in 0 until PHASH_LOW) for (x in 0 until PHASH_LOW) {
            if (x == 0 && y == 0) continue
            sum += block[y][x]
            count++
        }
        val mean = sum / count
        var hash = 0L
        var bit = 0
        for (y in 0 until PHASH_LOW) for (x in 0 until PHASH_LOW) {
            if (block[y][x] > mean) hash = hash or (1L shl bit)
            bit++
        }
        return hash
    }

    /** 汉明距离：两个 pHash 的差异位数 */
    fun hammingDistance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    /** 是否视觉相似：默认距离 < 10 视为同图（经验值） */
    fun isVisuallySimilar(a: Long, b: Long, threshold: Int = 10): Boolean =
        hammingDistance(a, b) < threshold

    // ---- 内部工具 ----

    /**
     * 二维 DCT-II（Type-II Discrete Cosine Transform）
     * 复杂度 O(N^4)，N=32 时计算约 10ms，可接受。
     */
    private fun dct2D(input: Array<DoubleArray>): Array<DoubleArray> {
        val n = input.size
        val out = Array(n) { DoubleArray(n) }
        val factor = PI / (2.0 * n)

        for (u in 0 until n) for (v in 0 until n) {
            var sum = 0.0
            for (i in 0 until n) for (j in 0 until n) {
                sum += input[i][j] *
                    cos((2.0 * i + 1) * u * factor) *
                    cos((2.0 * j + 1) * v * factor)
            }
            val cu = if (u == 0) 1.0 / sqrt(2.0) else 1.0
            val cv = if (v == 0) 1.0 / sqrt(2.0) else 1.0
            out[u][v] = 0.25 * cu * cv * sum
        }
        return out
    }

    /** 解码图片到不超过 maxSide 的尺寸（节省内存） */
    private fun decodeScaled(stream: InputStream, maxSide: Int): Bitmap? {
        val bytes = stream.readBytesCapped(MAX_DECODE_BYTES)
        if (bytes.isEmpty()) return null

        // 第一次解码：只读 header 得到原始尺寸
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null

        // 计算 inSampleSize
        var sample = 1
        var w = opts.outWidth
        var h = opts.outHeight
        while (w > maxSide || h > maxSide) {
            sample *= 2
            w /= 2
            h /= 2
        }
        val opts2 = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts2)
    }

    /**
     * 读取流到 byte 数组，最多读 maxBytes 字节。
     * QQ chatpic 单图普遍 < 5MB，10MB 上限做最后兜底。
     */
    private fun InputStream.readBytesCapped(maxBytes: Int): ByteArray {
        val buffer = ByteArray(8192)
        val output = java.io.ByteArrayOutputStream()
        var total = 0
        while (total < maxBytes) {
            val read = read(buffer)
            if (read == -1) break
            val toWrite = minOf(read, maxBytes - total)
            output.write(buffer, 0, toWrite)
            total += toWrite
        }
        return output.toByteArray()
    }

    // ---- 常量 ----

    private const val PHASH_SCALE = 32   // 缩放尺寸
    private const val PHASH_LOW = 8      // 低频块尺寸
    private const val MAX_PHASH_SIDE = 256
    private const val MAX_HASH_BYTES = 32 * 1024 * 1024   // xxHash 最多 32MB（罕见的超大图保护）
    private const val MAX_DECODE_BYTES = 32 * 1024 * 1024
}
