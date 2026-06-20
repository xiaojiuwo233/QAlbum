package com.xjw.qalbum.service.cleanup

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.xjw.qalbum.access.AccessManager
import com.xjw.qalbum.data.db.dao.ImageDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * 图片文件操作。
 *
 * 负责把缓存图片导出到系统相册，或生成一个可交给第三方应用预览的临时文件。
 */
class ImageFileManager(
    private val context: Context,
    private val accessManager: AccessManager,
    private val imageDao: ImageDao,
) {

    private val externalViewDir: File by lazy {
        File(context.cacheDir, "external_view").apply { if (!exists()) mkdirs() }
    }

    private val shareDir: File by lazy {
        File(context.cacheDir, "share").apply { if (!exists()) mkdirs() }
    }

    /** 将图片导出到系统相册 Pictures/QAlbum/ */
    suspend fun exportToGallery(imageId: Long): Boolean = withContext(Dispatchers.IO) {
        val image = imageDao.getById(imageId) ?: return@withContext false
        val provider = accessManager.current()
        if (!provider.isAvailable()) return@withContext false

        try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, image.filename.ifBlank { "qalbum_${image.id}.jpg" })
                put(MediaStore.Images.Media.MIME_TYPE, image.mime.ifBlank { "image/jpeg" })
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/QAlbum")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val uri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
            ) ?: return@withContext false

            context.contentResolver.openOutputStream(uri)?.use { out ->
                provider.readFile(image.path).use { input ->
                    input.copyTo(out)
                }
            } ?: return@withContext false

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
            }
            Timber.i("ImageFileManager: exported $imageId to gallery")
            true
        } catch (t: Throwable) {
            Timber.e(t, "ImageFileManager.exportToGallery failed for $imageId")
            false
        }
    }

    /** 准备一个可交给第三方 App 打开的临时 content Uri。 */
    suspend fun prepareExternalView(imageId: Long): ExternalViewTarget? = withContext(Dispatchers.IO) {
        val image = imageDao.getById(imageId) ?: return@withContext null
        val provider = accessManager.current()
        if (!provider.isAvailable()) return@withContext null

        try {
            externalViewDir.listFiles()?.forEach { it.delete() }
            val safeName = image.filename.ifBlank { "qalbum_${image.id}.jpg" }
            val dest = File(externalViewDir, "${image.id}_$safeName")
            provider.readFile(image.path).use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                dest,
            )
            ExternalViewTarget(uri = uri, mimeType = image.mime.ifBlank { "image/*" })
        } catch (t: Throwable) {
            Timber.e(t, "ImageFileManager.prepareExternalView failed for $imageId")
            null
        }
    }

    /**
     * 准备一个可分享的临时文件（content Uri）。chatpic 原文件名是无后缀的 `Cache_xxx`，
     * 这里按 MIME 补上正确扩展名（部分 App 必须靠后缀识别图片类型）。
     */
    suspend fun prepareShare(imageId: Long): ExternalViewTarget? = withContext(Dispatchers.IO) {
        val image = imageDao.getById(imageId) ?: return@withContext null
        val provider = accessManager.current()
        if (!provider.isAvailable()) return@withContext null
        try {
            val ext = extensionForMime(image.mime)
            val dest = File(shareDir, "qalbum_${image.id}.$ext")
            provider.readFile(image.path).use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", dest)
            ExternalViewTarget(uri = uri, mimeType = image.mime.ifBlank { "image/*" })
        } catch (t: Throwable) {
            Timber.e(t, "ImageFileManager.prepareShare failed for $imageId")
            null
        }
    }

    private fun extensionForMime(mime: String): String = when (mime.lowercase()) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/heic" -> "heic"
        "image/heif" -> "heif"
        "image/bmp" -> "bmp"
        else -> "jpg"
    }
}

data class ExternalViewTarget(
    val uri: Uri,
    val mimeType: String,
)
