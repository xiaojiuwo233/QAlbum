package com.xjw.qalbum.service.cleanup

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.ParcelFileDescriptor
import androidx.room.withTransaction
import com.xjw.qalbum.access.AccessManager
import com.xjw.qalbum.data.db.AppDatabase
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.data.db.entity.OriginalBackupEntity
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.fileops.BoundFileOps
import com.xjw.qalbum.fileops.FileOpsClient
import com.xjw.qalbum.fileops.FileOpsImpl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 原图压缩：把 JPEG / PNG 按 EXIF 方向转正、缩到长边上限、以指定质量重新编码成 JPEG，原地替换。
 *
 * QQ 只按「文件存在且非空」选用缓存、按内容解码，不核对 MD5 或大小，压缩后的图照常显示（QQ 9.2.50 / 9.3.60 静态分析）。
 *
 * 两种用法：
 * - 先对比：[prepare] 把结果压到缓存目录（不动原图）给用户看，确认后 [apply] 替换，放弃则 [discard]；
 * - 直接压缩：[compress] 逐张压缩并替换，省不到一成的记为不划算，规则不再列出。
 *
 * 替换在特权进程里一次完成（[FileOpsImpl.replaceFileChecked]）：原图先复制成备份（设置可关），新内容写成同目录临时文件，
 * 核对文件没被 QQ 改过后原子改名覆盖，修改时间保持不变。替换前写一条 pending 日志，启动时 [reconcile] 按文件实际状态收尾。
 * 替换后日志转为这张图的压缩记录（质量、前后大小与尺寸，详情里显示）；备份在回收站「原图备份」里可以还原，
 * 到期（与回收站同一保留天数）只删备份、记录留着。
 */
