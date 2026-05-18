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
 * 关键策略：
 * - 文件**无扩展名**，必须用 [MagicBytesDetector] 检测格式
 * - 同一 `cache_key` 跨 source_type 出现的记录组成「变体组」（同图不同分辨率）
 * - 批量写入（每 500 条一个 transaction），保证 10 万张图也能稳定完成
 *
 * 真正的"图片分析"（hash / OCR / AI）在后续 Phase 中完成，本类只负责"发现并入库"。
 */
class ImageIndexer(
    private val accessManager: AccessManager,
    private val imageDao: ImageDao,
) {

    /**
     * 全量扫描：清空旧索引语义留给调用方决定，
     * 本方法只做"新增/更新"，不会删除已有记录。
     */
    suspend fun fullScan(chatpicRoot: String): Flow<ScanProgress> = flow {
        val provider = accessManager.current()
        if (!provider.isAvailable()) {
            emit(ScanProgress.Failed("文件访问未就绪：请先在设置页授权 Shizuku 或 Root"))
            return@flow
        }

        // ---- Step 1: 枚举所有候选文件 ----
        val all = mutableListOf<FileInfo>()
        for (sourceDir in SOURCE_DIRS) {
            val sourcePath = "$chatpicRoot/$sourceDir"
            if (!provider.exists(sourcePath)) continue

            // 三字符索引目录（0b0, 0e2, ffa, ...）
            val indexDirs = provider.listFiles(sourcePath).toList()
            for (indexDir in indexDirs) {
                if (!indexDir.isDirectory) continue
                provider.listFiles(indexDir.path).collect { f ->
                    if (!f.isDirectory) all.add(f)
                }
            }
        }
        emit(ScanProgress.Enumerating(all.size))
        Timber.i("Indexer: enumerated ${all.size} candidate files under $chatpicRoot")

        // ---- Step 2: 逐个识别 + 批量入库 ----
        val now = System.currentTimeMillis()
        val batch = mutableListOf<ImageEntity>()
        var processed = 0
        var skipped = 0

        for (file in all) {
            processed++

            val entity = buildEntity(provider, file, now)
            if (entity == null) {
                skipped++
                if (processed % 500 == 0) emit(ScanProgress.Indexing(processed, all.size))
                continue
            }
            batch.add(entity)

            if (batch.size >= BATCH_SIZE) {
                imageDao.insertBatch(batch)
                batch.clear()
                emit(ScanProgress.Indexing(processed, all.size))
            }
        }
        if (batch.isNotEmpty()) {
            imageDao.insertBatch(batch)
        }
        Timber.i("Indexer: indexed ${all.size - skipped} images, skipped $skipped non-image files")

        // ---- Step 3: 建立变体组 ----
        buildVariantGroups()
        emit(ScanProgress.Done(all.size - skipped))
    }

    /**
     * 变体组建立：把跨 source_type 共享 cache_key 的记录归到同一 variant_group，
     * 并按优先级（raw > img > thumb > temp）挑出主图。
     */
    suspend fun buildVariantGroups() {
        val keys = imageDao.cacheKeysWithMultipleEntries()
        if (keys.isEmpty()) {
            Timber.i("Variant grouping: no shared cache_keys")
            return
        }

        var nextGroupId = imageDao.maxVariantGroupId() + 1
        var grouped = 0
        for (key in keys) {
            val members = imageDao.getByCacheKey(key)
            if (members.size <= 1) continue
            // 主图优先级：chatraw > chatimg > chatthumb > temp
            val primary = members.maxByOrNull { SOURCE_PRIORITY[it.sourceType] ?: 0 } ?: members.first()
            members.forEach { m ->
                imageDao.assignVariant(m.id, nextGroupId, isPrimary = m.id == primary.id)
            }
            nextGroupId++
            grouped += members.size
        }
        Timber.i("Variant grouping: assigned $grouped rows into ${nextGroupId - 1} groups")
    }

    /**
     * 用 FileInfo + magic bytes 构造 ImageEntity。
     * 对于无法识别为图片的文件，返回 null。
     */
    private suspend fun buildEntity(
        provider: FileAccessProvider,
        file: FileInfo,
        now: Long,
    ): ImageEntity? {
        // 通过读流前 16 字节识别 mime；流读完即关闭
        val type = runCatching {
            provider.readFile(file.path).use { stream ->
                MagicBytesDetector.detect(stream)
            }
        }.getOrNull() ?: return null

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
            width = 0,            // 宽高在后续 Phase 的 DIMENSIONS 阶段读取
            height = 0,
            isAnimated = type.isAnimated,
        )
    }

    companion object {
        /** 扫描的子目录，按优先级排列 */
        val SOURCE_DIRS = listOf("chatimg", "chatraw", "chatthumb", "temp")

        /** 同一变体组内的主图优先级 */
        val SOURCE_PRIORITY = mapOf(
            "chatraw" to 3,
            "chatimg" to 2,
            "chatthumb" to 1,
            "temp" to 0,
        )

        const val BATCH_SIZE = 500
    }
}
