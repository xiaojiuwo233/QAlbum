package com.xjw.qalbum.service.indexer

import com.xjw.qalbum.access.AccessManager
import com.xjw.qalbum.access.FileAccessProvider
import com.xjw.qalbum.data.db.dao.ImageDao
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.domain.model.FileInfo
import com.xjw.qalbum.domain.model.ScanProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger

/**
 * 图片索引器：扫描 chatpic 目录，把发现的图片写入 [ImageEntity] 表。
 *
 * 扫描结构（来自 DEVELOPMENT.md 附录 A）：
 * ```
 * chatpic/
 *   chatimg/  {三字符索引目录}/Cache_{hash}
 *   chatraw/  {三字符索引目录}/Cache_{hash}
 *   chatthumb/{三字符索引目录}/Cache_{hash}
 *   temp/     ……（结构不同，作为清理候选）
 * ```
 * 关键策略（性能优化后）：
 * - **枚举**：一条 `find + stat` 递归命令拿到所有候选（[FileAccessProvider.enumerateFiles]），
 *   边发现边上报进度，避免上千个三字符目录逐个 IPC 造成的「卡在启动中」。
 * - **检测**：只对「新增 / size·mtime 变化」的文件读前若干字节做 magic bytes 识别，
 *   未变化的文件直接跳过（只刷新 last_seen）。检测并发执行（[CONCURRENCY]）。
 * - **入库**：基于内存 (path,size,mtime) 快照分流，批量 insert / update，
 *   不再逐条 getByPath（原实现 path 无索引时是 O(n²)）。
 */
