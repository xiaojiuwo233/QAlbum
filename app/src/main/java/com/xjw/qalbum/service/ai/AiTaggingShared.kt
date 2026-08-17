package com.xjw.qalbum.service.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.xjw.qalbum.access.AccessException
import com.xjw.qalbum.access.ImageBytesReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayOutputStream

/** 多个 AI 服务共享的小工具：图片编码 */
object AiTaggingShared {

    /** 单图读取字节上限（兜底罕见超大图，防止无界 readBytes 拉爆堆）。 */
    const val MAX_IMAGE_BYTES = 32 * 1024 * 1024

    /**
     * 读图 → 缩放到 ≤ [maxSide] → JPEG [quality]% → Base64。
     *
     * 经 [reader] 读取（AI 逐图读统一走 PrivilegedImageReader 原生 fd，禁止 provider.readFile
     * 每图 fork 进程）。返回 null 仅表示该图本身不可读/解码失败；读取基础设施失败
     * （[AccessException]）与取消原样上抛，调用方不得把基础设施失败当作图片损坏。
     */
    suspend fun encodeImageBase64(
        reader: ImageBytesReader,
        path: String,
        maxSide: Int = 512,
        quality: Int = 60,
    ): String? = withContext(Dispatchers.IO) {
        try {
            val bytes = reader.read(path, MAX_IMAGE_BYTES) ?: return@withContext null
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            if (opts.outWidth <= 0) return@withContext null

            var sample = 1
            var w = opts.outWidth
            var h = opts.outHeight
            while (w > maxSide || h > maxSide) {
                sample *= 2
                w /= 2; h /= 2
            }
            val bitmap = BitmapFactory.decodeByteArray(
                bytes, 0, bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample },
            ) ?: return@withContext null

            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            bitmap.recycle()
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (ce: CancellationException) {
            throw ce
        } catch (ae: AccessException) {
            throw ae
        } catch (t: Throwable) {
            Timber.w(t, "encodeImageBase64 failed for $path")
            null
        }
    }
}
