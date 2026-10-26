package com.xjw.qalbum.ui.imageloading

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.media.ExifInterface
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import coil3.ImageLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.Closeable
import java.io.File

/**
 * 一张图的分块解码器：按需从原图解出一块（[decode]）。看图放大到整图看不清时用，见 ZoomableImage。
 * 解码串行进行（同一个解码器不能并发解）；用完 [close]。
 */
class ImageTileSource internal constructor(
    private val decoder: BitmapRegionDecoder,
    private val file: ParcelFileDescriptor,
    /** 原始像素的宽高（没按方向转正） */
    val rawSize: IntSize,
    /** 文件里记的 EXIF 方向；整图解码套用了它时分块也要套用 */
    val exifOrientation: Int,
) : Closeable {
    private val lock = Any()
    private var closed = false

    /**
     * 解出转正后图上的 [rect]（按 [orientation] 转正），每 [sample] 个原图像素取一个。
     * 已经关掉、或解码失败时返回 null。
     */
    fun decode(rect: IntRect, sample: Int, orientation: Int): Bitmap? {
        val raw = TileMath.toRaw(rect, orientation, rawSize.width, rawSize.height)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = synchronized(lock) {
            if (closed) return null
            runCatching { decoder.decodeRegion(Rect(raw.left, raw.top, raw.right, raw.bottom), options) }.getOrNull()
        } ?: return null
        val matrix = orientationMatrix(orientation) ?: return bitmap
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, false).also { if (it !== bitmap) bitmap.recycle() }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            decoder.recycle()
        }
        runCatching { file.close() }
    }

    private companion object {
        /** 把原始像素转正的变换（与 [TileMath.toRaw] 对应） */
        fun orientationMatrix(orientation: Int): Matrix? = when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> Matrix().apply { setScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_180 -> Matrix().apply { setRotate(180f) }
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> Matrix().apply { setRotate(180f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSPOSE -> Matrix().apply { setRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> Matrix().apply { setRotate(90f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> Matrix().apply { setRotate(-90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> Matrix().apply { setRotate(-90f) }
            else -> null
        }
    }
}

/**
 * 给看图页打开某张图的分块解码器：图库的图读磁盘缓存里的那份（看图时整图已经读进去了），回收站里 App 自己的文件直接读，
 * 其余经特权进程拿 fd。动图、GIF 和解不了分块的格式返回 null（只显示整图）。
 */
class ImageTileSources internal constructor(
    private val imageLoader: ImageLoader,
    private val fastPath: FdFastPath,
) {
    suspend fun open(model: Any?): ImageTileSource? = withContext(Dispatchers.IO) {
        try {
            val file = when (model) {
                is ImageEntityModel -> {
                    val entity = model.entity
                    if (entity.isAnimated || entity.mime == "image/gif") return@withContext null
                    cachedCopy(entityDiskKey(entity)) ?: fastPath.openRead(entity.path)
                }
                is FileAccessPath -> File(model.path).takeIf { it.canRead() }
                    ?.let { ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY) }
                    ?: fastPath.openRead(model.path)
                else -> null
            } ?: return@withContext null
            open(file)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Timber.w(t, "分块解码打不开")
            null
        }
    }

    /** 磁盘缓存里的那份：打开后就算缓存被清掉，已经打开的文件照样能读 */
    private fun cachedCopy(key: String): ParcelFileDescriptor? {
        val snapshot = imageLoader.diskCache?.openSnapshot(key) ?: return null
        return snapshot.use { ParcelFileDescriptor.open(it.data.toFile(), ParcelFileDescriptor.MODE_READ_ONLY) }
    }

    private fun open(file: ParcelFileDescriptor): ImageTileSource? {
        try {
            val fd = file.fileDescriptor
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFileDescriptor(fd, null, bounds)
            val mime = bounds.outMimeType
            if (mime !in REGION_MIMES) {
                file.close()
                return null
            }
            Os.lseek(fd, 0, OsConstants.SEEK_SET)
            // Coil 整图解码时这几种格式会按 EXIF 转正（其余不转），分块跟它一致
            val orientation = if (mime in EXIF_MIMES) {
                runCatching { ExifInterface(fd).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
                    .getOrDefault(ExifInterface.ORIENTATION_NORMAL).also { Os.lseek(fd, 0, OsConstants.SEEK_SET) }
            } else {
                ExifInterface.ORIENTATION_NORMAL
            }
            @Suppress("DEPRECATION")
            val decoder = if (Build.VERSION.SDK_INT >= 31) BitmapRegionDecoder.newInstance(file) else BitmapRegionDecoder.newInstance(fd, false)
            if (decoder == null) {
                file.close()
                return null
            }
            return ImageTileSource(decoder, file, IntSize(decoder.width, decoder.height), orientation)
        } catch (t: Throwable) {
            runCatching { file.close() }
            throw t
        }
    }

    private companion object {
        val REGION_MIMES = setOf("image/jpeg", "image/png", "image/webp", "image/heic", "image/heif")
        val EXIF_MIMES = setOf("image/jpeg", "image/webp", "image/heic", "image/heif")
    }
}

/** 看图页用来打开分块解码器（没有提供时只显示整图，如界面测试） */
val LocalImageTileSources = staticCompositionLocalOf<ImageTileSources?> { null }
