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
 * 保险箱
 *
 * 与回收站的区别：
 * - 保险箱保存的是"用户主动想保留"的图片
 * - 内容会**复制**到 App 私有目录 `filesDir/vault/{category}/`（不动 chatpic 原文件）
 * - 同时把 `is_protected = true`，让评分引擎永远不会推荐它被删除
 * - 提供"导出到系统相册"，用户随时把图片永久挪出 QQ 缓存
 */
class VaultManager(
    private val context: Context,
    private val accessManager: AccessManager,
    private val imageDao: ImageDao,
) {

    private val rootDir: File by lazy {
        File(context.filesDir, "vault").apply { if (!exists()) mkdirs() }
    }

    private val externalViewDir: File by lazy {
        File(context.cacheDir, "external_view").apply { if (!exists()) mkdirs() }
    }

    /** 将图片复制到保险箱，并设置 is_protected */
    suspend fun addToVault(imageId: Long, category: String = "default"): Boolean =
        withContext(Dispatchers.IO) {
            val image = imageDao.getById(imageId) ?: return@withContext false
            val provider = accessManager.current()
            if (!provider.isAvailable()) return@withContext false

            val categoryDir = File(rootDir, category.ifBlank { "default" })
                .apply { if (!exists()) mkdirs() }
            val dest = File(categoryDir, "${image.id}_${image.filename}")

            try {
                provider.readFile(image.path).use { input ->
                    dest.outputStream().use { out -> input.copyTo(out) }
                }
                imageDao.setProtected(image.id, true)
                Timber.i("VaultManager: added $imageId to vault ($category)")
                true
            } catch (t: Throwable) {
                Timber.e(t, "VaultManager.addToVault failed for $imageId")
                false
            }
        }

    /** 移除保险箱标记（不删除保险箱中的副本，留给用户自行清理） */
    suspend fun removeProtection(imageId: Long) {
        imageDao.setProtected(imageId, false)
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
            Timber.i("VaultManager: exported $imageId to gallery")
            true
        } catch (t: Throwable) {
            Timber.e(t, "VaultManager.exportToGallery failed for $imageId")
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
            Timber.e(t, "VaultManager.prepareExternalView failed for $imageId")
            null
        }
    }
}

data class ExternalViewTarget(
    val uri: Uri,
    val mimeType: String,
)
