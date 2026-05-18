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
 * 隔离区（回收站）
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

    /** 隔离区根目录 */
    private val rootDir: File by lazy {
        File(context.filesDir, "quarantine").apply { if (!exists()) mkdirs() }
    }

    /** 缩略图目录 */
    private val thumbDir: File by lazy {
        File(rootDir, "thumb").apply { if (!exists()) mkdirs() }
    }

    /**
     * 把指定图片移到隔离区。
     * 若该图属于变体组，会把整组所有变体一起处理（保持"逻辑图片"一致性）。
     */
    suspend fun quarantine(imageId: Long, reason: String): Boolean = withContext(Dispatchers.IO) {
        val image = imageDao.getById(imageId) ?: return@withContext false
        val provider = accessManager.current()
        if (!provider.isAvailable()) return@withContext false

        // 同变体组所有成员一起处理
        val targets = image.variantGroup
            ?.let { imageDao.getByVariantGroup(it) }
            ?: listOf(image)

        // 缩略图取主图
        val primary = targets.firstOrNull { it.isVariantPrimary } ?: image
        val thumbPath = runCatching { generateThumbnail(provider, primary.path, primary.id) }
            .getOrNull()

        val retentionDays = settings.quarantineRetentionDays.first()
        val expiresAt = System.currentTimeMillis() + retentionDays * ONE_DAY_MS
        var totalSize = 0L
        val originalPaths = mutableListOf<String>()

        for (variant in targets) {
            val dest = File(rootDir, "${variant.id}_${variant.sourceType}_${variant.filename}")
            val moved = provider.moveFile(variant.path, dest.absolutePath)
            if (!moved) {
                Timber.w("Quarantine moveFile failed for ${variant.path}")
                // 尝试"复制 + 删源"的回退策略
                try {
                    provider.readFile(variant.path).use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    }
                    provider.deleteFile(variant.path)
                } catch (t: Throwable) {
                    Timber.e(t, "Quarantine fallback copy failed for ${variant.path}")
                    continue
                }
            }
            imageDao.updateStatus(variant.id, "quarantined")
            originalPaths.add(variant.path)
            totalSize += variant.size
        }

        cleanupDao.insertQuarantine(
            QuarantineEntity(
                imageId = imageId,
                originalPath = originalPaths.joinToString("|"),
                quarantinePath = rootDir.absolutePath,
                thumbnailPath = thumbPath,
                reason = reason,
                ocrSummary = null,    // Phase 3 OCR 接入后填充
                size = totalSize,
                quarantinedAt = System.currentTimeMillis(),
                expiresAt = expiresAt,
            )
        )
        Timber.i("Quarantine: $imageId moved, ${targets.size} variants, size=$totalSize")
        true
    }

    /** 从隔离区恢复（把所有变体移回原 chatpic 路径） */
    suspend fun restore(quarantineId: Long): Boolean = withContext(Dispatchers.IO) {
        val record = cleanupDao.getQuarantineById(quarantineId) ?: return@withContext false
        val provider = accessManager.current()
        if (!provider.isAvailable()) return@withContext false

        val image = imageDao.getById(record.imageId) ?: return@withContext false
        val targets = image.variantGroup
            ?.let { imageDao.getByVariantGroup(it) }
            ?: listOf(image)

        for (variant in targets) {
            val src = File(rootDir, "${variant.id}_${variant.sourceType}_${variant.filename}")
            if (!src.exists()) continue
            val ok = provider.moveFile(src.absolutePath, variant.path)
            if (ok) {
                imageDao.updateStatus(variant.id, "active")
            }
        }
        record.thumbnailPath?.let { File(it).delete() }
        cleanupDao.deleteQuarantine(quarantineId)
        true
    }

    /** 立即从隔离区永久删除（不可恢复） */
    suspend fun purge(quarantineId: Long): Boolean = withContext(Dispatchers.IO) {
        val record = cleanupDao.getQuarantineById(quarantineId) ?: return@withContext false
        val image = imageDao.getById(record.imageId) ?: return@withContext false
        val targets = image.variantGroup
            ?.let { imageDao.getByVariantGroup(it) }
            ?: listOf(image)
        for (variant in targets) {
            val src = File(rootDir, "${variant.id}_${variant.sourceType}_${variant.filename}")
            src.delete()
            imageDao.updateStatus(variant.id, "deleted")
        }
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
