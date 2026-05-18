package com.xjw.qalbum.service.indexer

import android.graphics.BitmapFactory
import com.xjw.qalbum.access.FileAccessProvider
import java.io.InputStream

/**
 * 读取图片宽高（不解码像素，仅读 header）
 *
 * 用 [BitmapFactory.Options.inJustDecodeBounds] = true，BitmapFactory 只解析 metadata 不分配像素，
 * 对大文件友好，10 万张耗时约 1~2 分钟。
 */
object ImageDimensionReader {

    data class Dimension(val width: Int, val height: Int)

    /** 通过 FileAccessProvider 拿到流并解析宽高 */
    suspend fun read(provider: FileAccessProvider, path: String): Dimension? {
        return try {
            provider.readFile(path).use { stream ->
                readFromStream(stream)
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun readFromStream(stream: InputStream): Dimension? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(stream, null, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        return Dimension(options.outWidth, options.outHeight)
    }
}
