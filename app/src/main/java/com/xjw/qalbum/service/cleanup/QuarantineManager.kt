package com.xjw.qalbum.service.cleanup

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.ParcelFileDescriptor
import androidx.room.withTransaction
import com.xjw.qalbum.access.AccessManager
import com.xjw.qalbum.access.FileAccessProvider
import com.xjw.qalbum.data.db.AppDatabase
import com.xjw.qalbum.data.db.dao.CleanupDao
import com.xjw.qalbum.data.db.dao.ImageDao
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.data.db.entity.QuarantineEntity
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.data.repository.ImageRepository
import com.xjw.qalbum.fileops.BoundFileOps
import com.xjw.qalbum.fileops.FileOpsClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 回收站
 *
 * 删除操作的实际语义：把文件从 chatpic 移动到 App 专属目录 `<externalFilesDir>/quarantine/`
 * （可恢复，默认 30 天后由 [purgeExpired]/QuarantinePurgeWorker 真正删除，
 * 见 [SettingsRepository.quarantineRetentionDays]）。
 *
 * 目录选择：外部专属目录不吃 /data 配额（回收站可达数十 GB），卸载/清除数据行为与 filesDir
 * 一致；存量 filesDir 记录按记录里存的绝对路径解析（[quarantineFileOf]），不做搬迁。
 *
 * 批量移入走 [quarantineBatch]：按 [QUARANTINE_CHUNK] 分块「先写 pending 日志 → 移文件
 * → 原子 finalize」。进程死亡后 [reconcilePending] 根据源/目标存在性继续完成或回滚；块内
 * 文件走批量通道（moveFiles / deleteFiles / AIDL fd 直读），不逐张派生 shell 进程。
 *
 * 缩略图必须由 App 自己生成写入（[thumbDir]）：root 特权 move 进来的原图属 root、App 进程读不了，
 * 不能直接拿移动后的原图展示，故回收站列表/预览都用 App 写的缩略图。
 */
