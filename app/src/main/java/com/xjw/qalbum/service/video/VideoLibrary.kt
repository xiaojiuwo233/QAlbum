package com.xjw.qalbum.service.video

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.DeadObjectException
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.room.withTransaction
import com.xjw.qalbum.access.AccessManager
import com.xjw.qalbum.analytics.Analytics
import com.xjw.qalbum.analytics.elapsedMs
import com.xjw.qalbum.analytics.savedToAlbum
import com.xjw.qalbum.analytics.videoScanned
import com.xjw.qalbum.analytics.videosDeleted
import com.xjw.qalbum.data.db.AppDatabase
import com.xjw.qalbum.data.db.entity.VideoEntity
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.fileops.BoundFileOps
import com.xjw.qalbum.fileops.FileOpsClient
import com.xjw.qalbum.fileops.FileOpsImpl
import com.xjw.qalbum.fileops.HeaderBatchCodec
import com.xjw.qalbum.qq.QqStorageRoots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.IOException

/**
 * 视频页的数据：QQ 聊天里的视频（Tencent/MobileQQ/shortvideo）与收到的文件里的视频（Tencent/QQfile_recv）。
 * 两处都在 QQ 的 Android/data 目录里，Shizuku 就够用。打开视频页时扫描入库（[scan]），之后在后台补上时长与尺寸
 * （[fillMetadata]）；删除是永久删除（视频动辄几十上百 MB，移进回收站腾不出空间），删前核对文件还是扫描时那个。
 */
