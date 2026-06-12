package com.xjw.qalbum.service.indexer

import androidx.room.withTransaction
import com.xjw.qalbum.data.db.AppDatabase
import com.xjw.qalbum.data.db.dao.DirSnapshotDao
import com.xjw.qalbum.data.db.dao.ImageDao
import com.xjw.qalbum.data.db.entity.DirSnapshotEntity
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.domain.model.FileInfo
import com.xjw.qalbum.domain.model.ScanProgress
import com.xjw.qalbum.service.indexer.transport.DirStat
import com.xjw.qalbum.service.indexer.transport.ScanTransport
import com.xjw.qalbum.service.indexer.transport.ScanTransportFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/** 扫描档位 */
enum class ScanMode {
    /** 增量：目录 mtime 剪枝，只扫有变化的目录 —— 日常路径，O(变化量) */
    INCREMENTAL,

    /** 全量：所有目录都做文件级 diff（可全库纠正 missing/幽灵），但只对新增/变化文件读文件头 */
    FULL,

    /** 深度校验：全量基础上对未变化文件也重读文件头，修正历史 mime/动图误判 */
    DEEP_VERIFY,
}

/**
 * 扫描管线 —— 三档共用一条实现。
 *
 * 流程：
 * 1. 枚举目录层（~1.6w 个三字符目录），与 [DirSnapshotEntity] 快照做 [DirDiffer] 剪枝；
 * 2. 消失目录：行转 missing、快照删除；
 * 3. 需要扫描的目录按批处理：枚举文件 → 按目录范围查 DB 行 → [FileClassifier]
 *    四分类 → 仅对新增/变化读文件头识别 → **一个事务**提交行变更 + 目录快照。
 *
 * 性能/安全特性：
 * - 任何阶段都不持有全库文件列表或全表行快照，峰值内存 O(单批)；
 * - 快照与行变更同事务提交：扫描中断后未提交目录下次自然重扫，幂等；
 * - 不再有每次扫描全表刷 last_seen / markThumbnailsAsTrash 的写放大。
 */