class OriginalCompressor(
    private val context: Context,
    private val db: AppDatabase,
    private val accessManager: AccessManager,
    private val fileOpsClient: FileOpsClient,
    private val settings: SettingsRepository,
    /** 测试用：直接给出文件服务句柄（默认按当前访问方式绑定 Root / Shizuku 服务） */
    private val bindOverride: (suspend () -> BoundFileOps?)? = null,
) {
    private val images = db.imageDao()
    private val backups = db.originalBackupDao()
    private val mutex = Mutex()

    /** 启动对账只清理本进程启动前留下的临时文件，不误删正在对比的结果 */
    private val startedAt = System.currentTimeMillis()

    private val backupDir: File by lazy {
        File(context.getExternalFilesDir(null) ?: context.filesDir, "originals").apply { mkdirs() }
    }

    private val workDir: File by lazy {
        File(context.externalCacheDir ?: context.cacheDir, "compress").apply { mkdirs() }
    }

    private val _changes = MutableSharedFlow<Set<Long>>(extraBufferCapacity = 16)

    /** 文件被替换或还原了的图片 id：看图页据此刷新大小与压缩状态 */
    val changes: SharedFlow<Set<Long>> = _changes.asSharedFlow()

    data class Outcome(val compressed: Int = 0, val skipped: Int = 0, val failed: Int = 0, val savedBytes: Long = 0) {
        operator fun plus(other: Outcome) = Outcome(
            compressed + other.compressed, skipped + other.skipped, failed + other.failed, savedBytes + other.savedBytes,
        )
    }

    /**
     * 压缩出来、还没替换原图的结果（对比用）；[file] 在缓存目录，由 [apply] / [discard] 删除。
     * [originalWidth] / [originalHeight] 是解码时看到的原图尺寸（按显示方向）：库里的宽高可能是旧版分析写入的缩小尺寸。
     */
    data class Prepared(
        val image: ImageEntity,
        val file: File,
        val size: Long,
        val width: Int,
        val height: Int,
        val quality: Int,
        val originalWidth: Int,
        val originalHeight: Int,
    ) {
        val savedBytes: Long get() = image.size - size

        /** PNG 压缩后存成 JPEG（文件名不变，QQ 按内容解码） */
        val fromPng: Boolean get() = image.mime == "image/png"

        /** 省下的比例（0~1） */
        val savedRatio: Float get() = if (image.size > 0) savedBytes.toFloat() / image.size else 0f
    }

    /** 一张图的对比结果 */
    sealed interface Preview {
        val image: ImageEntity

        data class Ready(val prepared: Prepared) : Preview {
            override val image: ImageEntity get() = prepared.image
        }

        /** 不能压缩；[worthless] 为真表示压了也没用（解不了、没变小），直接压缩时记为不划算 */
        data class Unavailable(override val image: ImageEntity, val reason: String, val worthless: Boolean = false) : Preview
    }

    /** 这张图能不能压缩：能则 null，否则是给用户看的原因 */
    fun unsupportedReason(image: ImageEntity): String? = when {
        image.status != "active" && image.status != "archived" -> "图片已不在图库里"
        image.sourceType == "chatthumb" -> "缩略图不压缩"
        image.isAnimated || image.mime == "image/gif" -> "动图不压缩"
        image.mime != "image/jpeg" && image.mime != "image/png" -> "只能压缩 JPEG 和 PNG"
        image.optimized == COMPRESSED -> "已经压缩过"
        else -> null
    }

    /** 文件访问没就绪（未授权 Root / Shizuku）时的原因 */
    suspend fun accessError(): String? {
        if (bindOverride != null) return null
        return withContext(Dispatchers.IO) { if (accessManager.current().isAvailable()) null else NO_ACCESS }
    }

    // ---- 先对比：压到缓存 → 确认后替换 ----

    /** 逐张压缩到缓存目录供对比，不动原图；[onProgress]（已处理张数）每张回调一次 */
    suspend fun prepare(
        targets: List<ImageEntity>, quality: Int, maxSide: Int, onProgress: (Int) -> Unit = {},
    ): List<Preview> = withContext(Dispatchers.IO) {
        withBound { bound ->
            val previews = ArrayList<Preview>(targets.size)
            try {
                targets.forEachIndexed { index, image ->
                    currentCoroutineContext().ensureActive()
                    previews += try {
                        prepareOne(bound, image, quality, maxSide)
                    } catch (e: OutOfMemoryError) {
                        Timber.w(e, "Compress: out of memory preparing ${image.id}")
                        Preview.Unavailable(image, "图片太大，内存不够")
                    }
                    onProgress(index + 1)
                }
            } catch (e: CancellationException) {
                // 换了质量或撤销：已经压好的临时文件一并删掉
                discard(previews.mapNotNull { (it as? Preview.Ready)?.prepared })
                throw e
            }
            previews
        } ?: targets.map { Preview.Unavailable(it, NO_ACCESS) }
    }

    /** 用对比结果替换原图（期间文件被改过的跳过）；临时文件用完删除 */
    suspend fun apply(prepared: List<Prepared>, onProgress: (Int) -> Unit = {}): Outcome = withContext(Dispatchers.IO) {
        try {
            mutex.withLock {
                withBound { bound ->
                    val keep = settings.compressKeepOriginal.first()
                    val expiresAt = expiry()
                    val changed = HashSet<Long>()
                    var outcome = Outcome()
                    prepared.forEachIndexed { index, item ->
                        currentCoroutineContext().ensureActive()
                        val one = replace(bound, item, keep, expiresAt)
                        if (one.compressed > 0) changed += item.image.id
                        outcome += one
                        onProgress(index + 1)
                    }
                    if (changed.isNotEmpty()) _changes.tryEmit(changed)
                    outcome
                } ?: Outcome(failed = prepared.size)
            }
        } finally {
            discard(prepared)
        }
    }

    /** 放弃对比结果：删掉缓存里的压缩文件 */
    fun discard(prepared: Collection<Prepared>) {
        prepared.forEach { it.file.delete() }
    }

    // ---- 直接压缩 ----

    /** 逐张压缩并替换；省不到一成、无法解码的记为不划算（规则不再列出）。[onProgress]（已处理张数）每张回调一次 */
    suspend fun compress(
        ids: List<Long>, quality: Int, maxSide: Int, onProgress: (Int) -> Unit = {},
    ): Outcome = withContext(Dispatchers.IO) {
        mutex.withLock {
            withBound { bound ->
                val keep = settings.compressKeepOriginal.first()
                val expiresAt = expiry()
                val changed = HashSet<Long>()
                var outcome = Outcome()
                ids.forEachIndexed { index, id ->
                    currentCoroutineContext().ensureActive()
                    val one = try {
                        compressOne(bound, id, quality, maxSide, keep, expiresAt)
                    } catch (e: OutOfMemoryError) {
                        Timber.w(e, "Compress: out of memory, skip $id")
                        Outcome(failed = 1)
                    }
                    if (one.compressed > 0) changed += id
                    outcome += one
                    onProgress(index + 1)
                }
                if (changed.isNotEmpty()) _changes.tryEmit(changed)
                outcome
            } ?: Outcome(failed = ids.size)
        }
    }

    private suspend fun compressOne(bound: BoundFileOps, id: Long, quality: Int, maxSide: Int, keep: Boolean, expiresAt: Long): Outcome {
        val image = images.getById(id) ?: return Outcome(skipped = 1)
        return when (val preview = prepareOne(bound, image, quality, maxSide)) {
            is Preview.Unavailable -> {
                if (preview.worthless) images.markOptimized(id, NOT_WORTH)
                Outcome(skipped = 1)
            }
            is Preview.Ready -> {
                val prepared = preview.prepared
                try {
                    if (prepared.size > image.size * MAX_RATIO) {
                        images.markOptimized(id, NOT_WORTH)
                        Outcome(skipped = 1)
                    } else {
                        replace(bound, prepared, keep, expiresAt)
                    }
                } finally {
                    prepared.file.delete()
                }
            }
        }
    }

    // ---- 压缩与替换 ----

    private suspend fun prepareOne(bound: BoundFileOps, image: ImageEntity, quality: Int, maxSide: Int): Preview {
        unsupportedReason(image)?.let { return Preview.Unavailable(image, it) }
        diskStamp(bound, image) ?: return Preview.Unavailable(image, CHANGED)
        val original = readAll(bound, image.path) ?: return Preview.Unavailable(image, "读不到原图")
        if (original.size.toLong() != image.size) return Preview.Unavailable(image, CHANGED)
        var problem: Problem? = null
        val encoded = encode(original, quality, maxSide) { problem = it }
            ?: return Preview.Unavailable(image, (problem ?: Problem.UNDECODABLE).text, worthless = true)
        if (encoded.bytes.size >= image.size) return Preview.Unavailable(image, "压缩后没有变小", worthless = true)
        val file = File(File(workDir, "preview").apply { mkdirs() }, "${image.id}-q$quality-${System.nanoTime()}.jpg")
        file.writeBytes(encoded.bytes)
        return Preview.Ready(
            Prepared(
                image, file, encoded.bytes.size.toLong(), encoded.width, encoded.height, quality,
                originalWidth = encoded.sourceWidth, originalHeight = encoded.sourceHeight,
            ),
        )
    }

    /**
     * 用 [item] 的压缩文件原地替换原图：库里与磁盘上必须还是压缩时那个文件；先写 pending 日志，
     * 特权进程备份并原子替换后写回大小与宽高，日志转为压缩记录。
     */
    private suspend fun replace(bound: BoundFileOps, item: Prepared, keep: Boolean, expiresAt: Long): Outcome {
        val image = item.image
        val current = images.getById(image.id) ?: return Outcome(skipped = 1)
        if (current.size != image.size || current.mtime != image.mtime || unsupportedReason(current) != null) return Outcome(skipped = 1)
        val stamp = diskStamp(bound, image) ?: return Outcome(skipped = 1)
        if (!item.file.isFile || item.file.length() != item.size) return Outcome(failed = 1)

        // 旧记录（例如压缩后 QQ 又重新下载了原图）作废
        backups.getByImageId(image.id)?.let { stale -> discard(bound, stale) }
        val extension = if (image.mime == "image/png") "png" else "jpg"
        val now = System.currentTimeMillis()
        val backupPath = if (keep) File(backupDir, "${image.id}_$now.$extension").path else null
        val record = OriginalBackupEntity(
            imageId = image.id, path = image.path, backupPath = backupPath,
            originalSize = image.size, originalMtime = image.mtime,
            originalWidth = item.originalWidth, originalHeight = item.originalHeight,
            compressedSize = item.size, compressedWidth = item.width, compressedHeight = item.height,
            createdAt = now, expiresAt = expiresAt, state = STATE_PENDING, quality = item.quality,
            originalMime = image.mime,
            // 不保留原图时替换完就腾出来了；保留的等备份删掉
            freedAt = if (backupPath == null) now else null,
        )
        val recordId = backups.insert(record)
        return try {
            val code = ParcelFileDescriptor.open(item.file, ParcelFileDescriptor.MODE_READ_ONLY).use { content ->
                bound.callWithTimeout(timeoutMs = REPLACE_TIMEOUT_MS) {
                    it.replaceFileChecked(image.path, content, backupPath.orEmpty(), stamp, image.mtime)
                }
            }
            withContext(NonCancellable) {
                if (code == FileOpsImpl.REPLACE_OK) {
                    finishReplace(record.copy(id = recordId))
                    Outcome(compressed = 1, savedBytes = image.size - item.size)
                } else {
                    backups.delete(recordId)
                    if (code == FileOpsImpl.REPLACE_CHANGED) Outcome(skipped = 1) else Outcome(failed = 1)
                }
            }
        } catch (e: Exception) {
            // 结果未知（binder 断开等）：日志留着，启动对账时按文件实际状态收尾
            Timber.w(e, "Compress: replace failed for ${image.id}")
            Outcome(failed = 1)
        }
    }

    /** 替换已发生：写回大小与宽高，日志转为压缩记录（没保留原图时 backup_path 为空） */
    private suspend fun finishReplace(record: OriginalBackupEntity) {
        db.withTransaction {
            images.applyOptimization(
                record.imageId, record.compressedSize, record.compressedWidth, record.compressedHeight,
                COMPRESSED, JPEG, record.originalSize, record.originalMtime,
            )
            backups.markReady(record.id)
        }
    }

    /** 磁盘上的文件与库里一致时返回它的 stamp（替换时用来核对没被改过），否则 null */
    private suspend fun diskStamp(bound: BoundFileOps, image: ImageEntity): String? {
        val state = bound.callWithTimeout { it.statFiles(arrayOf(image.path)) }.firstOrNull()?.split('|')
        if (state == null || state.size != 4 || state[0] != "S") return null
        if (state[1].toLongOrNull() != image.size || state[2].toLongOrNull() != image.mtime) return null
        return state[3]
    }

    private suspend fun expiry(): Long = System.currentTimeMillis() + settings.quarantineRetentionDays.first() * DAY_MS

    // ---- 还原 / 删除备份 ----

    /** 还原原图：备份写回原位置，替换压缩后的文件；压缩记录随之删除 */
    suspend fun restore(imageId: Long): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val record = backups.getByImageId(imageId)?.takeIf { it.state == STATE_READY && it.backupPath != null }
                ?: return@withLock false
            withBound { bound ->
                val state = bound.callWithTimeout { it.statFiles(arrayOf(record.path)) }.firstOrNull()?.split('|')
                if (state == null || state.size != 4 || state[0] != "S" || state[1].toLongOrNull() != record.compressedSize) {
                    return@withBound false
                }
                if (backups.markRestoring(record.id) != 1) return@withBound false
                val code = try {
                    bound.callWithTimeout(timeoutMs = REPLACE_TIMEOUT_MS) {
                        it.restoreFileChecked(record.backupPath!!, record.path, state[3], record.originalMtime)
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Compress: restore failed for $imageId")
                    return@withBound false
                }
                withContext(NonCancellable) {
                    if (code == FileOpsImpl.REPLACE_OK) {
                        finishRestore(record)
                        _changes.tryEmit(setOf(imageId))
                        true
                    } else {
                        backups.rollbackRestoring(record.id)
                        false
                    }
                }
            } ?: false
        }
    }

    private suspend fun finishRestore(record: OriginalBackupEntity) {
        db.withTransaction {
            // 还原后不再列入压缩规则（仍可在图库里手动压缩）
            images.applyOptimization(
                record.imageId, record.originalSize, record.originalWidth, record.originalHeight,
                NOT_WORTH, record.originalMime ?: JPEG, record.compressedSize, record.originalMtime,
            )
            backups.delete(record.id)
        }
    }

    /** 删除原图备份（释放空间），压缩记录留着；返回删掉的张数 */
    suspend fun purge(records: List<OriginalBackupEntity>): Int = withContext(Dispatchers.IO) {
        if (records.isEmpty()) return@withContext 0
        mutex.withLock {
            withBound { bound -> records.chunked(PURGE_CHUNK).sumOf { deleteBackups(bound, it) } } ?: 0
        }
    }

    suspend fun purgeByImageIds(imageIds: Collection<Long>): Int =
        purge(imageIds.mapNotNull { backups.getByImageId(it) }.filter { it.state == STATE_READY && it.backupPath != null })

    suspend fun purgeAll(onProgress: (Int) -> Unit = {}): Int {
        var afterId = 0L
        var purged = 0
        while (true) {
            val page = backups.listReady(afterId, PURGE_CHUNK)
            if (page.isEmpty()) break
            afterId = page.last().id
            purged += purge(page)
            onProgress(purged)
        }
        return purged
    }

    /** 到期的原图备份自动删除（QuarantinePurgeWorker 每天一次） */
    suspend fun purgeExpired(): Int {
        val now = System.currentTimeMillis()
        var afterId = 0L
        var purged = 0
        while (true) {
            val page = backups.listExpired(now, afterId, PURGE_CHUNK)
            if (page.isEmpty()) break
            afterId = page.last().id
            purged += purge(page)
        }
        return purged
    }

    private suspend fun deleteBackups(bound: BoundFileOps, records: List<OriginalBackupEntity>): Int {
        val withFiles = records.filter { it.backupPath != null }
        val paths = withFiles.map { it.backupPath!! }
        val gone = if (paths.isEmpty()) BooleanArray(0) else bound.callWithTimeout { it.deleteFiles(paths.toTypedArray()) }
        val done = withFiles.filterIndexed { index, record ->
            gone.getOrElse(index) { false } || File(record.backupPath!!).delete() || !File(record.backupPath).exists()
        }.map { it.id }
        if (done.isNotEmpty()) backups.clearBackupPaths(done, System.currentTimeMillis())
        return done.size
    }

    private suspend fun discard(bound: BoundFileOps, record: OriginalBackupEntity) {
        record.backupPath?.let { path -> runCatching { bound.callWithTimeout { it.deleteFiles(arrayOf(path)) } } }
        backups.delete(record.id)
    }

    // ---- 启动对账 ----

    /** 处理中断的压缩 / 还原：按原位置文件的实际大小判断走到了哪一步 */
    suspend fun reconcile(): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            withBound { bound ->
                var afterId = 0L
                var count = 0
                while (true) {
                    val page = backups.listUnfinished(afterId, PURGE_CHUNK)
                    if (page.isEmpty()) break
                    afterId = page.last().id
                    val states = bound.callWithTimeout { it.statFiles(page.map { r -> r.path }.toTypedArray()) }
                    page.forEachIndexed { i, record ->
                        val parts = states.getOrNull(i)?.split('|').orEmpty()
                        val size = if (parts.firstOrNull() == "S") parts.getOrNull(1)?.toLongOrNull() else null
                        when (record.state) {
                            STATE_PENDING -> when (size) {
                                record.compressedSize -> finishReplace(record)
                                else -> discard(bound, record)
                            }
                            STATE_RESTORING -> when (size) {
                                record.originalSize -> {
                                    finishRestore(record)
                                    record.backupPath?.let { path -> runCatching { bound.callWithTimeout { it.deleteFiles(arrayOf(path)) } } }
                                }
                                record.compressedSize -> backups.rollbackRestoring(record.id)
                                else -> discard(bound, record)
                            }
                        }
                        count++
                    }
                }
                cleanTempFiles()
                count
            } ?: 0
        }
    }

    /**
     * 0.2.1 压缩 / 还原时新文件由 root / shell 创建，属主不是 QQ，QQ 读不到自己的缓存：修正一次。
     * 返回修正的张数；没有文件访问时下次启动再试。
     */
    suspend fun repairOwnership(): Int = withContext(Dispatchers.IO) {
        if (settings.ownershipRepaired.first()) return@withContext 0
        val fixed = withBound { bound ->
            var afterId = 0L
            var count = 0
            while (true) {
                val page = images.optimizedPaths(afterId, PURGE_CHUNK)
                if (page.isEmpty()) break
                afterId = page.last().id
                count += bound.callWithTimeout { it.repairOwnership(page.map { row -> row.path }.toTypedArray()) }
                    .count { it == FileOpsImpl.REPAIR_DONE }
            }
            count
        } ?: return@withContext 0
        settings.setOwnershipRepaired()
        if (fixed > 0) Timber.i("Compress: repaired ownership of $fixed files")
        fixed
    }

    private fun cleanTempFiles() {
        workDir.walkBottomUp().filter { it != workDir }.forEach { file ->
            if (file.isDirectory) file.delete() else if (file.lastModified() < startedAt) file.delete()
        }
    }

    private suspend fun <T> withBound(block: suspend (BoundFileOps) -> T): T? {
        val bound = bindOverride?.invoke() ?: run {
            val provider = accessManager.current()
            if (!provider.isAvailable()) return null
            fileOpsClient.bind(provider.getAccessMode())
        } ?: return null
        return try {
            block(bound)
        } finally {
            bound.close()
        }
    }

    private suspend fun readAll(bound: BoundFileOps, path: String): ByteArray? {
        val pfd = bound.callWithTimeout(onAbandoned = { it?.close() }) { it.openRead(path) } ?: return null
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                if (total > MAX_INPUT_BYTES) return null
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        }
    }

    /** 压缩结果；[sourceWidth] / [sourceHeight] 是原图按显示方向的尺寸 */
    internal class Encoded(val bytes: ByteArray, val width: Int, val height: Int, val sourceWidth: Int, val sourceHeight: Int)

    /** 压不了的原因；直接压缩时记为不划算，规则不再列出 */
    internal enum class Problem(val text: String) {
        UNDECODABLE("无法解码"),
        ANIMATED("动图不压缩"),
        TRANSPARENT("透明 PNG 不压缩"),
    }

    companion object {
        /** optimized 列：已压缩 / 无需压缩（省不到一成、无法解码或已还原） */
        const val COMPRESSED = 1
        const val NOT_WORTH = 2

        /** 直接压缩时至少要比原来小一成才替换 */
        private const val MAX_RATIO = 0.9

        private const val JPEG = "image/jpeg"
        private const val NO_ACCESS = "文件访问未授权，先在「设置 → 文件访问」里授权"
        private const val CHANGED = "文件已变化，重新扫描后再试"

        private const val STATE_PENDING = "pending"
        private const val STATE_READY = "ready"
        private const val STATE_RESTORING = "restoring"
        private const val PURGE_CHUNK = 200
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val REPLACE_TIMEOUT_MS = 60_000L
        private const val MAX_INPUT_BYTES = 80L * 1024 * 1024

        /**
         * 按 EXIF 方向转正、缩到长边 [maxSide]（0 = 不缩小）后以 [quality] 编码成 JPEG；压不了时回调原因并返回 null。
         * PNG 也存成 JPEG：动画 PNG 与有透明像素的（多是表情）不处理。设置页的质量演示也用它，保证与实际压缩一致。
         */
        internal fun encode(bytes: ByteArray, quality: Int, maxSide: Int, onProblem: (Problem) -> Unit = {}): Encoded? {
            val png = isPng(bytes)
            if (png && isAnimatedPng(bytes)) {
                onProblem(Problem.ANIMATED)
                return null
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                onProblem(Problem.UNDECODABLE)
                return null
            }
            val longSide = maxOf(bounds.outWidth, bounds.outHeight)
            var sample = 1
            if (maxSide > 0) while (longSide / (sample * 2) >= maxSide) sample *= 2
            val decoded = BitmapFactory.decodeByteArray(
                bytes, 0, bytes.size,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                },
            ) ?: run {
                onProblem(Problem.UNDECODABLE)
                return null
            }
            if (png && hasTransparency(decoded)) {
                decoded.recycle()
                onProblem(Problem.TRANSPARENT)
                return null
            }
            val orientation = orientation(bytes)
            // 转 90° 的几种方向（5~8）宽高互换
            val upright = orientation !in ExifInterface.ORIENTATION_TRANSPOSE..ExifInterface.ORIENTATION_ROTATE_270
            val sourceWidth = if (upright) bounds.outWidth else bounds.outHeight
            val sourceHeight = if (upright) bounds.outHeight else bounds.outWidth
            var bitmap = oriented(decoded, orientation)
            val currentLong = maxOf(bitmap.width, bitmap.height)
            if (maxSide in 1 until currentLong) {
                val scale = maxSide.toFloat() / currentLong
                val scaled = Bitmap.createScaledBitmap(
                    bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true,
                )
                if (scaled !== bitmap) bitmap.recycle()
                bitmap = scaled
            }
            return try {
                val out = ByteArrayOutputStream(bytes.size / 3)
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) return null
                val encoded = out.toByteArray()
                // 再解一次尺寸，确保写回去的是一张完整可读的图（QQ 解码失败会删掉缓存）
                val check = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(encoded, 0, encoded.size, check)
                if (check.outWidth != bitmap.width || check.outHeight != bitmap.height) null
                else Encoded(encoded, bitmap.width, bitmap.height, sourceWidth, sourceHeight)
            } finally {
                bitmap.recycle()
            }
        }

        private fun isPng(bytes: ByteArray) = bytes.size >= 8 && (bytes[0].toInt() and 0xFF) == 0x89 &&
            bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte()

        /** 动画 PNG：图像数据（IDAT）之前有 acTL 块 */
        private fun isAnimatedPng(bytes: ByteArray): Boolean {
            var offset = 8
            while (offset + 8 <= bytes.size) {
                val length = ((bytes[offset].toInt() and 0xFF) shl 24) or ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                    ((bytes[offset + 2].toInt() and 0xFF) shl 8) or (bytes[offset + 3].toInt() and 0xFF)
                when (String(bytes, offset + 4, 4, Charsets.ISO_8859_1)) {
                    "acTL" -> return true
                    "IDAT" -> return false
                }
                if (length < 0) return false
                offset += 12 + length
            }
            return false
        }

        /** 有没有不透明度不满的像素（逐行读，不整张复制） */
        private fun hasTransparency(bitmap: Bitmap): Boolean {
            if (!bitmap.hasAlpha()) return false
            val row = IntArray(bitmap.width)
            for (y in 0 until bitmap.height) {
                bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
                if (row.any { it ushr 24 != 0xFF }) return true
            }
            return false
        }

        private fun orientation(bytes: ByteArray): Int = try {
            ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }

        private fun oriented(src: Bitmap, orientation: Int): Bitmap {
            val m = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
                else -> return src
            }
            val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
            if (out !== src) src.recycle()
            return out
        }
    }
}
