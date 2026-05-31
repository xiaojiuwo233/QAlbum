package com.xjw.qalbum.service.cleanup

import com.xjw.qalbum.access.AccessManager
import com.xjw.qalbum.data.db.dao.ImageDao
import com.xjw.qalbum.data.db.entity.ImageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * chatthumb 缩略图。
 *
 * 缩略图不进入图库、OCR、AI 或重复分析，只在这里作为可清理垃圾展示和永久删除。
 */
class ThumbnailTrashManager(
    private val accessManager: AccessManager,
    private val imageDao: ImageDao,
) {

    fun observeSummary(): Flow<ImageDao.ThumbnailTrashSummary> =
        imageDao.observeThumbnailTrashSummary()

    fun observeItems(): Flow<List<ImageEntity>> =
        imageDao.observeThumbnailTrash()

    suspend fun purge(imageId: Long): Boolean = withContext(Dispatchers.IO) {
        val image = imageDao.getById(imageId) ?: return@withContext false
        if (image.sourceType != "chatthumb" || image.status != "thumbnail_trash") {
            return@withContext false
        }
        val provider = accessManager.current()
        if (!provider.isAvailable()) return@withContext false

        val deleted = runCatching { provider.deleteFile(image.path) }.getOrDefault(false)
        if (deleted) {
            imageDao.updateStatus(image.id, "deleted")
        } else {
            Timber.w("ThumbnailTrash purge failed for ${image.path}")
        }
        deleted
    }

    suspend fun purgeAll(): Int = withContext(Dispatchers.IO) {
        val items = imageDao.listThumbnailTrash()
        if (items.isEmpty()) return@withContext 0

        val provider = accessManager.current()
        if (!provider.isAvailable()) return@withContext 0

        val deletedIds = mutableListOf<Long>()
        for (image in items) {
            val deleted = runCatching { provider.deleteFile(image.path) }.getOrDefault(false)
            if (deleted) {
                deletedIds.add(image.id)
            } else {
                Timber.w("ThumbnailTrash purgeAll failed for ${image.path}")
            }
        }
        if (deletedIds.isNotEmpty()) {
            imageDao.markDeleted(deletedIds)
        }
        deletedIds.size
    }
}