class ScanPipeline(
    private val db: AppDatabase,
    private val imageDao: ImageDao,
    private val dirSnapshotDao: DirSnapshotDao,
    private val transportFactory: ScanTransportFactory,
) {

    /** 进程级互斥：定时 Worker 增量与手动扫描可能并发触发，串行化避免重复插入 */
    private val scanMutex = Mutex()

    fun scan(chatpicRoot: String, mode: ScanMode): Flow<ScanProgress> = channelFlow {
        scanMutex.withLock { runScan(chatpicRoot, mode) }
    }

    private suspend fun ProducerScope<ScanProgress>.runScan(
        chatpicRoot: String,
        mode: ScanMode,
    ) {
        val startedAt = System.currentTimeMillis()
        val transport = transportFactory.create()
        if (transport == null) {
            send(ScanProgress.Failed("文件访问未就绪：请先在设置页授权 Shizuku 或 Root"))
            return
        }
        try {
            // ---- Step 1: 目录层枚举 ----
            send(ScanProgress.Enumerating(0))
            val fsDirs = ArrayList<DirStat>(4096)
            for (src in SOURCE_DIRS) {
                fsDirs += transport.listSubdirs("$chatpicRoot/$src")
                send(ScanProgress.Enumerating(fsDirs.size))
            }

            // ---- Step 2: 目录级 diff（快照按当前根路径隔离） ----
            val rootPrefix = "$chatpicRoot/"
            val snapshots = dirSnapshotDao.listAll().filter { it.dirPath.startsWith(rootPrefix) }
            val dirDiff = DirDiffer.diff(fsDirs, snapshots)
            val toScan = if (mode == ScanMode.INCREMENTAL) dirDiff.toScan else fsDirs

            // ---- Step 3: 消失目录 → 行转 missing + 快照删除 ----
            if (dirDiff.deletedDirPaths.isNotEmpty()) {
                db.withTransaction {
                    for (dir in dirDiff.deletedDirPaths) {
                        imageDao.markMissingInPathRange("$dir/", "${dir}0")
                    }
                    dirDiff.deletedDirPaths.chunked(DB_VAR_LIMIT).forEach {
                        dirSnapshotDao.deleteByPaths(it)
                    }
                }
                Timber.i("ScanPipeline: ${dirDiff.deletedDirPaths.size} dirs vanished, rows marked missing")
            }

            // ---- Step 4: 分批扫描目录 ----
            val deepVerify = mode == ScanMode.DEEP_VERIFY
            var processedDirs = 0
            var updatedFiles = 0
            var missingFiles = 0
            if (toScan.isNotEmpty()) {
                send(ScanProgress.Indexing(0, toScan.size))
                for (dirBatch in toScan.chunked(DIR_BATCH)) {
                    val r = processDirBatch(transport, dirBatch, deepVerify)
                    updatedFiles += r.updated
                    missingFiles += r.missing
                    processedDirs += dirBatch.size
                    send(ScanProgress.Indexing(processedDirs, toScan.size))
                }
            }

            Timber.i(
                "ScanPipeline[$mode] done via ${transport.name}: dirs=${fsDirs.size} " +
                    "scanned=${toScan.size} pruned=${fsDirs.size - toScan.size} " +
                    "updated=$updatedFiles missing=$missingFiles " +
                    "in ${System.currentTimeMillis() - startedAt}ms",
            )
            send(ScanProgress.Done(updatedFiles))
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Timber.e(t, "ScanPipeline[$mode] failed")
            send(ScanProgress.Failed(t.message ?: "未知错误"))
        } finally {
            transport.close()
        }
    }

    // ---- 单批目录处理 ----

    private data class BatchResult(val updated: Int, val missing: Int)

    private suspend fun processDirBatch(
        transport: ScanTransport,
        dirBatch: List<DirStat>,
        deepVerify: Boolean,
    ): BatchResult {
        // 1. 枚举批内所有文件，按父目录分组
        val filesByDir = HashMap<String, MutableList<FileInfo>>(dirBatch.size * 2)
        transport.listFiles(dirBatch.map { it.path }).collect { f ->
            filesByDir.getOrPut(f.path.substringBeforeLast('/')) { ArrayList() }.add(f)
        }

        // 2. 逐目录与 DB 行做 diff（path 索引范围查询，只装载本批行）
        val newFiles = ArrayList<FileInfo>()
        val changedFiles = ArrayList<FileInfo>()
        val reviveIds = ArrayList<Long>()
        val missingIds = ArrayList<Long>()
        val unchangedPairs = ArrayList<Pair<FileInfo, ImageDao.ScanRow>>()
        for (dir in dirBatch) {
            val rows = imageDao.listScanRowsInPathRange("${dir.path}/", "${dir.path}0")
            val cls = FileClassifier.classify(
                fsFiles = filesByDir[dir.path].orEmpty(),
                dbRows = rows,
                collectUnchanged = deepVerify,
            )
            newFiles += cls.newFiles
            changedFiles += cls.changedFiles
            reviveIds += cls.reviveIds
            missingIds += cls.missingIds
            if (deepVerify) unchangedPairs += cls.unchangedPairs
        }

        // 3. 仅对需要的文件读 header 识别
        val now = System.currentTimeMillis()
        val newEntities = detectAndBuild(transport, newFiles, now)
        val changedEntities = detectAndBuild(transport, changedFiles, now)
        val mimeFixes = verifyMime(transport, unchangedPairs)

        // 4. 行变更 + 本批目录快照原子提交（崩溃安全：未提交目录下次重扫）
        db.withTransaction {
            newEntities.chunked(INSERT_CHUNK).forEach { imageDao.insertAll(it) }
            if (changedEntities.isNotEmpty()) imageDao.updateBatchFromScan(changedEntities)
            reviveIds.chunked(DB_VAR_LIMIT).forEach { imageDao.reviveByIds(it) }
            missingIds.chunked(DB_VAR_LIMIT).forEach { imageDao.markMissingByIds(it) }
            for (fix in mimeFixes) {
                imageDao.fixMimeType(fix.id, fix.mime, fix.isAnimated)
            }
            dirSnapshotDao.upsertAll(
                dirBatch.map { DirSnapshotEntity(dirPath = it.path, mtime = it.mtimeMs, scannedAt = now) },
            )
        }
        return BatchResult(
            updated = newEntities.size + changedEntities.size + mimeFixes.size,
            missing = missingIds.size,
        )
    }

    /** 批量读 header → magic bytes 识别 → 构造 entity；识别失败的文件被跳过 */
    private suspend fun detectAndBuild(
        transport: ScanTransport,
        files: List<FileInfo>,
        now: Long,
    ): List<ImageEntity> {
        if (files.isEmpty()) return emptyList()
        val headers = transport.readHeaders(files.map { it.path }, HEADER_BYTES)
        val out = ArrayList<ImageEntity>(files.size)
        for (i in files.indices) {
            val header = headers[i] ?: continue
            val type = MagicBytesDetector.detect(header, header.size) ?: continue
            out.add(buildEntity(files[i], type, now))
        }
        return out
    }

    private data class MimeFix(val id: Long, val mime: String, val isAnimated: Boolean)

    /** 深度校验：复核未变化文件的 mime/动图标记，仅修正不一致的行（不动分析状态） */
    private suspend fun verifyMime(
        transport: ScanTransport,
        pairs: List<Pair<FileInfo, ImageDao.ScanRow>>,
    ): List<MimeFix> {
        if (pairs.isEmpty()) return emptyList()
        val headers = transport.readHeaders(pairs.map { it.first.path }, HEADER_BYTES)
        val fixes = ArrayList<MimeFix>()
        for (i in pairs.indices) {
            val header = headers[i] ?: continue
            val type = MagicBytesDetector.detect(header, header.size) ?: continue
            val row = pairs[i].second
            if (type.mime != row.mime || type.isAnimated != row.isAnimated) {
                fixes.add(MimeFix(row.id, type.mime, type.isAnimated))
            }
        }
        return fixes
    }

    /** 用 FileInfo + 识别结果构造 ImageEntity（路径结构：…/{source_type}/{三字符}/{filename}） */
    private fun buildEntity(
        file: FileInfo,
        type: MagicBytesDetector.ImageType,
        now: Long,
    ): ImageEntity {
        val pathParts = file.path.split('/')
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
            width = 0, // 宽高在分析阶段读取
            height = 0,
            isAnimated = type.isAnimated,
            // chatthumb 缩略图直接进缩略图回收站
            status = if (sourceType == "chatthumb") "thumbnail_trash" else "active",
        )
    }

    companion object {
        /** 扫描的子目录，按优先级排列 */
        val SOURCE_DIRS = listOf("chatimg", "chatraw", "chatthumb", "temp")

        /** 每批处理的目录数（决定峰值内存与事务大小：~64 目录 × 平均几百文件） */
        private const val DIR_BATCH = 64

        /** 单条 insert 语句的实体数 */
        private const val INSERT_CHUNK = 500

        /** SQLite 单语句变量上限（999）下的安全分批大小 */
        private const val DB_VAR_LIMIT = 900

        /** magic bytes 检测读取的头部字节数（覆盖 JPEG/PNG/GIF/WebP-VP8X/HEIC 判定） */
        private const val HEADER_BYTES = 32
    }
}
