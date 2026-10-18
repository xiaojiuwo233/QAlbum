package com.xjw.qalbum.service.video

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import timber.log.Timber
import java.io.FileDescriptor

/** 读视频的时长、尺寸与封面帧（MediaMetadataRetriever，读特权进程传来的 fd） */
object VideoProbe {

    data class Meta(val durationMs: Long, val width: Int, val height: Int)

    /** 时长与按显示方向的宽高；读不出来返回 null */
    fun meta(fd: FileDescriptor): Meta? = withRetriever(fd) { retriever ->
        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        if (rotation % 180 != 0) Meta(duration, height, width) else Meta(duration, width, height)
    }

    /** 封面：前三分之一处（最多第 1 秒）最近的关键帧，缩到长边不超过 [maxPx] */
    fun frame(fd: FileDescriptor, maxPx: Int): Bitmap? = withRetriever(fd) { retriever ->
        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        val timeUs = (duration * 1000 / 3).coerceIn(0L, 1_000_000L)
        val option = MediaMetadataRetriever.OPTION_CLOSEST_SYNC
        val frame = if (Build.VERSION.SDK_INT >= 27) {
            retriever.getScaledFrameAtTime(timeUs, option, maxPx, maxPx) ?: retriever.getScaledFrameAtTime(-1, option, maxPx, maxPx)
        } else {
            (retriever.getFrameAtTime(timeUs, option) ?: retriever.getFrameAtTime(-1, option))?.let { scaleDown(it, maxPx) }
        }
        frame
    }

    private fun scaleDown(bitmap: Bitmap, maxPx: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxPx) return bitmap
        val scale = maxPx.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    private inline fun <T> withRetriever(fd: FileDescriptor, block: (MediaMetadataRetriever) -> T?): T? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(fd)
            block(retriever)
        } catch (e: Exception) {
            Timber.w(e, "视频读取失败")
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
}
