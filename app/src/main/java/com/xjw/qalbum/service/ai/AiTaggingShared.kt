package com.xjw.qalbum.service.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.xjw.qalbum.access.FileAccessProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayOutputStream

/** 多个 AI 服务共享的小工具：图片编码 */
object AiTaggingShared {

    suspend fun encodeImageBase64(
        provider: FileAccessProvider,
        path: String,
        maxSide: Int = 512,
        quality: Int = 60,
    ): String? = withContext(Dispatchers.IO) {
        try {
            val bytes = provider.readFile(path).use { it.readBytes() }
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
        } catch (t: Throwable) {
            Timber.w(t, "encodeImageBase64 failed for $path")
            null
        }
    }
}
