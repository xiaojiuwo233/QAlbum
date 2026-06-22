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
 * - xxHash 使用 [LongHashFunction.xx3]，~5 GB/s 吞吐量。
 * - pHash 自实现 DCT-based 算法：缩放到 32×32 灰度 → 二维 DCT → 取左上 8×8 低频块 →
 *   大于均值置 1 → 64-bit hash。
 *
 * 性能优化：
 * - DCT 改为**可分离的两次 1D 变换**（O(N⁴) → O(N³)），且只计算最终需要的左上 8×8，
 *   配合**预计算的 cos 查找表**消除内层 `cos()` 调用。与原始 2D DCT 数学等价
 *   （省略的只是对「是否大于均值」无影响的公共正比例因子）。
 * - 暴露 [xxHashOf] / [decodeScaledBytes] / [computePHashFromBitmap]，使分析阶段
 *   能「一次读取字节 → 同时算 xxHash + pHash + 质量」，把每图 3 次文件读取降到 1 次。
 */
object HashEngine {

    private val xxh3 = LongHashFunction.xx3()

    /** 预计算 cosTable[u][i] = cos((2i+1)·u·π / 2N)，N = [PHASH_SCALE] */
    private val cosTable: Array<DoubleArray> = Array(PHASH_SCALE) { u ->
        DoubleArray(PHASH_SCALE) { i ->
            cos((2.0 * i + 1.0) * u * PI / (2.0 * PHASH_SCALE))
        }
    }

    // ---- xxHash ----

    /** 对字节内容计算 xxh3 64-bit，返回 16 进制字符串（小写，定长 16） */
    fun xxHashOf(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        val h = xxh3.hashBytes(bytes)
        return h.toULong().toString(16).padStart(16, '0')
    }

