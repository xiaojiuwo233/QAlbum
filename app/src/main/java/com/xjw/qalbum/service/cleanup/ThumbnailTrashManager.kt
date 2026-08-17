package com.xjw.qalbum.service.cleanup

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.xjw.qalbum.access.AccessManager
import com.xjw.qalbum.access.FileAccessProvider
import com.xjw.qalbum.data.db.dao.ImageDao
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.data.repository.ImageRepository
import com.xjw.qalbum.fileops.BoundFileOps
import com.xjw.qalbum.fileops.FileOpsClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * chatthumb 缩略图。
 *
 * 缩略图不进入图库、OCR、AI 或重复分析，只在这里作为可清理垃圾展示和永久删除。
 *
 * 删除提速：早期实现逐文件 `provider.deleteFile`，Shizuku 下每张都要反射起一个
 * `sh -c rm` 子进程（约 60-80ms/张，4w 张 ≈ 45min）。现在统一走 [purgeChunk]：
 * 大批量复用扫描那套 AIDL 特权服务（[FileOpsClient]）做进程内原生批删，跨进程只走
 * binder；小批量或绑定失败时落到 provider 的 shell 批删（一批一进程）。
 */
class ThumbnailTrashManager(
    private val accessManager: AccessManager,
    private val imageDao: ImageDao,
    private val fileOpsClient: FileOpsClient,
    private val imageRepository: ImageRepository,
) {

    fun observeSummary(): Flow<ImageDao.ThumbnailTrashSummary> =
        imageDao.observeThumbnailTrashSummary()

    fun pagingItems(): Flow<PagingData<ImageEntity>> =
        Pager(
            config = PagingConfig(
                pageSize = 60,
                prefetchDistance = 120,
                initialLoadSize = 60,
                enablePlaceholders = false,
            ),
            pagingSourceFactory = { imageDao.pagingThumbnailTrash() },
        ).flow

    /** 永久删除选中的缩略图（多选）。返回实际成功删除的张数。 */
    suspend fun purge(imageIds: List<Long>): Int = withContext(Dispatchers.IO) {
        if (imageIds.isEmpty()) return@withContext 0
        val provider = accessManager.current()
        if (!provider.isAvailable()) return@withContext 0
        val bound = if (imageIds.size >= AIDL_MIN_BATCH) fileOpsClient.bind(provider.getAccessMode()) else null
        var aidlAlive = bound != null
        var deleted = 0
        try {
            for (idChunk in imageIds.chunked(FILE_CHUNK)) {
                // 取消只在块间生效：单块内「删文件 → 落库」不可分割（见 purgeChunk）
                ensureActive()
                // 以 DB 为准筛出仍是 thumbnail_trash 的 chatthumb，避免误标 deleted 污染统计；
                // 逐块查（单次 cursor ≤ FILE_CHUNK 行）而非一次性 flatMap 收集，控住内存。
                val chunk = imageDao.getThumbnailTrashByIds(idChunk)
                val outcome = purgeChunk(chunk, bound, aidlAlive, provider)
                aidlAlive = outcome.aidlAlive
                deleted += outcome.deleted
            }
        } finally {
            bound?.close()
        }
        Timber.i("ThumbnailTrash purge(selected): deleted=$deleted/${imageIds.size} aidl=${bound != null}")
        deleted
    }

    /**
     * 一键清理全部缩略图。
     *
     * 图片可能多达数万张，故支持 [onProgress] 上报进度；DB 标记删除分块写入，
     * 中途中断时已删除的部分也能落库、不丢进度。
     *
     * 用 keyset 分页（id 升序游标）逐页拉取，绝不调用全量查询：早期 `listThumbnailTrash()`
     * 一次性 `SELECT *` 把数万行物化进内存，结果集超过 CursorWindow 2MB 上限就会在遍历到
     * 某行时抛 `Couldn't read row N, col 0 from CursorWindow` 崩溃。分页后单次 cursor 仅
     * [FILE_CHUNK] 行，远低于该上限。
     *
     * @param onProgress (已处理张数, 总张数) 回调
     * @return 实际成功删除的张数
     */
    suspend fun purgeAll(onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): Int =
        withContext(Dispatchers.IO) {
            val total = imageDao.countThumbnailTrash()
            if (total == 0) {
                onProgress(0, 0)
                return@withContext 0
            }
            val provider = accessManager.current()
            if (!provider.isAvailable()) return@withContext 0

            onProgress(0, total)
            val bound = if (total >= AIDL_MIN_BATCH) fileOpsClient.bind(provider.getAccessMode()) else null
            var aidlAlive = bound != null
            var done = 0
            var deleted = 0
            try {
                // 删成功的行 status 变 'deleted' 自然从后续页排除；删失败的行 id ≤ afterId 也不再被取到，
                // 故固定从 id 升序推进，既不漏不重也不会死循环。
                var afterId = 0L
                while (true) {
                    // 取消只在块间生效：单块内「删文件 → 落库」不可分割（见 purgeChunk）
                    ensureActive()
                    val page = imageDao.listThumbnailTrashPaged(afterId, FILE_CHUNK)
                    if (page.isEmpty()) break
                    afterId = page.last().id
                    val outcome = purgeChunk(page, bound, aidlAlive, provider)
                    aidlAlive = outcome.aidlAlive
                    deleted += outcome.deleted
                    done += page.size
                    onProgress(done, total)
                }
            } finally {
                bound?.close()
            }
            Timber.i("ThumbnailTrash purgeAll: deleted=$deleted/$total aidl=${bound != null}")
            deleted
        }

    private data class ChunkOutcome(val deleted: Int, val aidlAlive: Boolean)

    /**
     * 删除一批（≤ [FILE_CHUNK]）缩略图文件，并把删成功的行标记为 deleted。
     *
     * 选路策略：[aidlAlive] 且 [bound] 非空时走 AIDL 特权服务批删（进程内原生批删，跨进程只走
     * binder）；binder 死亡等异常就地降级到 shell 批删，并通过返回的 [ChunkOutcome.aidlAlive]=false
     * 让后续批次都走 shell。是否绑定 AIDL 由调用方按总量（[AIDL_MIN_BATCH]）决定并跨批复用。
     */
    private suspend fun purgeChunk(
        chunk: List<ImageEntity>,
        bound: BoundFileOps?,
        aidlAlive: Boolean,
        provider: FileAccessProvider,
    ): ChunkOutcome {
        if (chunk.isEmpty()) return ChunkOutcome(0, aidlAlive)
        val paths = chunk.map { it.path }
        var alive = aidlAlive
        // 文件批删一旦发起就会执行完（binder 同步事务 / shell 批删都不可中断）：结果必须落库。
        // 取消若打断在「删文件」与「标记 deleted」之间，会留下永远加载失败的幻影行且漏计统计，
        // 故整块置于 NonCancellable，取消只在块间生效（调用方循环里 ensureActive）。
        return withContext(NonCancellable) {
            val results: BooleanArray = if (alive && bound != null) {
                runCatching {
                    // 批量大事务放宽超时；超时（BinderCallTimeoutException）走同一 shell 降级路径
                    bound.callWithTimeout(timeoutMs = AIDL_DELETE_TIMEOUT_MS) {
                        it.deleteFiles(paths.toTypedArray())
                    }
                }.getOrElse {
                    // binder 死亡 / 卡死等异常：本块及后续都落到 shell
                    Timber.w(it, "AIDL deleteFiles failed, fallback to shell")
                    alive = false
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
                // 守卫 from='thumbnail_trash'：清空期间被其他路径迁走的行不被误标 deleted
                imageDao.markDeletedFromStatus(okIds, System.currentTimeMillis(), "thumbnail_trash")
                // 级联清理派生数据：缩略图常规上不进分析，但防御性清掉可能存在的
                // OCR/标签/向量/排斥残留（死向量会占据语义搜索 TopK 名额）；幂等
                imageRepository.purgeDerivedData(okIds)
            }
            ChunkOutcome(okIds.size, alive)
        }
    }

    companion object {
        /** 每批删除/标记的张数：AIDL binder 单次事务与 DB markDeleted 共用（< SQLite 变量上限） */
        private const val FILE_CHUNK = 500

        /** 达到该量级才值得绑定 AIDL 特权服务（绑定有约秒级启动成本） */
        private const val AIDL_MIN_BATCH = 2000

        /** AIDL 批删单次事务超时：一批 ≤ [FILE_CHUNK] 个 unlink，60s 覆盖坏卡/FUSE 卡顿场景 */
        private const val AIDL_DELETE_TIMEOUT_MS = 60_000L
    }
}
