package com.xjw.qalbum.service.cleanup

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.xjw.qalbum.access.AccessManager
import com.xjw.qalbum.data.db.dao.CleanupDao
import com.xjw.qalbum.data.db.dao.ImageDao
import com.xjw.qalbum.data.db.entity.QuarantineEntity
import com.xjw.qalbum.data.preferences.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * 回收站
 *
 * 删除操作的实际语义：把文件从 chatpic 移动到 App 私有目录 `filesDir/quarantine/`，
 * 并保留一份缩略图（让用户事后查看回收站时不需要再次读 chatpic）。
 *
 * 过期清理：默认 30 天后真正删除（[SettingsRepository.quarantineRetentionDays]）。
 */
class QuarantineManager(
    private val context: Context,
    private val accessManager: AccessManager,
    private val imageDao: ImageDao,
    private val cleanupDao: CleanupDao,
    private val settings: SettingsRepository,
) {

    /** 回收站根目录 */
    private val rootDir: File by lazy {
        File(context.filesDir, "quarantine").apply { if (!exists()) mkdirs() }
    }

    /** 缩略图目录 */
    private val thumbDir: File by lazy {
        File(rootDir, "thumb").apply { if (!exists()) mkdirs() }
    }

    /** 把指定图片移到回收站。 */
    suspend fun quarantine(imageId: Long, reason: String): Boolean = withContext(Dispatchers.IO) {
        val image = imageDao.getById(imageId) ?: return@withContext false
        val provider = accessManager.current()
        if (!provider.isAvailable()) return@withContext false

        val thumbPath = runCatching { generateThumbnail(provider, image.path, image.id) }
            .getOrNull()

        val retentionDays = settings.quarantineRetentionDays.first()
        val expiresAt = System.currentTimeMillis() + retentionDays * ONE_DAY_MS

        val dest = File(rootDir, "${image.id}_${image.sourceType}_${image.filename}")
        val moved = provider.moveFile(image.path, dest.absolutePath)
        if (!moved) {
            Timber.w("Quarantine moveFile failed for ${image.path}")
            try {
                provider.readFile(image.path).use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                provider.deleteFile(image.path)
            } catch (t: Throwable) {
                Timber.e(t, "Quarantine fallback copy failed for ${image.path}")
                return@withContext false
            }
        }
        imageDao.updateStatus(image.id, "quarantined")

        cleanupDao.insertQuarantine(
            QuarantineEntity(
                imageId = imageId,
                originalPath = image.path,
                quarantinePath = rootDir.absolutePath,
                thumbnailPath = thumbPath,
                reason = reason,
                ocrSummary = null,    // Phase 3 OCR 接入后填充
                size = image.size,
                quarantinedAt = System.currentTimeMillis(),
                expiresAt = expiresAt,
            )
        )
        Timber.i("Quarantine: $imageId moved, size=${image.size}")
        true
    }

    /** 直接从原位置永久删除，不进入回收站。 */
    suspend fun deletePermanently(imageId: Long): Boolean = withContext(Dispatchers.IO) {
        val image = imageDao.getById(imageId) ?: return@withContext false
        val provider = accessManager.current()
        if (!provider.isAvailable()) return@withContext false

        val deleted = runCatching { provider.deleteFile(image.path) }.getOrDefault(false)
        if (deleted) {
            imageDao.markDeleted(listOf(image.id), System.currentTimeMillis())
        } else {
            Timber.w("Permanent delete failed for ${image.path}")
        }
        deleted
    }

    /** 从回收站恢复。 */
    suspend fun restore(quarantineId: Long): Boolean = withContext(Dispatchers.IO) {
        val record = cleanupDao.getQuarantineById(quarantineId) ?: return@withContext false
        val provider = accessManager.current()
        if (!provider.isAvailable()) return@withContext false

        val image = imageDao.getById(record.imageId) ?: return@withContext false
        val src = File(rootDir, "${image.id}_${image.sourceType}_${image.filename}")
        if (!src.exists()) return@withContext false
        val ok = provider.moveFile(src.absolutePath, image.path)
        if (ok) {
            imageDao.updateStatus(image.id, "active")
        }
        record.thumbnailPath?.let { File(it).delete() }
        if (ok) cleanupDao.deleteQuarantine(quarantineId)
        ok
    }

    /** 立即从回收站永久删除（不可恢复） */
    suspend fun purge(quarantineId: Long): Boolean = withContext(Dispatchers.IO) {
        val record = cleanupDao.getQuarantineById(quarantineId) ?: return@withContext false
        val image = imageDao.getById(record.imageId) ?: return@withContext false
        val src = File(rootDir, "${image.id}_${image.sourceType}_${image.filename}")
        src.delete()
        imageDao.markDeleted(listOf(image.id), System.currentTimeMillis())
        record.thumbnailPath?.let { File(it).delete() }
        cleanupDao.deleteQuarantine(quarantineId)
        true
    }

    /** 缩略图生成：解码后压缩到 240px，保存到 thumb 子目录 */
    private suspend fun generateThumbnail(
        provider: com.xjw.qalbum.access.FileAccessProvider,
        path: String,
        id: Long,
    ): String? = withContext(Dispatchers.IO) {
        try {
            val bytes = provider.readFile(path).use { it.readBytes() }
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            if (opts.outWidth <= 0) return@withContext null

            var sample = 1
            var w = opts.outWidth
            var h = opts.outHeight
            while (w > 480 || h > 480) {
                sample *= 2
                w /= 2; h /= 2
            }
            val opts2 = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts2) ?: return@withContext null
            val dest = File(thumbDir, "$id.jpg")
            dest.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 70, out)
            }
            bitmap.recycle()
            dest.absolutePath
        } catch (t: Throwable) {
            Timber.w(t, "Thumbnail generation failed for $path")
            null
        }
    }

    companion object {
        private const val ONE_DAY_MS = 86_400_000L
    }
}
