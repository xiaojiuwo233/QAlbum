package com.xjw.qalbum.service.indexer

import android.os.RemoteException
import androidx.room.withTransaction
import com.xjw.qalbum.data.db.AppDatabase
import com.xjw.qalbum.data.db.dao.DirSnapshotDao
import com.xjw.qalbum.data.db.dao.ImageDao
import com.xjw.qalbum.data.db.entity.DirSnapshotEntity
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.domain.model.FileInfo
import com.xjw.qalbum.domain.model.ScanProgress
import com.xjw.qalbum.qq.QqCachePaths
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
import java.io.IOException

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
        // 双保险规范化（落盘侧 setChatpicPath 已规范化，这里兜历史坏值/其他调用路径）：
        // 末尾 '/' 会让拼出的 path 带双斜杠，与库中已有单斜杠 path 全部不匹配，
        // 200w 行会被判为新增全量重复插入且旧行永久脱离扫描范围
        val root = chatpicRoot.trim().trimEnd('/')
        if (root.isBlank()) {
            send(ScanProgress.Failed("扫描路径为空，请先在设置页配置 chatpic 路径"))
            return
        }
        val initialTransport = transportFactory.create()
        if (initialTransport == null) {
            send(ScanProgress.Failed("文件访问未就绪：请先在设置页授权 Shizuku 或 Root"))
            return
        }
        // var：传输瞬时故障时按批重建热切换（见 Step 4），finally 关闭的是最新通道
        var transport: ScanTransport = initialTransport
        try {
            // ---- Step 1: 目录层枚举 ----
            send(ScanProgress.Enumerating(0))
            val fsDirs = ArrayList<DirStat>(4096)
            for (src in SOURCE_DIRS) {
                fsDirs += transport.listSubdirs("$root/$src")
                send(ScanProgress.Enumerating(fsDirs.size))
            }

            // ---- Step 2: 目录级 diff（快照按当前根路径隔离） ----
            val rootPrefix = "$root/"
            val snapshots = dirSnapshotDao.listAll().filter { it.dirPath.startsWith(rootPrefix) }

            // 枚举结果为空 ≠ 目录真消失：传输层对「不可访问」与「空目录」都返回空列表
            // （存储未挂载、特权进程 namespace 缺 /storage、路径配错均命中）。直接往下走
            // 会把全部快照判为消失目录、全库 active 行误标 missing。先做根目录存在性
            // 校验区分场景，不可判定时中止本轮并保留快照与索引。
            if (fsDirs.isEmpty()) {
                val rootChildren = transport.listSubdirs(root)
                when {
                    rootChildren.isEmpty() -> {
                        send(ScanProgress.Failed("扫描根目录不可访问或为空：$root（已保留现有索引）"))
                        return
                    }
                    rootChildren.none { it.path.substringAfterLast('/') in SOURCE_DIRS } -> {
                        send(ScanProgress.Failed("该路径不包含 chatimg/chatraw/chatthumb/temp 子目录，请检查扫描路径设置"))
                        return
                    }
                    snapshots.isNotEmpty() -> {
                        send(ScanProgress.Failed("源目录本轮枚举为空但存在历史索引，疑似枚举异常，已中止（保留现有索引）"))
                        return
                    }
                    // else：源目录存在但尚无内容且无历史快照 —— 正常空库，继续走完 Done(0)
                }
            }
            val dirDiff = DirDiffer.diff(fsDirs, snapshots)
            val toScan = if (mode == ScanMode.INCREMENTAL) dirDiff.toScan else fsDirs

            // ---- Step 3: 消失目录 → 行转 missing + 快照删除 ----
            if (dirDiff.deletedDirPaths.isNotEmpty()) {
                // 熔断：大量目录同时「消失」更可能是枚举异常（个别源列举静默失败/存储抖动）
                // 而非真删。中止并保留快照，绝不冒全库误标 missing 的风险；小规模真实删除
                // 不受影响。确认已大量清理缓存的用户可等目录 mtime 变化后由文件级 diff 收敛。
                val vanished = dirDiff.deletedDirPaths.size
                if (vanished >= VANISH_FUSE_MIN_DIRS && vanished * 2 > snapshots.size) {
                    send(
                        ScanProgress.Failed(
                            "$vanished/${snapshots.size} 个已索引目录本轮不可见，疑似枚举异常，已中止（保留现有索引）",
                        ),
                    )
                    return
                }
                // 分块提交：块内行变更与快照删除原子一致（中断可自愈），块间释放写锁 ——
                // 消失目录可达 1.6w 个 × path 范围 UPDATE，单事务会持写锁分钟级、WAL 膨胀数百 MB
                for (chunk in dirDiff.deletedDirPaths.chunked(VANISHED_DIR_TX_CHUNK)) {
                    db.withTransaction {
                        for (dir in chunk) {
                            imageDao.markMissingInPathRange("$dir/", "${dir}0", startedAt)
                        }
                        dirSnapshotDao.deleteByPaths(chunk)
                    }
                    send(ScanProgress.Enumerating(fsDirs.size)) // 心跳：让 UI 感知长回收仍在推进
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
                    val r = try {
                        processDirBatch(transport, dirBatch, deepVerify, startedAt)
                    } catch (e: Exception) {
                        if (e !is RemoteException && e !is IOException) throw e
                        // 传输瞬时故障（binder 死亡/超时、shell 进程被杀）：重建传输
                        // （优先回到 AIDL）重试当前批一次，仍失败才让整场失败；
                        // 批事务未提交即失败，重试幂等
                        transport = rebuildTransport(transport, e)
                        processDirBatch(transport, dirBatch, deepVerify, startedAt)
                    }
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

    /** 传输故障后的重建：关闭旧通道再走工厂（会先重试 AIDL 绑定），重建失败原样抛出触发整场失败 */
    private suspend fun rebuildTransport(old: ScanTransport, cause: Exception): ScanTransport {
        Timber.w(cause, "ScanPipeline: transport failure, rebuilding to retry current batch")
        old.close()
        return transportFactory.create() ?: throw cause
    }

    // ---- 单批目录处理 ----

    private data class BatchResult(val updated: Int, val missing: Int)

    private suspend fun processDirBatch(
        transport: ScanTransport,
        dirBatch: List<DirStat>,
        deepVerify: Boolean,
        scanStart: Long,
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
        val retireTerminalIds = ArrayList<Long>()
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
            retireTerminalIds += cls.retireTerminalIds
            missingIds += cls.missingIds
            if (deepVerify) unchangedPairs += cls.unchangedPairs
        }

        // 3. 仅对需要的文件读 header 识别
        val now = System.currentTimeMillis()
        val headerFailedDirs = HashSet<String>()
        val newEntities = detectAndBuild(transport, newFiles, now, headerFailedDirs)
        val changedEntities = detectAndBuild(transport, changedFiles, now, headerFailedDirs)
        val mimeFixes = verifyMime(transport, unchangedPairs)
        if (headerFailedDirs.isNotEmpty()) {
            Timber.w(
                "ScanPipeline: header read failed for files in ${headerFailedDirs.size} dirs " +
                    "(e.g. ${headerFailedDirs.first()}), snapshots withheld for rescan",
            )
        }

        // 4. 行变更 + 本批目录快照原子提交（崩溃安全：未提交目录下次重扫）
        db.withTransaction {
            retireTerminalIds.chunked(DB_VAR_LIMIT).forEach { imageDao.retireTerminalPaths(it) }
            newEntities.chunked(INSERT_CHUNK).forEach { imageDao.insertAll(it) }
            if (changedEntities.isNotEmpty()) imageDao.updateBatchFromScan(changedEntities)
            reviveIds.chunked(DB_VAR_LIMIT).forEach { imageDao.reviveByIds(it) }
            missingIds.chunked(DB_VAR_LIMIT).forEach { imageDao.markMissingByIds(it, scanStart) }
            for (fix in mimeFixes) {
                imageDao.fixMimeType(fix.id, fix.mime, fix.isAnimated)
            }
            // 存在 header 读失败文件的目录本轮不写快照：下次增量自然重扫补回，
            // 否则瞬时读失败的新增/变化文件会被目录剪枝永久漏掉
            dirSnapshotDao.upsertAll(
                dirBatch.filter { it.path !in headerFailedDirs }
                    .map { DirSnapshotEntity(dirPath = it.path, mtime = it.mtimeMs, scannedAt = now) },
            )
        }
        return BatchResult(
            updated = newEntities.size + changedEntities.size + mimeFixes.size,
            missing = missingIds.size,
        )
    }

    /**
     * 批量读 header → magic bytes 识别 → 构造 entity。
     *
     * 两种「跳过」必须区分：header 为 null 是读取失败（瞬时 IO/权限/翻页间隙被删），
     * 记入 [failedDirs] 让所在目录本轮不写快照、下次重扫补回；识别返回 null 才是
     * 「确认非图片」，静默跳过且快照照常提交。
     */
    private suspend fun detectAndBuild(
        transport: ScanTransport,
        files: List<FileInfo>,
        now: Long,
        failedDirs: MutableSet<String>,
    ): List<ImageEntity> {
        if (files.isEmpty()) return emptyList()
        val headers = transport.readHeaders(files.map { it.path }, HEADER_BYTES)
        val out = ArrayList<ImageEntity>(files.size)
        for (i in files.indices) {
            val header = headers[i]
            if (header == null) {
                failedDirs.add(files[i].path.substringBeforeLast('/'))
                continue
            }
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
            // 同一张 QQ 图片的原图 / 预览图 / 缩略图共用家族键，入库即由触发器归入同一家族
            variantGroup = QqCachePaths.familyKey(file.path),
            // 合并显示时的大小：先按自身大小，属于家族的由触发器改成整组合计
            variantBytes = file.size,
            size = file.size,
            mtime = file.lastModified,
            firstSeen = now,
            lastSeen = now,
            mime = type.mime,
            width = 0, // 宽高在分析阶段读取
            height = 0,
            isAnimated = type.isAnimated,
            // chatthumb 沿用历史状态名 thumbnail_trash：不进图库时间线，在缩略图页与来源页管理。
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

        /** 消失目录回收的单事务目录数：块内行+快照原子一致，块间释放写锁并发心跳 */
        private const val VANISHED_DIR_TX_CHUNK = 256

        /** 消失目录熔断下限：低于该数的真实删除不触发比例熔断，正常回收 */
        private const val VANISH_FUSE_MIN_DIRS = 64

        /** magic bytes 检测读取的头部字节数（覆盖 JPEG/PNG/GIF/WebP-VP8X/HEIC 判定与 ftyp 兼容 brand 窗口） */
        private const val HEADER_BYTES = 32
    }
}