class VideoLibrary(
    private val context: Context,
    private val db: AppDatabase,
    private val accessManager: AccessManager,
    private val fileOpsClient: FileOpsClient,
    private val settings: SettingsRepository,
    private val analytics: Analytics? = null,
) {
    private val dao = db.videoDao()

    /** 扫描结果：找到的视频数；文件访问没就绪时 [noAccess] */
    data class ScanReport(val found: Int, val noAccess: Boolean = false)

    /** 删除结果：删掉的个数与大小、文件已经不在（只从列表去掉）的个数、文件变了或删不掉的个数 */
    data class DeleteReport(val deleted: Int, val bytes: Long, val gone: Int, val failed: Int, val noAccess: Boolean = false)

    private data class Found(val path: String, val size: Long, val mtime: Long)

    /**
     * 扫描一遍，扫完回调 [onScanned]，再接着补上还没读过的时长与尺寸（同一个连接，Shizuku 不必重启服务进程）。
     * [includeFiles] 为假时不扫收到的文件（QQfile_recv），已有的记录留着，页面上不显示。
     */
    suspend fun refresh(includeFiles: Boolean, onScanned: (ScanReport) -> Unit) = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        val done = withOps { bound ->
            val report = ScanReport(scan(bound, QqStorageRoots.fromChatpic(settings.chatpicPath.first()), includeFiles))
            analytics?.videoScanned(report.found, includeFiles, elapsedMs(started), ok = true)
            onScanned(report)
            val filled = fillMetadata(bound, sourcesOf(includeFiles))
            if (filled > 0) Timber.i("Video metadata read: %d", filled)
        }
        if (done == null) {
            analytics?.videoScanned(0, includeFiles, elapsedMs(started), ok = false)
            onScanned(ScanReport(0, noAccess = true))
        }
    }

    /** 扫描 [roots] 下的聊天视频（[includeFiles] 时还有收到的视频文件）并入库，返回找到的个数 */
    internal suspend fun scan(bound: BoundFileOps, roots: QqStorageRoots, includeFiles: Boolean = true): Int {
        var found = 0
        val folders = listOf(VideoEntity.SOURCE_CHAT to roots.shortVideo, VideoEntity.SOURCE_FILE to roots.fileRecv)
        for ((source, root) in folders.filter { includeFiles || it.first == VideoEntity.SOURCE_CHAT }) {
            // 读不了（权限变了、出错）就保留上次的结果；目录不存在就是没有视频
            val state = bound.callWithTimeout { it.statFiles(arrayOf(root)) }.firstOrNull()
            if (state != "O" && state != "M") continue
            val files = if (state == "M") emptyList() else walk(bound, root)
            val videos = detect(bound, files)
            save(source, videos)
            found += videos.size
        }
        Timber.i("Videos scanned: %d", found)
        return found
    }

    private suspend fun walk(bound: BoundFileOps, root: String): List<Found> {
        val files = ArrayList<Found>()
        var offset = 0
        while (true) {
            val page = bound.callWithTimeout(WALK_TIMEOUT_MS) { it.walkFiles(root, offset, WALK_PAGE) }
            if (page.isEmpty()) break
            var more = false
            for (entry in page) {
                if (entry == FileOpsImpl.MORE_MARKER) {
                    more = true
                    continue
                }
                val parts = entry.split('|', limit = 3)
                if (parts.size < 3) continue
                files += Found("$root/${parts[2]}", parts[0].toLongOrNull() ?: continue, parts[1].toLongOrNull() ?: continue)
            }
            if (!more) break
            offset += WALK_PAGE
        }
        return files
    }

    /** 有视频后缀的直接算；没有认得的后缀、又够大的读文件头认 */
    private suspend fun detect(bound: BoundFileOps, files: List<Found>): List<Found> {
        val byName = files.filter { VideoFiles.hasVideoExtension(it.path) && it.size > 0 }
        val unknown = files.filter { VideoFiles.needsProbe(it.path, it.size) }
        val byHeader = unknown.chunked(HEADER_CHUNK).flatMap { chunk ->
            val blob = bound.callWithTimeout(HEADER_TIMEOUT_MS) { it.readHeaders(chunk.map(Found::path).toTypedArray(), VideoFiles.HEADER_BYTES) }
            val headers = HeaderBatchCodec.unpack(blob, chunk.size)
            chunk.filterIndexed { i, _ -> VideoFiles.isVideoHeader(headers.getOrNull(i)) }
        }
        return byName + byHeader
    }

    /** 入库：新文件插入，大小或修改时间变了的重读时长，没变的只记一下看到了，这次没看到的删掉 */
    private suspend fun save(source: String, videos: List<Found>) {
        val now = System.currentTimeMillis()
        db.withTransaction {
            val existing = dao.bySource(source).associateBy { it.path }
            val inserts = ArrayList<VideoEntity>()
            val changed = ArrayList<VideoEntity>()
            val same = ArrayList<Long>()
            for (video in videos) {
                val old = existing[video.path]
                when {
                    old == null -> inserts += VideoEntity(path = video.path, source = source, size = video.size, mtime = video.mtime, seenAt = now)
                    old.size != video.size || old.mtime != video.mtime ->
                        changed += VideoEntity(id = old.id, path = video.path, source = source, size = video.size, mtime = video.mtime, seenAt = now)
                    else -> same += old.id
                }
            }
            inserts.chunked(SQL_CHUNK).forEach { dao.insertAll(it) }
            if (changed.isNotEmpty()) dao.updateAll(changed)
            same.chunked(SQL_CHUNK).forEach { dao.touch(it, now) }
            dao.deleteUnseen(source, now)
        }
    }

    /** 补上 [sources] 里还没读过的时长与尺寸，返回读了几个 */
    internal suspend fun fillMetadata(bound: BoundFileOps, sources: List<String> = sourcesOf(true)): Int {
        val tried = HashSet<Long>()
        while (true) {
            val batch = dao.pendingMetadata(sources, METADATA_BATCH).filter { it.id !in tried }
            if (batch.isEmpty()) break
            for (video in batch) {
                currentCoroutineContext().ensureActive()
                tried += video.id
                val meta = openWith(bound, video.path)?.use { VideoProbe.meta(it.fileDescriptor) }
                dao.setMetadata(video.id, video.size, video.mtime, meta?.durationMs ?: 0, meta?.width ?: 0, meta?.height ?: 0)
            }
        }
        return tried.size
    }

    /** 只读打开视频（播放 / 取帧）：App 自己读得到的直接打开，否则经特权进程拿 fd；打不开为 null */
    suspend fun open(path: String): ParcelFileDescriptor? = withContext(Dispatchers.IO) {
        val own = File(path)
        if (own.canRead()) return@withContext runCatching { ParcelFileDescriptor.open(own, ParcelFileDescriptor.MODE_READ_ONLY) }.getOrNull()
        try {
            withOps { bound -> openWith(bound, path) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "打开视频失败")
            null
        }
    }

    /** 打开失败（文件没了、读不了、调用超时）为 null；连接断了照常抛出，由 [withOps] 重连 */
    private suspend fun openWith(bound: BoundFileOps, path: String): ParcelFileDescriptor? = try {
        bound.callWithTimeout(onAbandoned = { pfd: ParcelFileDescriptor? -> pfd?.close() }) { it.openRead(path) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: DeadObjectException) {
        throw e
    } catch (e: Exception) {
        Timber.w(e, "打开视频失败")
        null
    }

    /** 永久删除：核对文件还是扫描时那个（大小、修改时间没变）再删；文件已经不在的只从列表去掉 */
    suspend fun delete(ids: Collection<Long>, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): DeleteReport =
        withContext(Dispatchers.IO) {
            val started = System.nanoTime()
            (withOps { bound -> delete(ids, bound, onProgress) } ?: DeleteReport(0, 0, 0, ids.size, noAccess = true)).also { report ->
                analytics?.videosDeleted(ids.size, report.deleted + report.gone, report.failed, report.bytes, elapsedMs(started))
            }
        }

    internal suspend fun delete(ids: Collection<Long>, bound: BoundFileOps, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): DeleteReport {
        val rows = ids.distinct().chunked(SQL_CHUNK).flatMap { dao.byIds(it) }
        var deleted = 0
        var bytes = 0L
        var gone = 0
        var failed = 0
        var done = 0
        onProgress(0, rows.size)
        for (chunk in rows.chunked(DELETE_CHUNK)) {
            val states = bound.callWithTimeout { it.statFiles(chunk.map(VideoEntity::path).toTypedArray()) }
            val removed = ArrayList<Long>()
            val targets = ArrayList<Pair<VideoEntity, String>>()
            chunk.forEachIndexed { i, video ->
                val state = states.getOrNull(i).orEmpty()
                val parts = state.split('|')
                when {
                    state == "M" -> {
                        removed += video.id
                        gone++
                    }
                    // S|大小|修改时间|stamp：还是扫描时那个文件才删
                    parts.size == 4 && parts[0] == "S" && parts[1].toLongOrNull() == video.size && parts[2].toLongOrNull() == video.mtime ->
                        targets += video to parts[3]
                    else -> failed++
                }
            }
            if (targets.isNotEmpty()) {
                val results = bound.callWithTimeout {
                    it.deleteFilesChecked(targets.map { t -> t.first.path }.toTypedArray(), targets.map { t -> t.second }.toTypedArray())
                }
                targets.forEachIndexed { i, (video, _) ->
                    if (results.getOrElse(i) { false }) {
                        removed += video.id
                        deleted++
                        bytes += video.size
                    } else {
                        failed++
                    }
                }
            }
            if (removed.isNotEmpty()) dao.deleteByIds(removed)
            done += chunk.size
            onProgress(done, rows.size)
        }
        Timber.i("Videos deleted: %d (%d bytes), gone=%d failed=%d", deleted, bytes, gone, failed)
        return DeleteReport(deleted, bytes, gone, failed)
    }

    /** 保存到系统相册 Pictures/QAlbum[/子目录]（与保存图片同一个相册） */
    suspend fun export(id: Long, subdir: String?): Boolean = withContext(Dispatchers.IO) {
        val video = dao.byIds(listOf(id)).firstOrNull() ?: return@withContext false
        var uri: Uri? = null
        try {
            val input = open(video.path) ?: return@withContext false
            ParcelFileDescriptor.AutoCloseInputStream(input).use { stream ->
                val name = video.filename.let { if (VideoFiles.hasVideoExtension(it)) it else "$it.mp4" }
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, name)
                    put(MediaStore.Video.Media.MIME_TYPE, VideoFiles.mimeOf(name))
                    put(MediaStore.Video.Media.DATE_TAKEN, video.mtime)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val folder = buildString {
                            append("Pictures/QAlbum")
                            subdir?.let { SettingsRepository.sanitizeExportSubdir(it) }?.let { append("/").append(it) }
                        }
                        put(MediaStore.Video.Media.RELATIVE_PATH, folder)
                        put(MediaStore.Video.Media.IS_PENDING, 1)
                    }
                }
                uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext false
                val target = uri!!
                context.contentResolver.openOutputStream(target)?.use { out -> stream.copyTo(out) }
                    ?: throw IOException("openOutputStream returned null for $target")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    context.contentResolver.update(target, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                }
            }
            true
        } catch (e: CancellationException) {
            uri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            throw e
        } catch (t: Throwable) {
            uri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            Timber.e(t, "保存视频失败")
            false
        }
    }.also { ok -> analytics?.savedToAlbum("video", "video", 1, if (ok) 1 else 0) }

    /**
     * 连上特权进程执行 [block]；连接中途断了（Shizuku 服务进程被回收、App 更新后旧进程退出）就重连再做一遍——
     * 扫描、补时长、删除都可以重做。文件访问没就绪时为 null。
     */
    private suspend fun <T> withOps(block: suspend (BoundFileOps) -> T): T? {
        repeat(2) { attempt ->
            val provider = accessManager.current()
            if (!provider.isAvailable()) return null
            val bound = fileOpsClient.bind(provider.getAccessMode()) ?: return null
            try {
                return block(bound)
            } catch (e: DeadObjectException) {
                if (attempt > 0) throw e
                Timber.w(e, "特权进程断开，重新连接")
            } finally {
                bound.close()
            }
        }
        return null
    }

    companion object {
        /** 页面上显示哪些来源 */
        fun sourcesOf(includeFiles: Boolean): List<String> =
            if (includeFiles) listOf(VideoEntity.SOURCE_CHAT, VideoEntity.SOURCE_FILE) else listOf(VideoEntity.SOURCE_CHAT)

        private const val WALK_PAGE = 1000
        private const val WALK_TIMEOUT_MS = 120_000L
        private const val HEADER_CHUNK = 200
        private const val HEADER_TIMEOUT_MS = 60_000L
        private const val DELETE_CHUNK = 100
        private const val METADATA_BATCH = 50
        private const val SQL_CHUNK = 500
    }
}