class QuarantineManager(
    private val context: Context,
    private val accessManager: AccessManager,
    private val db: AppDatabase,
    private val imageDao: ImageDao,
    private val cleanupDao: CleanupDao,
    private val settings: SettingsRepository,
    private val fileOpsClient: FileOpsClient,
    private val imageRepository: ImageRepository,
) {

    /** 回收站根目录（外部专属目录；存储不可用时回退 filesDir） */
    private val rootDir: File by lazy {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        File(base, "quarantine").apply { if (!exists()) mkdirs() }
    }

    /** 缩略图目录（App 自己写入，保证 App 可读，用于回收站展示） */
    private val thumbDir: File by lazy {
        File(rootDir, "thumb").apply { if (!exists()) mkdirs() }
    }

    /**
     * 正在被移入/恢复/清除的 imageId。同一图的两次操作指向同一 dest 文件，重入会把先前
     * 成功隔离的真实文件当「半截文件」清掉（物理销毁可恢复照片），故后到者直接跳过。
     */
    private val inFlight: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    /** 把指定图片移到回收站（单张；批量走 [quarantineBatch]）。非 active 的图直接返回 false。 */
    suspend fun quarantine(imageId: Long, reason: String): Boolean =
        quarantineBatch(listOf(imageId to reason)) == 1

    /**
     * 批量移入回收站。每块先持久化 pending 日志并在同一事务重验收藏/用户保留，再处理文件。
     * 文件成功后把日志转 ready、图片转 quarantined；失败项恢复 active。任何崩溃点都有日志可对账。
     */
    suspend fun quarantineBatch(
        items: List<Pair<Long, String>>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Int = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext 0
        val provider = accessManager.current()
        if (!provider.isAvailable()) return@withContext 0

        // 上次进程在文件操作中退出时，先收敛旧日志；未能判定的行保持 pending，不会再次被 claim。
        reconcilePendingWithProvider(provider)

        val total = items.size
        onProgress(0, total)
        val expiresAt = computeExpiry()
        // 目标目录可写性一次性探测：Shizuku shell 写不进的目录，注定失败的逐张 mv 整批跳过
        val dirWritable = try {
            provider.canWriteDir(rootDir.absolutePath)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Timber.w(t, "Quarantine: cannot probe destination directory")
            false
        }
        // 量大时绑定 AIDL：缩略图/拷贝改走 fd 直读（每图一次 binder 往返），替代每图派生 cat 进程
        val bound = if (total >= AIDL_MIN_BATCH) fileOpsClient.bind(provider.getAccessMode()) else null
        val aidlAlive = AtomicBoolean(bound != null)
        var moved = 0
        var done = 0
        try {
            for (chunk in items.chunked(QUARANTINE_CHUNK)) {
                ensureActive()
                val claimed = chunk.mapNotNull { (id, _) -> id.takeIf { inFlight.add(it) } }
                try {
                    val reasonById = chunk.toMap()
                    val journaled = if (claimed.isEmpty()) {
                        emptyList()
                    } else {
                        createQuarantineJournals(claimed, reasonById, expiresAt)
                    }
                    val images = journaled.map { it.image }
                    val secured = quarantineChunk(
                        provider, bound, aidlAlive, dirWritable, images, reasonById, expiresAt,
                    )
                    val securedByImage = secured.associateBy { it.imageId }
                    if (journaled.isNotEmpty()) {
                        // 文件侧结果无论成功/失败都要落库；调用协程取消不能截断 finalize/rollback。
                        withContext(NonCancellable) {
                            db.withTransaction {
                                val successfulIds = ArrayList<Long>()
                                val failedRecordIds = ArrayList<Long>()
                                val failedImageIds = ArrayList<Long>()
                                for (entry in journaled) {
                                    val result = securedByImage[entry.image.id]
                                    if (result != null) {
                                        cleanupDao.markQuarantineReady(entry.record.id, result.thumbnailPath)
                                        successfulIds.add(entry.image.id)
                                    } else {
                                        failedRecordIds.add(entry.record.id)
                                        failedImageIds.add(entry.image.id)
                                    }
                                }
                                if (successfulIds.isNotEmpty()) {
                                    check(imageDao.finalizeQuarantine(successfulIds) == successfulIds.size) {
                                        "Quarantine finalize lost DB claims"
                                    }
                                }
                                if (failedImageIds.isNotEmpty()) {
                                    imageDao.rollbackQuarantinePending(failedImageIds)
                                    cleanupDao.deleteQuarantineByIds(failedRecordIds)
                                }
                            }
                        }
                        moved += securedByImage.size
                    }
                } finally {
                    claimed.forEach { inFlight.remove(it) }
                }
                done += chunk.size
                onProgress(done, total)
            }
        } finally {
            bound?.close()
        }
        Timber.i("QuarantineBatch: moved $moved/$total (dirWritable=$dirWritable, aidl=${bound != null})")
        moved
    }

    /** 在移动任何字节前持久化操作日志；事务内再次验证保护状态，解决审核页快照与执行间竞态。 */
    private suspend fun createQuarantineJournals(
        claimed: List<Long>,
        reasonById: Map<Long, String>,
        expiresAt: Long,
    ): List<JournaledQuarantine> = db.withTransaction {
        val images = imageDao.getCleanupEligibleByIds(claimed)
        if (images.isEmpty()) return@withTransaction emptyList()
        val now = System.currentTimeMillis()
        val drafts = images.map { image ->
            QuarantineEntity(
                imageId = image.id,
                originalPath = image.path,
                quarantinePath = destFileOf(image).absolutePath,
                thumbnailPath = null,
                reason = reasonById[image.id].orEmpty(),
                ocrSummary = null,
                size = image.size,
                quarantinedAt = now,
                expiresAt = expiresAt,
                state = STATE_PENDING,
            )
        }
        val recordIds = cleanupDao.insertQuarantineAll(drafts)
        check(recordIds.size == images.size)
        check(imageDao.markQuarantinePending(images.map { it.id }) == images.size) {
            "Quarantine claim changed while transaction was open"
        }
        images.indices.map { index ->
            JournaledQuarantine(images[index], drafts[index].copy(id = recordIds[index]))
        }
    }

    /**
     * 处理一块（≤ [QUARANTINE_CHUNK]）图片的文件侧工作，返回文件已安置进回收站目录、
     * 可以写库的记录（不碰 DB）。
     *
     * 通道选择：目标目录可写时整块走 [FileAccessProvider.moveFiles]（shell 侧每批一次进程，
     * root 原生 rename）；不可写或个别 mv 失败时走拷贝回退——fd 直读流式拷入，原文件积攒后
     * 一次 [FileAccessProvider.deleteFiles] 批删。删原文件失败的回滚副本、保留原图。
     */
    private suspend fun quarantineChunk(
        provider: FileAccessProvider,
        bound: BoundFileOps?,
        aidlAlive: AtomicBoolean,
        dirWritable: Boolean,
        images: List<ImageEntity>,
        reasonById: Map<Long, String>,
        expiresAt: Long,
    ): List<QuarantineEntity> {
        if (images.isEmpty()) return emptyList()
        // 缩略图基于**原位置**图、由 App 自己写入（特权 move 进来的原图属 root，App 读不了），必须先于移动
        val sem = Semaphore(CONCURRENCY)
        val thumbById = coroutineScope {
            images.map { img ->
                async { sem.withPermit { img.id to generateThumbnail(bound, aidlAlive, provider, img) } }
            }.awaitAll()
        }.toMap()

        val secured = ArrayList<ImageEntity>(images.size)
        val needCopy: List<ImageEntity>
        if (dirWritable) {
            val pairs = images.map { it.path to destFileOf(it).absolutePath }
            val results = try {
                provider.moveFiles(pairs)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Timber.w(t, "Quarantine batch move failed, fallback to copy")
                BooleanArray(images.size)
            }
            images.forEachIndexed { i, img ->
                if (results.getOrNull(i) == true) secured.add(img)
            }
            needCopy = images.filterIndexed { i, _ -> results.getOrNull(i) != true }
        } else {
            needCopy = images
        }

        if (needCopy.isNotEmpty()) {
            val copied = coroutineScope {
                needCopy.map { img ->
                    async { sem.withPermit { img.takeIf { copyIntoQuarantine(bound, aidlAlive, provider, img) } } }
                }.awaitAll()
            }.filterNotNull()
            if (copied.isNotEmpty()) {
                val delResults = try {
                    provider.deleteFiles(copied.map { it.path })
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    Timber.e(t, "Quarantine batch delete originals failed")
                    BooleanArray(copied.size)
                }
                copied.forEachIndexed { i, img ->
                    if (delResults.getOrNull(i) == true) {
                        secured.add(img)
                    } else {
                        // 原文件删不掉却按已隔离处理会双份占用、后续 purge 虚增「已清理」统计：
                        // 回滚副本，本张隔离失败
                        runCatching { destFileOf(img).delete() }
                        Timber.w("Quarantine: delete original failed, rolled back copy for ${img.path}")
                    }
                }
            }
        }

        // 未成功隔离的图，其缩略图一并清掉
        if (secured.size != images.size) {
            val securedIds = secured.mapTo(HashSet()) { it.id }
            thumbById.forEach { (id, path) ->
                if (path != null && id !in securedIds) runCatching { File(path).delete() }
            }
        }

        val now = System.currentTimeMillis()
        return secured.map { img ->
            QuarantineEntity(
                imageId = img.id,
                originalPath = img.path,
                quarantinePath = destFileOf(img).absolutePath, // 完整文件路径：恢复/删除直接用，无需再查图片行
                thumbnailPath = thumbById[img.id],             // App 写的缩略图，回收站展示用
                reason = reasonById[img.id] ?: "",
                ocrSummary = null,
                size = img.size,
                quarantinedAt = now,
                expiresAt = expiresAt,
            )
        }
    }

    /**
     * 拷贝回退：先写 `dest.tmp`、校验字节数后原子 rename 落位。失败只清临时文件，
     * 绝不 delete 正式 dest——同名 dest 可能是先前一次成功隔离的真实文件，删它等于
     * 物理销毁一张「可恢复」的照片。不删原文件（由调用方批删）。
     */
    private suspend fun copyIntoQuarantine(
        bound: BoundFileOps?,
        aidlAlive: AtomicBoolean,
        provider: FileAccessProvider,
        image: ImageEntity,
    ): Boolean {
        val dest = destFileOf(image)
        val tmp = File(dest.parentFile, "${dest.name}.tmp")
        try {
            val input = openSourceStream(bound, aidlAlive, provider, image.path) ?: return false
            input.use { ins -> tmp.outputStream().use { out -> ins.copyTo(out) } }
            // 字节数与库里 size 不符（读取截断/文件已变化）一律失败，原图保持原位
            if (image.size > 0 && tmp.length() != image.size) {
                Timber.w("Quarantine copy size mismatch for ${image.path}: ${tmp.length()} != ${image.size}")
                return false
            }
            // 保留原 mtime（rename 不改 mtime）：恢复后不触发扫描把老照片顶到时间线顶部并重置分析状态
            tmp.setLastModified(image.mtime)
            return tmp.renameTo(dest)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Timber.e(t, "Quarantine copy fallback failed for ${image.path}")
            return false
        } finally {
            if (tmp.exists()) runCatching { tmp.delete() }
        }
    }

    /** 直接从原位置永久删除，不进入回收站。 */
    suspend fun deletePermanently(imageId: Long): Boolean =
        deletePermanentlyBatch(listOf(imageId)) == 1

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
            ensureActive()
            val claimed = chunk.filter { inFlight.add(it) }
            try {
                // 与移入回收站一致，在真正删文件前重验收藏/用户保留，审核页旧快照不能越过硬保护。
                val images = imageDao.getCleanupEligibleByIds(claimed)
                if (images.isEmpty()) continue
                // 文件批删一旦发起就会执行完（不可中断）：结果必须落库，取消不能打断在
                // 「删文件」与「markDeleted」之间，否则真实删除永久漏计「已清理」统计
                deleted += withContext(NonCancellable) {
                    val results = provider.deleteFiles(images.map { it.path })
                    val okIds = images.filterIndexed { i, _ -> results.getOrNull(i) == true }.map { it.id }
                    if (okIds.isNotEmpty()) {
                        imageDao.markDeletedFromStatus(okIds, System.currentTimeMillis(), "active")
                        // 级联清理派生数据（OCR/FTS/标签/向量/排斥）：行保留作统计源，派生必须删
                        imageRepository.purgeDerivedData(okIds)
                    }
                    okIds.size
                }
            } finally {
                claimed.forEach { inFlight.remove(it) }
            }
        }
        Timber.i("DeletePermanentlyBatch: deleted $deleted/${imageIds.size}")
        deleted
    }

    /** 从回收站恢复。 */
    suspend fun restore(quarantineId: Long): Boolean = withContext(Dispatchers.IO) {
        val record = cleanupDao.getQuarantineById(quarantineId) ?: return@withContext false
        if (record.state != STATE_READY) return@withContext false
        val provider = accessManager.current()
        if (!provider.isAvailable()) return@withContext false
        val image = imageDao.getById(record.imageId) ?: return@withContext false
        if (!inFlight.add(image.id)) return@withContext false
        try {
            val src = quarantineFileOf(record, image)
            if (!pathExists(provider, src.absolutePath)) return@withContext false
            val targetPath = resolveRestoreTarget(provider, record.originalPath, image.id)
                ?: return@withContext false

            // 先把目标 path 写入唯一索引并持久化 restoring 日志，阻止应用自身扫描并发插入。
            val claimed = try {
                db.withTransaction {
                    check(cleanupDao.markQuarantineRestoring(record.id) == 1)
                    check(imageDao.markRestorePending(image.id, targetPath) == 1)
                }
                true
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Timber.w(t, "Restore: failed to reserve target $targetPath")
                false
            }
            if (!claimed) return@withContext false

            val moved = try {
                provider.moveFileNoReplace(src.absolutePath, targetPath)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Timber.w(t, "Restore direct move failed for $targetPath")
                false
            }
            val fileResult = if (moved) {
                RestoreFileResult.COMPLETE
            } else {
                copyBackToOrigin(provider, src, image, targetPath)
            }
            when (fileResult) {
                RestoreFileResult.COMPLETE -> {
                    withContext(NonCancellable) {
                        db.withTransaction {
                            check(imageDao.finalizeRestore(image.id, System.currentTimeMillis()) == 1)
                            cleanupDao.deleteQuarantine(quarantineId)
                        }
                    }
                    // 缩略图只在恢复成功后删除：失败时回收站列表仍需展示
                    record.thumbnailPath?.let { runCatching { File(it).delete() } }
                    true
                }
                RestoreFileResult.NOT_WRITTEN -> {
                    withContext(NonCancellable) {
                        db.withTransaction {
                            imageDao.rollbackRestorePending(image.id)
                            cleanupDao.rollbackQuarantineRestore(record.id)
                        }
                    }
                    false
                }
                RestoreFileResult.NEEDS_RECONCILIATION -> {
                    // 目标已完整写回，但隔离源尚未确认删除。绝不能回滚为 ready 后允许再次恢复，
                    // 否则会复制出多份；保留 restoring 日志并立即重试一次，失败则留给启动对账。
                    val pending = cleanupDao.getQuarantineById(record.id)
                    pending != null && reconcileRecord(provider, pending)
                }
            }
        } finally {
            inFlight.remove(image.id)
        }
    }

    /**
     * 恢复的拷贝回退：特权 mv 失败（Shizuku shell 读不了存量 filesDir 私有文件）时，
     * App 自己读隔离文件写到外部中转文件，再特权 mv 送回原路径。全程失败不删任何已有文件。
     */
    private suspend fun copyBackToOrigin(
        provider: FileAccessProvider,
        src: File,
        image: ImageEntity,
        targetPath: String,
    ): RestoreFileResult {
        val staging = File(rootDir, "restore_${image.id}.tmp")
        var targetWritten = false
        try {
            // App 自写的隔离文件直接读；root mv 进来的属 root，走特权通道读
            val input = try {
                src.inputStream()
            } catch (_: Throwable) {
                try {
                    provider.readFile(src.absolutePath)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    Timber.e(t, "Restore fallback: cannot read $src")
                    return RestoreFileResult.NOT_WRITTEN
                }
            }
            input.use { ins -> staging.outputStream().use { out -> ins.copyTo(out) } }
            if (image.size > 0 && staging.length() != image.size) {
                Timber.w("Restore fallback size mismatch for $targetPath: ${staging.length()} != ${image.size}")
                return RestoreFileResult.NOT_WRITTEN
            }
            // mv 保留 mtime：恢复后不触发扫描重置分析状态/时间线错位
            staging.setLastModified(image.mtime)
            if (!provider.moveFileNoReplace(staging.absolutePath, targetPath)) {
                Timber.w("Restore fallback: move back failed for $targetPath")
                return RestoreFileResult.NOT_WRITTEN
            }
            targetWritten = true
            // 送回成功后必须确认隔离源消失。App 看不到 root 属主文件时 File.exists() 会是
            // false，仍需走 provider；仅凭 App File API 的 false 不能当作源已不存在。
            val deletedDirectly = src.exists() && src.delete()
            val sourceGone = deletedDirectly ||
                !providerExists(provider, src.absolutePath, defaultOnError = true) ||
                try {
                    provider.deleteFile(src.absolutePath) &&
                        !providerExists(provider, src.absolutePath, defaultOnError = true)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    Timber.w(t, "Restore fallback: cannot delete quarantine source $src")
                    false
                }
            if (!sourceGone) {
                Timber.w("Restore fallback: target written but quarantine source remains: $src")
                return RestoreFileResult.NEEDS_RECONCILIATION
            }
            return RestoreFileResult.COMPLETE
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Timber.e(t, "Restore fallback failed for $targetPath")
            return if (targetWritten) {
                RestoreFileResult.NEEDS_RECONCILIATION
            } else {
                RestoreFileResult.NOT_WRITTEN
            }
        } finally {
            if (staging.exists()) runCatching { staging.delete() }
        }
    }

    /** 立即从回收站永久删除单条（不可恢复）。批量走 [purgeByRecords]。 */
    suspend fun purge(quarantineId: Long): Boolean = withContext(Dispatchers.IO) {
        val record = cleanupDao.getQuarantineById(quarantineId) ?: return@withContext false
        if (record.state != STATE_READY) return@withContext false
        purgeByRecords(listOf(record)) == 1
    }

    /**
     * 批量永久删除回收站记录：删私有目录文件（File API，无需特权）+ 同事务批量标记 deleted +
     * 批量删 quarantine 行。文件删除失败的记录被保留，下次清理重试（记录一旦删掉，旧格式
     * 文件将永远无法定位，泄漏在私有目录）。调用方按页（≤ SQLite 变量上限）传入。
     * 返回实际清除的记录数。
     */
    suspend fun purgeByRecords(records: List<QuarantineEntity>): Int = withContext(Dispatchers.IO) {
        if (records.isEmpty()) return@withContext 0
        val provider = accessManager.current()
        val purged = ArrayList<QuarantineEntity>(records.size)
        for (r in records) {
            if (r.state != STATE_READY) continue
            // 与 restore/quarantine 互斥：正被操作的图本页跳过（keyset afterId 推进，不会死循环）
            if (!inFlight.add(r.imageId)) continue
            try {
                val file = locateQuarantineFile(r)
                if (file == null) {
                    Timber.e("PurgeByRecords: cannot locate file, keep record ${r.id}")
                    continue
                }
                val exists = file.exists() || providerExists(provider, file.absolutePath, defaultOnError = true)
                val gone = !exists || file.delete() || try {
                    provider.deleteFile(file.absolutePath)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    Timber.w(t, "PurgeByRecords: privileged delete failed for ${file.absolutePath}")
                    false
                }
                if (!gone) {
                    Timber.w("PurgeByRecords: delete failed, keep record ${r.id} (${file?.absolutePath})")
                    continue
                }
                r.thumbnailPath?.let { runCatching { File(it).delete() } }
                purged.add(r)
            } finally {
                inFlight.remove(r.imageId)
            }
        }
        if (purged.isNotEmpty()) {
            withContext(NonCancellable) {
                db.withTransaction {
                    // 守卫 from='quarantined'：逐页清空期间被用户恢复成 active 的行，
                    // 不会被拿着旧页快照的 purge 误标 deleted（status='deleted' 是纯净统计源）
                    imageDao.markDeletedFromStatus(
                        purged.map { it.imageId }, System.currentTimeMillis(), "quarantined",
                    )
                    cleanupDao.deleteQuarantineByIds(purged.map { it.id })
                }
                // 事务提交后级联清理派生数据（OCR/FTS/标签/向量/排斥）；幂等，
                // 与状态事务同处 NonCancellable 块，取消不会打断在两步之间
                imageRepository.purgeDerivedData(purged.map { it.imageId })
            }
        }
        Timber.i("PurgeByRecords: purged ${purged.size}/${records.size}")
        purged.size
    }

    /**
     * 清理已过期（expires_at ≤ now）的回收站记录，让 UI 承诺的「N 天后自动删除」真实生效。
     * keyset 逐页取出走 [purgeByRecords]；删失败被保留的记录 id ≤ afterId，不会原地打转。
     * 由 [com.xjw.qalbum.worker.QuarantinePurgeWorker] 每日调度。
     */
    suspend fun purgeExpired(): Int = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        var purged = 0
        var afterId = 0L
        while (true) {
            ensureActive()
            val page = cleanupDao.listExpiredQuarantinePaged(now, afterId, EXPIRE_PAGE)
            if (page.isEmpty()) break
            afterId = page.last().id
            purged += purgeByRecords(page)
        }
        if (purged > 0) Timber.i("QuarantineManager: purged $purged expired records")
        purged
    }

    /**
     * 对账上次进程退出时留下的文件操作日志。源文件仍在则回滚；目标文件已独占落位则完成；
     * 两边都不存在时保留日志供后续重试/人工诊断，绝不把无法定位的照片静默当成功或删除记录。
     */
    suspend fun reconcilePending(): Int = withContext(Dispatchers.IO) {
        val provider = accessManager.current()
        if (!provider.isAvailable()) return@withContext 0
        reconcilePendingWithProvider(provider)
    }

    private suspend fun reconcilePendingWithProvider(provider: FileAccessProvider): Int {
        var afterId = 0L
        var reconciled = 0
        while (true) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val page = cleanupDao.listPendingQuarantinePaged(afterId, RECONCILE_PAGE)
            if (page.isEmpty()) break
            afterId = page.last().id
            for (record in page) {
                if (!inFlight.add(record.imageId)) continue
                try {
                    if (reconcileRecord(provider, record)) reconciled++
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    Timber.e(t, "Quarantine reconcile failed for record=${record.id}")
                } finally {
                    inFlight.remove(record.imageId)
                }
            }
        }
        if (reconciled > 0) Timber.i("QuarantineManager: reconciled $reconciled interrupted operations")
        return reconciled
    }

    private suspend fun reconcileRecord(
        provider: FileAccessProvider,
        record: QuarantineEntity,
    ): Boolean {
        val image = imageDao.getById(record.imageId) ?: return false
        val quarantineFile = quarantineFileOf(record, image)
        val quarantineExists = pathExists(provider, quarantineFile.absolutePath)
        return when (record.state) {
            STATE_PENDING -> {
                val originalExists = providerExists(provider, record.originalPath, defaultOnError = false)
                when {
                    originalExists -> {
                        // copy 回退可能在删源前退出；保留原图，清理隔离副本并撤销日志。
                        if (quarantineExists && !deletePath(provider, quarantineFile.absolutePath)) return false
                        db.withTransaction {
                            imageDao.rollbackQuarantinePending(listOf(image.id))
                            cleanupDao.deleteQuarantine(record.id)
                        }
                        deterministicThumbnail(image.id).takeIf { it.exists() }?.delete()
                        true
                    }
                    quarantineExists -> {
                        db.withTransaction {
                            val changed = imageDao.finalizeQuarantine(listOf(image.id))
                            check(changed == 1 || image.status == "quarantined")
                            cleanupDao.markQuarantineReady(
                                record.id,
                                deterministicThumbnail(image.id).takeIf { it.exists() }?.absolutePath,
                            )
                        }
                        true
                    }
                    else -> {
                        Timber.e(
                            "Quarantine pending record has neither source nor destination: id=${record.id}, " +
                                "source=${record.originalPath}, destination=${record.quarantinePath}"
                        )
                        false
                    }
                }
            }
            STATE_RESTORING -> {
                val restoreTarget = image.path
                val targetExists = providerExists(provider, restoreTarget, defaultOnError = false)
                when {
                    !quarantineExists && targetExists -> {
                        finishReconciledRestore(record, image)
                        true
                    }
                    quarantineExists && !targetExists -> {
                        db.withTransaction {
                            imageDao.rollbackRestorePending(image.id)
                            cleanupDao.rollbackQuarantineRestore(record.id)
                        }
                        true
                    }
                    quarantineExists && targetExists -> {
                        // copy 回退可能停在“目标写完、隔离源尚未删”；仅在大小吻合时完成。
                        val targetSize = try {
                            provider.getFileInfo(restoreTarget)?.size
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (t: Throwable) {
                            Timber.w(t, "Restore reconcile: cannot stat $restoreTarget")
                            null
                        }
                        if (record.size > 0 && targetSize != record.size) return false
                        if (!deletePath(provider, quarantineFile.absolutePath)) return false
                        finishReconciledRestore(record, image)
                        true
                    }
                    else -> {
                        Timber.e(
                            "Restore pending record has neither source nor target: id=${record.id}, " +
                                "source=${record.quarantinePath}, target=$restoreTarget"
                        )
                        false
                    }
                }
            }
            else -> false
        }
    }

    private suspend fun finishReconciledRestore(record: QuarantineEntity, image: ImageEntity) {
        db.withTransaction {
            val changed = imageDao.finalizeRestore(image.id, System.currentTimeMillis())
            check(changed == 1 || image.status == "active")
            cleanupDao.deleteQuarantine(record.id)
        }
        record.thumbnailPath?.let { runCatching { File(it).delete() } }
        deterministicThumbnail(image.id).takeIf { it.exists() }?.delete()
    }

    /** 原路径被重新下载的新文件占用时，恢复到同目录下确定性的无冲突文件名。 */
    private suspend fun resolveRestoreTarget(
        provider: FileAccessProvider,
        originalPath: String,
        imageId: Long,
    ): String? {
        suspend fun isFree(path: String): Boolean =
            imageDao.getByPath(path) == null && !providerExists(provider, path, defaultOnError = true)

        if (isFree(originalPath)) return originalPath
        val slash = originalPath.lastIndexOf('/')
        val parent = if (slash >= 0) originalPath.substring(0, slash + 1) else ""
        val filename = if (slash >= 0) originalPath.substring(slash + 1) else originalPath
        val dot = filename.lastIndexOf('.').takeIf { it > 0 } ?: filename.length
        val stem = filename.substring(0, dot)
        val extension = filename.substring(dot)
        for (attempt in 0 until RESTORE_NAME_ATTEMPTS) {
            val suffix = if (attempt == 0) "" else "_$attempt"
            val candidate = "$parent${stem}_qalbum_restored_${imageId}$suffix$extension"
            if (isFree(candidate)) {
                Timber.i("Restore target occupied; using $candidate instead of $originalPath")
                return candidate
            }
        }
        return null
    }

    private suspend fun pathExists(provider: FileAccessProvider, path: String): Boolean =
        File(path).exists() || providerExists(provider, path, defaultOnError = false)

    private suspend fun deletePath(provider: FileAccessProvider, path: String): Boolean {
        val file = File(path)
        if (file.exists() && file.delete()) return true
        if (!file.exists() && !providerExists(provider, path, defaultOnError = true)) return true
        return try {
            provider.deleteFile(path)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Timber.w(t, "Quarantine reconcile: cannot delete $path")
            false
        }
    }

    private suspend fun providerExists(
        provider: FileAccessProvider,
        path: String,
        defaultOnError: Boolean,
    ): Boolean = try {
        provider.exists(path)
    } catch (ce: CancellationException) {
        throw ce
    } catch (t: Throwable) {
        Timber.w(t, "Quarantine: cannot stat $path")
        defaultOnError
    }

    private fun deterministicThumbnail(imageId: Long): File = File(thumbDir, "$imageId.jpg")

    /** 回收站内目标文件：按 `id_sourceType_filename` 决定性命名。 */
    private fun destFileOf(image: ImageEntity): File =
        File(rootDir, "${image.id}_${image.sourceType}_${image.filename}")

    /**
     * 回收站文件位置：新记录 [QuarantineEntity.quarantinePath] 是完整文件路径，直接用；
     * 存量记录该字段是目录（旧 filesDir 根），按 `id_sourceType_filename` 在**记录里存的目录**
     * 内重建——根目录已迁外部存储，不能再用当前 rootDir 解析旧记录。
     */
    private fun quarantineFileOf(record: QuarantineEntity, image: ImageEntity): File {
        val byPath = File(record.quarantinePath)
        if (byPath.isFile) return byPath
        if (byPath.isDirectory) return File(byPath, "${image.id}_${image.sourceType}_${image.filename}")
        return byPath
    }

    /**
     * 定位待清除的回收站文件。存量旧记录重建文件名必须用**无状态过滤**的 [ImageDao.getById]：
     * 回收站行 status='quarantined'，带 active 过滤的 getByIds 恒查不到，会导致旧记录的文件
     * 永远删不掉却被清了记录。返回 null = 图片行已不存在、无法定位；调用方必须保留记录。
     */
    private suspend fun locateQuarantineFile(record: QuarantineEntity): File? {
        val byPath = File(record.quarantinePath)
        if (byPath.isFile) return byPath
        if (!byPath.isDirectory) return byPath
        val image = imageDao.getById(record.imageId) ?: return null
        return quarantineFileOf(record, image)
    }

    /**
     * 打开原图读取流：AIDL fd 直读优先（每图一次 binder 往返），binder 失效后整轮降级
     * provider。返回 null = 文件本身打不开（provider 也读不到，不再重试）；provider 通道
     * 的失败按异常抛出，由调用方处理。
     */
    private suspend fun openSourceStream(
        bound: BoundFileOps?,
        aidlAlive: AtomicBoolean,
        provider: FileAccessProvider,
        path: String,
    ): InputStream? {
        if (bound != null && aidlAlive.get()) {
            try {
                val pfd = bound.callWithTimeout(
                    onAbandoned = { pfd: ParcelFileDescriptor? -> pfd?.close() },
                ) { it.openRead(path) }
                return pfd?.let { ParcelFileDescriptor.AutoCloseInputStream(it) }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                aidlAlive.set(false)
                Timber.w(t, "AIDL openRead 失效，本轮剩余读取降级 provider：$path")
            }
        }
        return provider.readFile(path)
    }

    /**
     * 缩略图生成：读原图（fd 直读优先，带上限）→ 降采样解码到 ≤480px → 按 EXIF Orientation
     * 校正 → JPEG 压缩写入 App 私有 [thumbDir]（App 可读）。超过 [THUMB_SOURCE_MAX] 的
     * 超大图直接跳过，避免整读进内存 ×[CONCURRENCY] 并发的瞬时堆压力。
     */
    private suspend fun generateThumbnail(
        bound: BoundFileOps?,
        aidlAlive: AtomicBoolean,
        provider: FileAccessProvider,
        image: ImageEntity,
    ): String? {
        if (image.size > THUMB_SOURCE_MAX) return null
        return try {
            val bytes = openSourceStream(bound, aidlAlive, provider, image.path)
                ?.use { it.readUpTo(THUMB_SOURCE_MAX) }
                ?: return null
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            if (opts.outWidth <= 0) return null
            var sample = 1
            var w = opts.outWidth
            var h = opts.outHeight
            while (w > 480 || h > 480) {
                sample *= 2
                w /= 2; h /= 2
            }
            val opts2 = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts2) ?: return null
            val bitmap = applyExifOrientation(bytes, decoded)
            val dest = File(thumbDir, "${image.id}.jpg")
            dest.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 70, out) }
            bitmap.recycle()
            dest.absolutePath
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Timber.w(t, "Thumbnail generation failed for ${image.path}")
            null
        }
    }

    /**
     * 按原图 EXIF Orientation 旋转/翻转像素：重编码后的 JPEG 不携带方向 tag，不校正则竖拍照片横躺。
     * 用框架 ExifInterface（InputStream 构造 API 24+，minSdk 26 满足），不为此引入 androidx 依赖。
     */
    private fun applyExifOrientation(source: ByteArray, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(source))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f); matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f); matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        val rotated = runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrNull() ?: return bitmap
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    private suspend fun computeExpiry(): Long {
        val retentionDays = settings.quarantineRetentionDays.first()
        return System.currentTimeMillis() + retentionDays * ONE_DAY_MS
    }

    companion object {
        private const val ONE_DAY_MS = 86_400_000L

        /** 移入回收站并发度：缩略图生成/拷贝回退是 IO 密集，并发摊薄逐张串行等待。 */
        private const val CONCURRENCY = 8

        /** 批量移入分块：每块「移文件 → 同事务写库」滚动推进，进程死亡最多损失一块（< SQLite 变量上限）。 */
        private const val QUARANTINE_CHUNK = 200

        /** 批量永久删除分块：每批一次 provider.deleteFiles + markDeleted（< SQLite 变量上限）。 */
        private const val DELETE_CHUNK = 500

        /** 过期清理逐页大小。 */
        private const val EXPIRE_PAGE = 200

        private const val RECONCILE_PAGE = 200
        private const val RESTORE_NAME_ATTEMPTS = 1_000

        private const val STATE_PENDING = "pending"
        private const val STATE_READY = "ready"
        private const val STATE_RESTORING = "restoring"

        /** 达到该量级才绑定 AIDL（绑定约秒级成本；逐图 provider 读每图派生一次进程，量大必须走 fd）。 */
        private const val AIDL_MIN_BATCH = 8

        /** 缩略图源文件读取上限：超过则跳过缩略图，与分析期单图读取上限同级。 */
        private const val THUMB_SOURCE_MAX = 32 * 1024 * 1024
    }

    private data class JournaledQuarantine(
        val image: ImageEntity,
        val record: QuarantineEntity,
    )

    private enum class RestoreFileResult {
        COMPLETE,
        NOT_WRITTEN,
        NEEDS_RECONCILIATION,
    }
}

/** 读流至多 [maxBytes] 字节；空内容返回 null。 */
private fun InputStream.readUpTo(maxBytes: Int): ByteArray? {
    val buffer = ByteArray(64 * 1024)
    val out = java.io.ByteArrayOutputStream(64 * 1024)
    var total = 0
    while (total < maxBytes) {
        val read = read(buffer)
        if (read == -1) break
        val toWrite = minOf(read, maxBytes - total)
        out.write(buffer, 0, toWrite)
        total += toWrite
    }
    return if (total == 0) null else out.toByteArray()
}
