package com.xjw.qalbum.service.cleanup

import com.xjw.qalbum.access.AccessManager
import com.xjw.qalbum.data.db.dao.ImageDao
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.fileops.FileOpsClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * chatthumb 缩略图。
 *
 * 缩略图不进入图库、OCR、AI 或重复分析，只在这里作为可清理垃圾展示和永久删除。
 *
 * 删除提速：早期实现逐文件 `provider.deleteFile`，Shizuku 下每张都要反射起一个
 * `sh -c rm` 子进程（约 60-80ms/张，4w 张 ≈ 45min）。现在统一走 [purgeInternal]：
 * 大批量复用扫描那套 AIDL 特权服务（[FileOpsClient]）做进程内原生批删，跨进程只走
 * binder；小批量或绑定失败时落到 provider 的 shell 批删（一批一进程）。
 */
class ThumbnailTrashManager(
    private val accessManager: AccessManager,
    private val imageDao: ImageDao,
    private val fileOpsClient: FileOpsClient,
) {

    fun observeSummary(): Flow<ImageDao.ThumbnailTrashSummary> =
        imageDao.observeThumbnailTrashSummary()

    fun observeItems(): Flow<List<ImageEntity>> =
        imageDao.observeThumbnailTrash()

    /** 永久删除选中的缩略图（多选）。返回实际成功删除的张数。 */
    suspend fun purge(imageIds: List<Long>): Int = withContext(Dispatchers.IO) {
        if (imageIds.isEmpty()) return@withContext 0
        // 以 DB 为准筛出仍是 thumbnail_trash 的 chatthumb，避免误标 deleted 污染统计
        val items = imageIds.chunked(FILE_CHUNK).flatMap { imageDao.getThumbnailTrashByIds(it) }
        purgeInternal(items) { _, _ -> }
    }

    /**
     * 一键清理全部缩略图。
     *
     * 图片可能多达数万张，故支持 [onProgress] 上报进度；DB 标记删除分块写入，
     * 中途中断时已删除的部分也能落库、不丢进度。
     *
     * @param onProgress (已处理张数, 总张数) 回调
     * @return 实际成功删除的张数
     */
    suspend fun purgeAll(onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): Int =
        withContext(Dispatchers.IO) {
            purgeInternal(imageDao.listThumbnailTrash(), onProgress)
        }

    /**
     * 批量永久删除 [items] 对应的磁盘文件，并把删成功的行标记为 deleted。
     *
     * 选路策略：达到 [AIDL_MIN_BATCH] 才绑定 AIDL 特权服务（绑定本身有约秒级启动成本，
     * 小批量直接 shell 批删反而更快）；绑定失败或中途 binder 异常都自动落到 shell 批删。
     * 每处理一块就 [ImageDao.markDeleted] 一次，保证中断也能落库。
     */
    private suspend fun purgeInternal(
        items: List<ImageEntity>,
        onProgress: (done: Int, total: Int) -> Unit,
    ): Int {
        val total = items.size
        if (total == 0) {
            onProgress(0, 0)
            return 0
        }
        val provider = accessManager.current()
        if (!provider.isAvailable()) return 0

        onProgress(0, total)
        val bound = if (total >= AIDL_MIN_BATCH) fileOpsClient.bind(provider.getAccessMode()) else null
        var useAidl = bound != null
        var done = 0
        var deleted = 0
        try {
            for (chunk in items.chunked(FILE_CHUNK)) {
                val paths = chunk.map { it.path }
                val results: BooleanArray = if (useAidl) {
                    runCatching { bound!!.ops.deleteFiles(paths.toTypedArray()) }
                        .getOrElse {
                            // binder 死亡等异常：本块及后续都落到 shell
                            Timber.w(it, "AIDL deleteFiles failed, fallback to shell")
                            useAidl = false
                            provider.deleteFiles(paths)
                        }
                } else {
                    provider.deleteFiles(paths)
                }

                val okIds = ArrayList<Long>(chunk.size)
                for (i in chunk.indices) {
                    if (results.getOrNull(i) == true) {
                        okIds.add(chunk[i].id)
                    } else {
                        Timber.w("ThumbnailTrash purge failed for ${chunk[i].path}")
                    }
                }
                if (okIds.isNotEmpty()) {
                    imageDao.markDeleted(okIds, System.currentTimeMillis())
                    deleted += okIds.size
                }
                done += chunk.size
                onProgress(done, total)
            }
        } finally {
            bound?.close()
        }
        Timber.i("ThumbnailTrash purge: deleted=$deleted/$total aidl=${bound != null}")
        return deleted
    }

    companion object {
        /** 每批删除/标记的张数：AIDL binder 单次事务与 DB markDeleted 共用（< SQLite 变量上限） */
        private const val FILE_CHUNK = 500

        /** 达到该量级才值得绑定 AIDL 特权服务（绑定有约秒级启动成本） */
        private const val AIDL_MIN_BATCH = 2000
    }
}
