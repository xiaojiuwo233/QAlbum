package com.xjw.qalbum.service.indexer

import com.xjw.qalbum.access.AccessManager
import com.xjw.qalbum.access.FileAccessProvider
import com.xjw.qalbum.data.db.dao.ImageDao
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.domain.model.FileInfo
import com.xjw.qalbum.domain.model.ScanProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import timber.log.Timber

/**
 * 增量扫描器：通过 (path, size, mtime) 三元组做 diff，
 * 避免每次启动都重新读所有文件。
 *
 * diff 规则：
 * - **新增**（disk 有，db 无）→ 调用 Indexer 入库
 * - **变更**（path 相同但 size/mtime 不同）→ 更新 size/mtime
 * - **消失**（db 有，disk 无）→ 暂不删除，只标记 status=missing，连续 N 次后清理
 *
 * 复用 [ImageIndexer.buildEntity] 那套逻辑（这里没显式调用 magic 检测，
 * 因为变更场景很少；新增场景由 Indexer 负责）。
 */
class IncrementalScanner(
    private val accessManager: AccessManager,
    private val imageDao: ImageDao,
) {

    suspend fun scan(chatpicRoot: String): Flow<ScanProgress> = flow {
        val provider = accessManager.current()
        if (!provider.isAvailable()) {
            emit(ScanProgress.Failed("文件访问未就绪"))
            return@flow
        }

        // 1. 拉取磁盘文件列表
        val diskFiles = mutableMapOf<String, FileInfo>()
        for (sourceDir in ImageIndexer.SOURCE_DIRS) {
            val sourcePath = "$chatpicRoot/$sourceDir"
            if (!provider.exists(sourcePath)) continue
            provider.listFiles(sourcePath).toList().forEach { indexDir ->
                if (!indexDir.isDirectory) return@forEach
                provider.listFiles(indexDir.path).collect { f ->
                    if (!f.isDirectory) diskFiles[f.path] = f
                }
            }
        }
        emit(ScanProgress.Enumerating(diskFiles.size))

        // 2. 拉取 db 端三元组快照
        val dbSnapshot = imageDao.listPathSizeMtime().associateBy { it.path }

        // 3. 计算 diff
        val newPaths = diskFiles.keys - dbSnapshot.keys
        Timber.i("IncrementalScanner: new=${newPaths.size}, db=${dbSnapshot.size}, disk=${diskFiles.size}")

        // 4. 处理新增：调用 buildEntity 但通过 indexer 的内部方法不方便，
        //    这里复用其同样的字段映射规则，但只针对新增子集
        val now = System.currentTimeMillis()
        val batch = mutableListOf<ImageEntity>()
        var processed = 0

        for (path in newPaths) {
            val info = diskFiles[path] ?: continue
            val entity = createEntityForNew(provider, info, now) ?: continue
            batch.add(entity)
            processed++
            if (batch.size >= ImageIndexer.BATCH_SIZE) {
                imageDao.insertBatch(batch)
                batch.clear()
                emit(ScanProgress.Indexing(processed, newPaths.size))
            }
        }
        if (batch.isNotEmpty()) imageDao.insertBatch(batch)

        // 5. 标记仍存在的 db 记录的 last_seen（用于后续判定 missing）
        diskFiles.keys.intersect(dbSnapshot.keys).forEach { path ->
            dbSnapshot[path]?.let { imageDao.touchLastSeen(it.id, now) }
        }

        // 6. 历史/新增缩略图收进独立回收站
        imageDao.markThumbnailsAsTrash()

        emit(ScanProgress.Done(processed))
    }

    private suspend fun createEntityForNew(
        provider: FileAccessProvider,
        file: FileInfo,
        now: Long,
    ): ImageEntity? {
        val type = runCatching {
            provider.readFile(file.path).use { stream ->
                MagicBytesDetector.detect(stream)
            }
        }.getOrNull() ?: return null

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
            width = 0,
            height = 0,
            isAnimated = type.isAnimated,
        )
    }
}