    /** 对文件内容计算 xxHash（便捷方法；分析阶段更推荐用 [readImageBytes] + [xxHashOf] 复用字节） */
    suspend fun computeXxHash(provider: FileAccessProvider, path: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                provider.readFile(path).use { it.readBytesCapped(MAX_HASH_BYTES) }
            }.getOrNull()?.let { xxHashOf(it) }
        }

    // ---- pHash ----

    /** 便捷方法：读流 + 解码 + 计算 pHash，失败返回 null */
    suspend fun computePHash(provider: FileAccessProvider, path: String): Long? =
        withContext(Dispatchers.IO) {
            try {
                val bytes = provider.readFile(path).use { it.readBytesCapped(MAX_DECODE_BYTES) }
                decodeScaledBytes(bytes, MAX_PHASH_SIDE)?.let { bmp ->
                    val h = computePHashFromBitmap(bmp)
                    bmp.recycle()
                    h
                }
            } catch (t: Throwable) {
                Timber.w(t, "pHash failed for $path")
                null
            }
        }

    /**
     * 从 Bitmap 计算 pHash。
     * 用可分离 1D DCT 只算左上 8×8 低频块，配合 [cosTable] 查表，避免 O(N⁴) 与重复 cos 计算。
     */
    fun computePHashFromBitmap(bitmap: Bitmap): Long {
        val n = PHASH_SCALE
        val low = PHASH_LOW

        // Step 1: 缩放到 32×32 并取灰度
        val scaled = Bitmap.createScaledBitmap(bitmap, n, n, true)
        val pixels = IntArray(n * n)
        scaled.getPixels(pixels, 0, n, 0, 0, n, n)
        if (scaled !== bitmap) scaled.recycle()

        val gray = Array(n) { y ->
            DoubleArray(n) { x ->
                val p = pixels[y * n + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                0.299 * r + 0.587 * g + 0.114 * b
            }
        }

        // Step 2: 行方向 1D DCT，只保留前 low 个频率 → rowT[i][v]
        val rowT = Array(n) { DoubleArray(low) }
        for (i in 0 until n) {
            val row = gray[i]
            for (v in 0 until low) {
                val cosV = cosTable[v]
                var s = 0.0
                for (x in 0 until n) s += row[x] * cosV[x]
                rowT[i][v] = s
            }
        }

        // Step 3: 列方向 1D DCT，得到左上 8×8 块（含 DCT-II 的 c(u)·c(v) 因子，省略对比较无影响的公共系数）
        val block = Array(low) { DoubleArray(low) }
        for (u in 0 until low) {
            val cosU = cosTable[u]
            val cu = if (u == 0) INV_SQRT2 else 1.0
            for (v in 0 until low) {
                val cv = if (v == 0) INV_SQRT2 else 1.0
                var s = 0.0
                for (y in 0 until n) s += cosU[y] * rowT[y][v]
                block[u][v] = cu * cv * s
            }
        }

        // Step 4: 阈值化（用除 DC 分量外的均值）
        var sum = 0.0
        for (y in 0 until low) for (x in 0 until low) {
            if (x == 0 && y == 0) continue
            sum += block[y][x]
        }
        val mean = sum / (low * low - 1)
        var hash = 0L
        var bit = 0
        for (y in 0 until low) for (x in 0 until low) {
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

    // ---- blockHash（分块指纹，视觉去重精筛）----

    /**
     * 从 Bitmap 计算 256bit 分块指纹：缩放到 (BLOCK_COLS × BLOCK_ROWS) 灰度后水平相邻比较（16×16 dHash）。
     * 供分析阶段与 pHash 共用已解码 bitmap，零额外解码；要求「局部逐格一致」，是截图去重的精筛主力。
     */
    fun computeBlockHashFromBitmap(bitmap: Bitmap): ByteArray {
        val scaled = Bitmap.createScaledBitmap(bitmap, BLOCK_COLS, BLOCK_ROWS, true)
        val pixels = IntArray(BLOCK_COLS * BLOCK_ROWS)
        scaled.getPixels(pixels, 0, BLOCK_COLS, 0, 0, BLOCK_COLS, BLOCK_ROWS)
        if (scaled !== bitmap) scaled.recycle()
        val gray = IntArray(pixels.size) { i ->
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            (r * 77 + g * 150 + b * 29) shr 8 // 整数 luma，与 QualityAnalyzer 一致
        }
        return blockHashFromGray(gray, BLOCK_COLS, BLOCK_ROWS)
    }

    /** 从 (cols × rows) 灰度网格用「水平相邻比较」生成分块指纹（(cols-1)*rows bit，按 byte 打包）。 */
    fun blockHashFromGray(gray: IntArray, cols: Int, rows: Int): ByteArray {
        val out = ByteArray(((cols - 1) * rows + 7) / 8)
        var idx = 0
        for (y in 0 until rows) {
            val base = y * cols
            for (x in 0 until cols - 1) {
                if (gray[base + x] < gray[base + x + 1]) {
                    out[idx ushr 3] = (out[idx ushr 3].toInt() or (1 shl (idx and 7))).toByte()
                }
                idx++
            }
        }
        return out
    }

    /**
     * 从 Bitmap 计算 32 字节特征网格：前 16 字节 = 4×4 亮度网格，后 16 字节 = 4×4 边缘密度网格。
     * 与 pHash/blockHash 共用已解码 bitmap，仅多一次 32×32 缩放，开销极小。
     */
    fun computeFeatureGridFromBitmap(bitmap: Bitmap): ByteArray {
        val side = FEATURE_SIDE
        val scaled = Bitmap.createScaledBitmap(bitmap, side, side, true)
        val pixels = IntArray(side * side)
        scaled.getPixels(pixels, 0, side, 0, 0, side, side)
        if (scaled !== bitmap) scaled.recycle()
        val gray = IntArray(pixels.size) { i ->
            val p = pixels[i]
            ((p shr 16 and 0xFF) * 77 + (p shr 8 and 0xFF) * 150 + (p and 0xFF) * 29) shr 8
        }
        return lumaGridFromGray(gray, side, FEATURE_GRID_N) + edgeGridFromGray(gray, side, FEATURE_GRID_N)
    }

    /** 亮度网格：srcSide×srcSide 灰度按 gridN×gridN 分区，每区**均值**（0-255）。保留绝对明暗，区分深/浅背景。 */
    fun lumaGridFromGray(gray: IntArray, srcSide: Int, gridN: Int): ByteArray {
        val out = ByteArray(gridN * gridN)
        val cell = srcSide / gridN
        if (cell <= 0) return out
        for (gy in 0 until gridN) {
            for (gx in 0 until gridN) {
                var sum = 0L
                var cnt = 0
                for (y in gy * cell until (gy + 1) * cell) {
                    val base = y * srcSide
                    for (x in gx * cell until (gx + 1) * cell) {
                        sum += gray[base + x]
                        cnt++
                    }
                }
                out[gy * gridN + gx] = (if (cnt > 0) (sum / cnt) else 0L).toInt().coerceIn(0, 255).toByte()
            }
        }
        return out
    }

    /** 边缘密度网格：每区内梯度幅值（与右 / 下邻居差）均值（0-255）。捕捉文字密集度 / 图表 vs 文字的版式差异。 */
    fun edgeGridFromGray(gray: IntArray, srcSide: Int, gridN: Int): ByteArray {
        val out = ByteArray(gridN * gridN)
        val cell = srcSide / gridN
        if (cell <= 0) return out
        for (gy in 0 until gridN) {
            for (gx in 0 until gridN) {
                var sum = 0L
                var cnt = 0
                for (y in gy * cell until (gy + 1) * cell) {
                    val base = y * srcSide
                    for (x in gx * cell until (gx + 1) * cell) {
                        val g = gray[base + x]
                        val dx = if (x + 1 < srcSide) kotlin.math.abs(gray[base + x + 1] - g) else 0
                        val dy = if (y + 1 < srcSide) kotlin.math.abs(gray[(y + 1) * srcSide + x] - g) else 0
                        sum += (dx + dy)
                        cnt++
                    }
                }
                out[gy * gridN + gx] = (if (cnt > 0) (sum / cnt) else 0L).toInt().coerceIn(0, 255).toByte()
            }
        }
        return out
    }

    // ---- 共享 IO / 解码 ----

    /** 读取图片字节（带上限），供「一次读取多结果」复用。失败或空返回 null。 */
    suspend fun readImageBytes(provider: FileAccessProvider, path: String): ByteArray? =
        withContext(Dispatchers.IO) {
            runCatching {
                provider.readFile(path).use { it.readBytesCapped(MAX_DECODE_BYTES) }
            }.getOrNull()?.takeIf { it.isNotEmpty() }
        }

    /** 解码字节到不超过 maxSide 的尺寸（节省内存）。损坏 / 非图片返回 null。 */
    fun decodeScaledBytes(bytes: ByteArray, maxSide: Int): Bitmap? {
        if (bytes.isEmpty()) return null
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null

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
     * QQ chatpic 单图普遍 < 5MB，上限做最后兜底。
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
    private const val BLOCK_COLS = 17    // 分块指纹：水平 17 列（16 次相邻比较）
    private const val BLOCK_ROWS = 16    // 分块指纹：16 行 → 16×16 = 256 bit
    private const val FEATURE_SIDE = 32  // 亮度/边缘网格的源灰度边长
    private const val FEATURE_GRID_N = 4 // 4×4 网格 → 每特征 16 字节
    private val INV_SQRT2 = 1.0 / sqrt(2.0)
    private const val MAX_HASH_BYTES = 32 * 1024 * 1024   // xxHash 最多 32MB（限制罕见的超大图）
    private const val MAX_DECODE_BYTES = 32 * 1024 * 1024
}
