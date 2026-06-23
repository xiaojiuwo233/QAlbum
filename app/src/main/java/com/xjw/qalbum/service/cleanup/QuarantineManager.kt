package com.xjw.qalbum.service.cleanup

import android.content.Context
import com.xjw.qalbum.access.AccessManager
import com.xjw.qalbum.access.FileAccessProvider
import com.xjw.qalbum.data.db.dao.CleanupDao
import com.xjw.qalbum.data.db.dao.ImageDao
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.data.db.entity.QuarantineEntity
import com.xjw.qalbum.data.preferences.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * 回收站
 *
 * 删除操作的实际语义：把文件从 chatpic 移动到 App 私有目录 `filesDir/quarantine/`（可恢复，
 * 默认 30 天后真正删除，见 [SettingsRepository.quarantineRetentionDays]）。
 *
 * 性能：回收站展示直接用移动后的原图（Coil 按需降采样），不再为每张预生成缩略图——预生成
 * 要「读整张原图 + 解码 + 压缩」，是批量移入的最大瓶颈。批量移入走 [quarantineBatch]：
 * [CONCURRENCY] 路并发处理文件移动 + 一次性批量写库；单张特权 move 失败（如 Shizuku shell
 * 权限写不进 App 私有目录）回退到 App 自行拷贝。
 */
