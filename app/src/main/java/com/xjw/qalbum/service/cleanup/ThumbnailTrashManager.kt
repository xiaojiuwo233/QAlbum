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

    /**
     * 一键清理全部缩略图。
     *
     * 图片可能多达数万张：逐文件删除耗时较长，故支持 [onProgress] 上报进度；
     * 且 DB 标记删除按 [DB_CHUNK] 分块写入，避免一次性把上万个 id 塞进
     * `IN (:ids)` 触发 SQLite 变量上限（之前 4w 张时正是因此清理失败）。
     * 分块写入也让中途中断时已删除的部分能落库、不丢进度。
     *
     * @param onProgress (已处理张数, 总张数) 回调
     * @return 实际成功删除的张数
     */
    suspend fun purgeAll(onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): Int =
        withContext(Dispatchers.IO) {
            val items = imageDao.listThumbnailTrash()
            if (items.isEmpty()) {
                onProgress(0, 0)
                return@withContext 0
            }

            val provider = accessManager.current()
            if (!provider.isAvailable()) return@withContext 0

            val total = items.size
            var done = 0
            var deleted = 0
            val pending = ArrayList<Long>(DB_CHUNK)
            onProgress(0, total)
            for (image in items) {
                val ok = runCatching { provider.deleteFile(image.path) }.getOrDefault(false)
                if (ok) {
                    pending.add(image.id)
                    deleted++
                } else {
                    Timber.w("ThumbnailTrash purgeAll failed for ${image.path}")
                }
                done++
                if (pending.size >= DB_CHUNK) {
                    imageDao.markDeleted(pending)
                    pending.clear()
                }
                if (done % PROGRESS_STEP == 0 || done == total) onProgress(done, total)
            }
            if (pending.isNotEmpty()) imageDao.markDeleted(pending)
            Timber.i("ThumbnailTrash purgeAll: deleted $deleted/$total")
            deleted
        }

    companion object {
        /** 单次 markDeleted 的 id 上限，避免触及 SQLite 变量限制 */
        private const val DB_CHUNK = 500

        /** 进度上报步长 */
        private const val PROGRESS_STEP = 50
    }
}
