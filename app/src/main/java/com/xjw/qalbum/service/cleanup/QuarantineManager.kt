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
import com.xjw.qalbum.qq.QqSourceScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
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
 * 批量移入走 [quarantineBatch]：分组「先写 pending 日志 → 移文件
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
    private val archive: ArchiveManager,
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

    /**
     * 删除确认弹窗用的事实（只查库，不碰文件，选得再多也很快）：所选图片自身大小、连同其他版本的大小、
     * 有其他版本的张数、受保护（归档 / 保留）的张数、其他聊天也在用的张数，以及文件访问是否就绪。
     * 「共用」与执行时的判断一致：不限范围时出现在不止一个会话 / 发送人里，限定来源时出现在范围之外。
     * 实际删除时仍逐组核对文件与关联（见 [PictureFamilyPlanner]）。[includeArchived] 为归档页删除：归档不算保护。
     */
    suspend fun deleteFacts(ids: Collection<Long>, scope: QqSourceScope? = null, includeArchived: Boolean = false): PictureDeleteFacts =
        withContext(Dispatchers.IO) {
            val rows = ids.distinct().chunked(SQL_CHUNK).flatMap { imageDao.deleteFactRows(it) }
            val members = rows.mapNotNullTo(HashSet()) { it.variantGroup }.chunked(SQL_CHUNK)
                .flatMap { imageDao.familyMemberSizes(it, includeArchived) }
            val familyIds = members.groupBy({ it.variantGroup }, { it.id })
            val sizes = members.associate { it.id to it.size } + rows.associate { it.id to it.size }
            fun protected(row: ImageDao.DeleteFactRow) = row.isKept || (row.isArchived && !includeArchived)
            val open = rows.filterNot(::protected)
            val sources = db.qqSourceDao()
            val sharedPaths = open.map { it.path }.chunked(PATH_CHUNK).flatMapTo(HashSet()) { page ->
                if (scope == null) sources.pathsInSeveralContexts(page) else sources.pathsOutsideScope(page, scope.groupKey, scope.senderKey)
            }
            PictureDeleteFacts(
                count = rows.size,
                bytes = rows.sumOf { it.size },
                versionBytes = sizes.values.sum(),
                withVersions = rows.count { row -> familyIds[row.variantGroup].orEmpty().any { it != row.id } },
                protectedCount = rows.count(::protected),
                shared = open.count { it.path in sharedPaths },
                accessError = fileAccessError(),
            )
        }

    /** 文件访问是否就绪：未就绪时返回可读原因（大量选择时删除弹窗用它代替逐个核对） */
    suspend fun fileAccessError(): String? = withContext(Dispatchers.IO) {
        if (accessManager.current().isAvailable()) null else "文件访问未授权，请先在设置中连接 Root 或 Shizuku"
    }

    /** [onProgress]（已处理的所选张数, 总数）每批回调一次，供 UI 显示进度 */
    suspend fun quarantinePictures(
        ids: Collection<Long>, options: PictureDeleteOptions, reason: String, scope: QqSourceScope? = null,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): PictureDeleteResult = performPictures(ids,options,reason,scope,permanent=false,onProgress)

    suspend fun deletePicturesPermanently(
        ids: Collection<Long>, options: PictureDeleteOptions, scope: QqSourceScope? = null,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): PictureDeleteResult = performPictures(ids,options,"",scope,permanent=true,onProgress)

    /** 按规划器的分块（约 200 张一批）整批处理：每批只有几个事务和几次 binder 调用，不逐组提交 */
    private suspend fun performPictures(
        ids: Collection<Long>, options: PictureDeleteOptions, reason: String, scope: QqSourceScope?, permanent: Boolean,
        onProgress: (done: Int, total: Int) -> Unit,
    ): PictureDeleteResult =
        withContext(Dispatchers.IO) {
            val provider=accessManager.current()
            if(!provider.isAvailable())return@withContext PictureDeleteResult(skipped=ids.size)
            val bound=fileOpsClient.bind(provider.getAccessMode())?:return@withContext PictureDeleteResult(skipped=ids.size)
            var result=PictureDeleteResult()
            val total=ids.size
            var done=0
            try {
                onProgress(0,total)
                reconcilePendingWithProvider(provider)
                val operations=linkedOperations(bound,provider)
                val expiry=computeExpiry()
                PictureFamilyPlanner(db,settings.chatpicPath.first()).batches(ids,options,bound,scope) { groups ->
                    val open=groups.filter { it.blocked==null }
                    result+=PictureDeleteResult(skipped=groups.size-open.size)
                    if(open.isNotEmpty())result+=if(permanent)operations.permanentlyDeleteAll(open,options.includeArchived)
                        else operations.quarantineAll(open,reason,expiry,options.includeArchived)
                    done+=groups.sumOf { it.selectedIds.size }
                    onProgress(done.coerceAtMost(total),total)
                }
                result
            } catch(e:CancellationException) { throw e }
            catch(_:Exception) { result.copy(partial=result.partial+1) }
            finally {
                bound.close()
                // 归档页删除：放回原处（核验失败回滚）的归档图按设置重新隐藏
                if(options.includeArchived)runCatching { archive.sync() }
                onProgress(total,total)
            }
        }

    internal fun linkedOperations(bound: BoundFileOps, provider: FileAccessProvider): LinkedPictureOperations = LinkedPictureOperations(
        db,bound,rootDir,inFlight,
        thumbnail={ image -> generateThumbnail(bound,AtomicBoolean(true),provider,image) },
        purgeDerived={ ids -> imageRepository.purgeDerivedData(ids) },
    )

    /** 把指定图片移到回收站（单张；批量走 [quarantineBatch]）。非 active 的图直接返回 false。 */
    suspend fun quarantine(imageId: Long, reason: String): Boolean =
        quarantineBatch(listOf(imageId to reason)) == 1

    /**
     * 批量移入回收站。每块先持久化 pending 日志并在同一事务重验归档/用户保留，再处理文件。
     * 文件成功后把日志转 ready、图片转 quarantined；失败项恢复 active。任何崩溃点都有日志可对账。
     */
    suspend fun quarantineBatch(
        items: List<Pair<Long, String>>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        options: PictureDeleteOptions = PictureDeleteOptions(),
    ): Int = quarantinePictures(items.map { it.first },options,items.firstOrNull()?.second.orEmpty(),onProgress=onProgress).selectedProcessed

    /** 直接从原位置永久删除，不进入回收站。 */
    suspend fun deletePermanently(imageId: Long): Boolean =
        deletePermanentlyBatch(listOf(imageId)) == 1

    /**
     * 批量从原位置永久删除（图库多选「永久删除」用）：原文件走 provider 批量 deleteFiles
     * （AIDL 原生批删，不逐张起 shell 进程），删成功的批量标记 deleted。返回成功删除的张数。
     */
    suspend fun deletePermanentlyBatch(imageIds: List<Long>): Int = deletePicturesPermanently(imageIds,PictureDeleteOptions()).selectedProcessed

    /** 从回收站恢复；从归档里删除的图恢复后回到归档（按设置重新隐藏） */
    suspend fun restore(quarantineId: Long): Boolean =
        restoreRecord(quarantineId).also { restored -> if (restored) runCatching { archive.sync() } }

    private suspend fun restoreRecord(quarantineId: Long): Boolean = withContext(Dispatchers.IO) {
        val record = cleanupDao.getQuarantineById(quarantineId) ?: return@withContext false
        if (record.state != STATE_READY) return@withContext false
        val provider = accessManager.current()
        if (!provider.isAvailable()) return@withContext false
        if (record.groupId != null) {
            val bound=fileOpsClient.bind(provider.getAccessMode())?:return@withContext false
            try { return@withContext linkedOperations(bound,provider).restore(cleanupDao.getQuarantineGroup(record.groupId)) }
            finally { bound.close() }
        }
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
        val members=record.groupId?.let { cleanupDao.getQuarantineGroup(it) }?:listOf(record)
        purgeIndividualRecords(members).size==members.size
    }

    /**
     * 批量永久删除回收站记录：删私有目录文件（File API，无需特权；删不掉的再交给特权进程一次批删）+
     * 同一事务批量标记 deleted、批量删 quarantine 行。文件删除失败的记录被保留，下次清理重试（记录一旦删掉，
     * 旧格式文件将永远无法定位，泄漏在私有目录）。[records] 每组一行代表记录，调用方按页（200 组左右）传入；
     * 返回整组清除的组数。
     */
    suspend fun purgeByRecords(records: List<QuarantineEntity>): Int = withContext(Dispatchers.IO) {
        val units=records.distinctBy { it.groupId?:"legacy:${it.id}" }
        val members=units.mapNotNull { it.groupId }.chunked(SQL_CHUNK).flatMap { cleanupDao.getQuarantineGroups(it) }.groupBy { it.groupId }
        val groups=units.map { record->record.groupId?.let { members[it].orEmpty() }?:listOf(record) }.filter { it.isNotEmpty() }
        val purged=purgeIndividualRecords(groups.flatten()).mapTo(HashSet()) { it.id }
        groups.count { group->group.all { it.id in purged } }
    }

    private suspend fun purgeIndividualRecords(records: List<QuarantineEntity>): List<QuarantineEntity> = withContext(Dispatchers.IO) {
        if (records.isEmpty()) return@withContext emptyList()
        val purged = ArrayList<QuarantineEntity>(records.size)
        // 与 restore/quarantine 互斥：正被操作的图本页跳过（keyset afterId 推进，不会死循环）
        val claimed = records.filter { it.state == STATE_READY && inFlight.add(it.imageId) }
        try {
            val leftover = ArrayList<Pair<QuarantineEntity, File>>()
            for (r in claimed) {
                val file = locateQuarantineFile(r)
                if (file == null) {
                    Timber.e("PurgeByRecords: cannot locate file, keep record ${r.id}")
                    continue
                }
                if (file.exists() && file.delete()) purged.add(r) else leftover.add(r to file)
            }
            // App 看不到或删不掉的文件（特权进程移入的）：一次批删，「本就不存在」也算删除
            if (leftover.isNotEmpty()) {
                val gone = deleteLeftovers(leftover.map { it.second.absolutePath })
                leftover.forEachIndexed { i, (r, file) ->
                    if (gone[i]) purged.add(r) else Timber.w("PurgeByRecords: delete failed, keep record ${r.id} (${file.absolutePath})")
                }
            }
            purged.forEach { r -> r.thumbnailPath?.let { runCatching { File(it).delete() } } }
            if (purged.isNotEmpty()) {
                withContext(NonCancellable) {
                    db.withTransaction {
                        // 守卫 from='quarantined'：逐页清空期间被用户恢复成 active 的行，
                        // 不会被拿着旧页快照的 purge 误标 deleted（status='deleted' 是纯净统计源）
                        val now = System.currentTimeMillis()
                        purged.map { it.imageId }.chunked(SQL_CHUNK).forEach { imageDao.markDeletedFromStatus(it, now, "quarantined") }
                        purged.map { it.id }.chunked(SQL_CHUNK).forEach { cleanupDao.deleteQuarantineByIds(it) }
                    }
                    // 事务提交后级联清理派生数据（OCR/FTS/标签/向量/排斥）；幂等，
                    // 与状态事务同处 NonCancellable 块，取消不会打断在两步之间
                    imageRepository.purgeDerivedData(purged.map { it.imageId })
                }
            }
        } finally {
            claimed.forEach { inFlight.remove(it.imageId) }
        }
        Timber.i("PurgeByRecords: purged ${purged.size}/${records.size}")
        purged
    }

    /** 特权批删（已删或本就不存在 = true）；服务连不上时逐个走 provider */
    private suspend fun deleteLeftovers(paths: List<String>): List<Boolean> {
        val provider = accessManager.current()
        val bound = if (provider.isAvailable()) fileOpsClient.bind(provider.getAccessMode()) else null
        if (bound != null) {
            try {
                return paths.chunked(PATH_CHUNK).flatMap { page -> bound.callWithTimeout { it.deleteFiles(page.toTypedArray()) }.toList() }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Timber.w(t, "PurgeByRecords: batch delete failed, falling back to provider")
            } finally {
                bound.close()
            }
        }
        return paths.map { path ->
            !providerExists(provider, path, defaultOnError = true) || try {
                provider.deleteFile(path)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Timber.w(t, "PurgeByRecords: privileged delete failed for $path")
                false
            }
        }
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
        // 同组记录按组整体对账，一组只处理一次
        val groups = HashSet<String>()
        while (true) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val page = cleanupDao.listPendingQuarantinePaged(afterId, RECONCILE_PAGE)
            if (page.isEmpty()) break
            afterId = page.last().id
            for (record in page) {
                if (record.sourceStamp != null) {
                    if (record.groupId != null && !groups.add(record.groupId)) continue
                    val bound=fileOpsClient.bind(provider.getAccessMode())?:continue
                    try { linkedOperations(bound,provider).reconcileByRecord(record) }
                    finally { bound.close() }
                    continue
                }
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
     * 缩略图生成：读源图（fd 直读优先，带上限；批量移入时每组只取最小的可用版本）→ 降采样解码到 ≤480px →
     * 按 EXIF Orientation 校正 → JPEG 压缩写入 App 私有 [thumbDir]（App 可读）；本身就很小的图原样存下。
     * 超过 [THUMB_SOURCE_MAX] 的超大图直接跳过，避免整读进内存的瞬时堆压力。
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
            val dest = File(thumbDir, "${image.id}.jpg")
            // 本身就是小图（QQ 的缩略图）：原样存下，显示时图片加载器会按 EXIF 方向解码，省掉一次解码和重新压缩
            if (bytes.size <= THUMB_COPY_MAX_BYTES && maxOf(opts.outWidth, opts.outHeight) <= THUMB_COPY_MAX_PX) {
                dest.writeBytes(bytes)
                return dest.absolutePath
            }
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

        /** IN 查询分块（低版本系统 SQLite 变量上限 999） */
        private const val SQL_CHUNK = 900
        private const val PATH_CHUNK = 200

        /** 过期清理逐页大小。 */
        private const val EXPIRE_PAGE = 200

        private const val RECONCILE_PAGE = 200
        private const val RESTORE_NAME_ATTEMPTS = 1_000

        private const val STATE_PENDING = "pending"
        private const val STATE_READY = "ready"
        private const val STATE_RESTORING = "restoring"

        /** 缩略图源文件读取上限：超过则跳过缩略图，与分析期单图读取上限同级。 */
        private const val THUMB_SOURCE_MAX = 32 * 1024 * 1024

        /** 不超过这个大小与边长的源图直接当缩略图存下 */
        private const val THUMB_COPY_MAX_BYTES = 160 * 1024
        private const val THUMB_COPY_MAX_PX = 640
    }

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