class QuarantineManager(
    private val context: Context,
    private val accessManager: AccessManager,
    private val imageDao: ImageDao,
    private val cleanupDao: CleanupDao,
    private val settings: SettingsRepository,
) {

    /** 回收站根目录 */
    private val rootDir: File by lazy {
        File(context.filesDir, "quarantine").apply { if (!exists()) mkdirs() }
    }

    /** 把指定图片移到回收站（单张；批量走 [quarantineBatch]）。 */
    suspend fun quarantine(imageId: Long, reason: String): Boolean = withContext(Dispatchers.IO) {
        val provider = accessManager.current()
        if (!provider.isAvailable()) return@withContext false
        val image = imageDao.getById(imageId) ?: return@withContext false
        val entity = moveOneToQuarantine(provider, image, reason, computeExpiry())
            ?: return@withContext false
        imageDao.updateStatus(image.id, "quarantined")
        cleanupDao.insertQuarantine(entity)
        Timber.i("Quarantine: $imageId moved, size=${image.size}")
        true
    }

    /**
     * 批量移入回收站。并发（[CONCURRENCY] 路）处理文件移动，DB 一次性批量写，
     * 远快于逐张「移动 + 3 次独立事务」串行。返回成功移动的张数。
     */
    suspend fun quarantineBatch(
        items: List<Pair<Long, String>>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Int = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext 0
        val provider = accessManager.current()
        if (!provider.isAvailable()) return@withContext 0

        val total = items.size
        onProgress(0, total)
        val expiresAt = computeExpiry()
        // 批量预取图片行，避免逐张 getById
        val imageById = imageDao.getByIds(items.map { it.first }).associateBy { it.id }

        val done = AtomicInteger(0)
        val sem = Semaphore(CONCURRENCY)
        val entities = coroutineScope {
            items.map { (imageId, reason) ->
                async {
                    sem.withPermit {
                        val entity = imageById[imageId]?.let {
                            moveOneToQuarantine(provider, it, reason, expiresAt)
                        }
                        onProgress(done.incrementAndGet(), total)
                        entity
                    }
                }
            }.awaitAll()
        }.filterNotNull()

        if (entities.isNotEmpty()) {
            imageDao.updateStatusByIds(entities.map { it.imageId }, "quarantined")
            cleanupDao.insertQuarantineAll(entities)
        }
        Timber.i("QuarantineBatch: moved ${entities.size}/$total")
        entities.size
    }

    /**
     * 把单张图片文件移动到回收站目录，返回待写库的记录（不碰 DB）。
     * 优先特权 move（root 下同分区是秒级 rename）；失败则 App 自行拷贝 + 删原文件
     * （Shizuku shell 写不进私有目录时走这条）。
     */
    private suspend fun moveOneToQuarantine(
        provider: FileAccessProvider,
        image: ImageEntity,
        reason: String,
        expiresAt: Long,
    ): QuarantineEntity? {
        val dest = File(rootDir, "${image.id}_${image.sourceType}_${image.filename}")
        val moved = runCatching { provider.moveFile(image.path, dest.absolutePath) }.getOrDefault(false)
        if (!moved) {
            try {
                provider.readFile(image.path).use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                provider.deleteFile(image.path)
            } catch (t: Throwable) {
                Timber.e(t, "Quarantine copy fallback failed for ${image.path}")
                runCatching { dest.delete() } // 清掉可能的半截文件
                return null
            }
        }
        return QuarantineEntity(
            imageId = image.id,
            originalPath = image.path,
            quarantinePath = dest.absolutePath,   // 存完整文件路径：恢复/删除/展示直接用，无需再查图片行
            thumbnailPath = null,                  // 不再预生成缩略图，回收站展示用移动后的原图
            reason = reason,
            ocrSummary = null,
            size = image.size,
            quarantinedAt = System.currentTimeMillis(),
            expiresAt = expiresAt,
        )
    }

    /** 直接从原位置永久删除，不进入回收站。 */
    suspend fun deletePermanently(imageId: Long): Boolean = withContext(Dispatchers.IO) {
        val image = imageDao.getById(imageId) ?: return@withContext false
        val provider = accessManager.current()
        if (!provider.isAvailable()) return@withContext false

        val deleted = runCatching { provider.deleteFile(image.path) }.getOrDefault(false)
        if (deleted) {
            imageDao.markDeleted(listOf(image.id), System.currentTimeMillis())
        } else {
            Timber.w("Permanent delete failed for ${image.path}")
        }
        deleted
    }

    /**
     * 批量从原位置永久删除（图库多选「永久删除」用）：原文件走 provider 批量 deleteFiles
     * （AIDL 原生批删，不逐张起 shell 进程），删成功的批量标记 deleted。返回成功删除的张数。
     */
    suspend fun deletePermanentlyBatch(imageIds: List<Long>): Int = withContext(Dispatchers.IO) {
        if (imageIds.isEmpty()) return@withContext 0
        val provider = accessManager.current()
        if (!provider.isAvailable()) return@withContext 0
        var deleted = 0
        for (chunk in imageIds.chunked(DELETE_CHUNK)) {
            val images = imageDao.getByIds(chunk)
            if (images.isEmpty()) continue
            val results = provider.deleteFiles(images.map { it.path })
            val okIds = images.filterIndexed { i, _ -> results.getOrNull(i) == true }.map { it.id }
            if (okIds.isNotEmpty()) {
                imageDao.markDeleted(okIds, System.currentTimeMillis())
                deleted += okIds.size
            }
        }
        Timber.i("DeletePermanentlyBatch: deleted $deleted/${imageIds.size}")
        deleted
    }

    /** 从回收站恢复。 */
    suspend fun restore(quarantineId: Long): Boolean = withContext(Dispatchers.IO) {
        val record = cleanupDao.getQuarantineById(quarantineId) ?: return@withContext false
        val provider = accessManager.current()
        if (!provider.isAvailable()) return@withContext false

        val image = imageDao.getById(record.imageId) ?: return@withContext false
        val src = quarantineFileOf(record, image)
        if (!src.exists()) return@withContext false
        val ok = provider.moveFile(src.absolutePath, image.path)
        if (ok) {
            imageDao.updateStatus(image.id, "active")
        }
        record.thumbnailPath?.let { File(it).delete() }
        if (ok) cleanupDao.deleteQuarantine(quarantineId)
        ok
    }

    /** 立即从回收站永久删除单条（不可恢复）。批量走 [purgeByRecords]。 */
    suspend fun purge(quarantineId: Long): Boolean = withContext(Dispatchers.IO) {
        val record = cleanupDao.getQuarantineById(quarantineId) ?: return@withContext false
        val image = imageDao.getById(record.imageId)
        quarantineFileOf(record, image).delete()
        record.thumbnailPath?.let { File(it).delete() }
        imageDao.markDeleted(listOf(record.imageId), System.currentTimeMillis())
        cleanupDao.deleteQuarantine(quarantineId)
        true
    }

    /**
     * 批量永久删除回收站记录：删私有目录文件（File API，无需特权）+ 一次性批量标记 deleted +
     * 批量删 quarantine 行。调用方按页（≤ SQLite 变量上限）传入。返回处理的记录数。
     */
    suspend fun purgeByRecords(records: List<QuarantineEntity>): Int = withContext(Dispatchers.IO) {
        if (records.isEmpty()) return@withContext 0
        val imageIds = records.map { it.imageId }
        // 存量记录 quarantinePath 是目录（旧实现），需图片行重建文件名；新记录直接用完整路径
        val needRebuild = records.any { !File(it.quarantinePath).isFile }
        val imageById = if (needRebuild) imageDao.getByIds(imageIds).associateBy { it.id } else emptyMap()
        for (r in records) {
            val file = File(r.quarantinePath).takeIf { it.isFile }
                ?: imageById[r.imageId]?.let { File(rootDir, "${it.id}_${it.sourceType}_${it.filename}") }
            runCatching { file?.delete() }
            r.thumbnailPath?.let { runCatching { File(it).delete() } }
        }
        imageDao.markDeleted(imageIds, System.currentTimeMillis())
        cleanupDao.deleteQuarantineByIds(records.map { it.id })
        Timber.i("PurgeByRecords: purged ${records.size}")
        records.size
    }

    /**
     * 回收站文件位置：新记录 [QuarantineEntity.quarantinePath] 是完整文件路径，直接用；
     * 存量记录该字段是目录，按 `id_sourceType_filename` 命名规则重建。
     */
    private fun quarantineFileOf(record: QuarantineEntity, image: ImageEntity?): File {
        val byPath = File(record.quarantinePath)
        if (byPath.isFile) return byPath
        if (image != null) return File(rootDir, "${image.id}_${image.sourceType}_${image.filename}")
        return byPath
    }

    private suspend fun computeExpiry(): Long {
        val retentionDays = settings.quarantineRetentionDays.first()
        return System.currentTimeMillis() + retentionDays * ONE_DAY_MS
    }

    companion object {
        private const val ONE_DAY_MS = 86_400_000L

        /** 移入回收站并发度：文件移动/拷贝是 IO 密集，并发摊薄逐张串行等待。 */
        private const val CONCURRENCY = 8

        /** 批量永久删除分块：每批一次 provider.deleteFiles + markDeleted（< SQLite 变量上限）。 */
        private const val DELETE_CHUNK = 500
    }
}