class ImageIndexer(
    private val accessManager: AccessManager,
    private val imageDao: ImageDao,
) {

    /**
     * 扫描 chatpic 并把发现的图片写入索引：只做"新增/更新"，不删除已有记录。
     *
     * @param force false（增量）跳过 size/mtime 未变化的文件，最快；
     *              true（全量）对所有已存在文件也重新识别一遍 magic bytes，用于修正历史误判。
     */
    suspend fun fullScan(chatpicRoot: String, force: Boolean = false): Flow<ScanProgress> = channelFlow {
        val provider = accessManager.current()
        if (!provider.isAvailable()) {
            send(ScanProgress.Failed("文件访问未就绪：请先在设置页授权 Shizuku 或 Root"))
            return@channelFlow
        }

        // ---- Step 1: 单命令递归枚举（边发现边报进度，消除"卡在启动中"）----
        send(ScanProgress.Enumerating(0))
        val roots = SOURCE_DIRS.map { "$chatpicRoot/$it" }
        val all = ArrayList<FileInfo>(4096)
        provider.enumerateFiles(roots, minDepth = 2, maxDepth = 2).collect { f ->
            all.add(f)
            if (all.size % ENUM_REPORT_STEP == 0) send(ScanProgress.Enumerating(all.size))
        }
        send(ScanProgress.Enumerating(all.size))
        Timber.i("Indexer: enumerated ${all.size} candidate files under $chatpicRoot")
        if (all.isEmpty()) {
            send(ScanProgress.Done(0))
            return@channelFlow
        }

        // ---- Step 2: 用 (path,size,mtime) 快照把候选分流为 新增 / 变化 / 未变 ----
        val now = System.currentTimeMillis()
        val snapshot = imageDao.listPathSizeMtime().associateBy { it.path }
        val newFiles = ArrayList<FileInfo>()
        val changedFiles = ArrayList<FileInfo>()
        val unchangedPaths = ArrayList<String>()
        for (f in all) {
            val s = snapshot[f.path]
            when {
                s == null -> newFiles.add(f)
                force || s.size != f.size || s.mtime != f.lastModified -> changedFiles.add(f)
                else -> unchangedPaths.add(f.path)
            }
        }

        // ---- Step 3: 仅对 新增/变化 文件并发检测 magic bytes 并构造 entity ----
        val total = newFiles.size + changedFiles.size
        val processed = AtomicInteger(0)
        val newEntities = detectAndBuild(provider, newFiles, now, total, processed)
        val changedEntities = detectAndBuild(provider, changedFiles, now, total, processed)

        // ---- Step 4: 批量入库 ----
        newEntities.chunked(BATCH_SIZE).forEach { imageDao.insertAll(it) }
        changedEntities.chunked(BATCH_SIZE).forEach { imageDao.updateBatchFromScan(it) }
        unchangedPaths.chunked(DB_VAR_LIMIT).forEach { imageDao.touchLastSeenByPaths(it, now) }
        Timber.i(
            "Indexer: new=${newEntities.size}, changed=${changedEntities.size}, " +
                "unchanged=${unchangedPaths.size}, skipped=${total - newEntities.size - changedEntities.size}",
        )

        // ---- Step 5: 历史/新增缩略图收进缩略图入口 ----
        imageDao.markThumbnailsAsTrash()
        send(ScanProgress.Done(newEntities.size + changedEntities.size))
    }

    /**
     * 并发对 [files] 做 magic bytes 识别并构造 [ImageEntity]，无法识别为图片的返回 null 被过滤。
     * 进度通过 [trySend] 上报（在子协程中调用，必须用线程安全的 trySend 而非 emit）。
     */
    private suspend fun ProducerScope<ScanProgress>.detectAndBuild(
        provider: FileAccessProvider,
        files: List<FileInfo>,
        now: Long,
        total: Int,
        processed: AtomicInteger,
    ): List<ImageEntity> {
        if (files.isEmpty()) return emptyList()
        val semaphore = Semaphore(CONCURRENCY)
        return coroutineScope {
            files.map { file ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        val entity = buildEntity(provider, file, now)
                        val done = processed.incrementAndGet()
                        if (done % INDEX_REPORT_STEP == 0 || done == total) {
                            trySend(ScanProgress.Indexing(done, total))
                        }
                        entity
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    /**
     * 用 FileInfo + magic bytes 构造 ImageEntity。无法识别为图片的文件返回 null。
     * 只读取文件头部若干字节（[HEADER_BYTES]），避免打开整个文件。
     */
    private suspend fun buildEntity(
        provider: FileAccessProvider,
        file: FileInfo,
        now: Long,
    ): ImageEntity? {
        val header = provider.readHeader(file.path, HEADER_BYTES) ?: return null
        val type = MagicBytesDetector.detect(header, header.size) ?: return null

        val pathParts = file.path.split('/')
        // 倒数 1 是 filename，倒数 2 是三字符目录，倒数 3 是 source_type (chatimg / chatraw / chatthumb / temp)
        val sourceType = pathParts.getOrNull(pathParts.size - 3) ?: "unknown"
        val parentDir = pathParts.getOrNull(pathParts.size - 2) ?: "unknown"
        val cacheKey = file.name.removePrefix("Cache_")

        return ImageEntity(
            path = file.path,
            filename = file.name,
            parentDir = parentDir,
            sourceType = sourceType,
            cacheKey = cacheKey,
            size = file.size,
            mtime = file.lastModified,
            firstSeen = now,
            lastSeen = now,
            mime = type.mime,
            width = 0,            // 宽高在分析阶段读取
            height = 0,
            isAnimated = type.isAnimated,
            // chatthumb 缩略图直接进缩略图回收站（markThumbnailsAsTrash 仍会兜底历史数据）
            status = if (sourceType == "chatthumb") "thumbnail_trash" else "active",
        )
    }

    companion object {
        /** 扫描的子目录，按优先级排列 */
        val SOURCE_DIRS = listOf("chatimg", "chatraw", "chatthumb", "temp")

        const val BATCH_SIZE = 500

        /** magic bytes 检测读取的头部字节数（覆盖 JPEG/PNG/GIF/WebP-VP8X/HEIC 判定） */
        private const val HEADER_BYTES = 32

        /** 文件头检测的并发度（Root/Shizuku 跨进程 IO 适度并发即可饱和） */
        private const val CONCURRENCY = 8

        /** 枚举进度上报步长 */
        private const val ENUM_REPORT_STEP = 500

        /** 索引进度上报步长 */
        private const val INDEX_REPORT_STEP = 200

        /** SQLite 单语句变量上限（999）下的安全分批大小 */
        private const val DB_VAR_LIMIT = 900
    }
}
