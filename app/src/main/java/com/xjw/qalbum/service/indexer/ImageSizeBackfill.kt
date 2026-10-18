package com.xjw.qalbum.service.indexer

import com.xjw.qalbum.data.db.dao.ImageDao
import com.xjw.qalbum.data.db.dao.OriginalBackupDao
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.service.indexer.transport.ScanTransport
import com.xjw.qalbum.service.work.BackgroundTasks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 补读宽高：扫描时新文件已经顺带读了文件头，这里只补以前入库、宽高为 0 的行（升级后首次约几分钟，之后几乎为空）。
 * 只解析文件头、不解码；读不到的记 -1，不再反复尝试。
 *
 * 另外核对一次旧图片的宽高（[CHECK_VERSION]）：以前的分析把图缩到长边 768 以内解码，早期版本把缩小后的尺寸
 * 当成了宽高（2560×1600 记成 640×400）。长边不超过 [CHECK_MAX_SIDE] 的图重读文件头，不一样就改；
 * 压缩记录里压缩前的尺寸是压缩时从库里抄的，一起改：有原图备份的读备份，没有的记为未知（0）。
 */
class ImageSizeBackfill(
    private val imageDao: ImageDao,
    private val backups: OriginalBackupDao,
    private val settings: SettingsRepository,
    /** 一般是 ScanTransportFactory.create；文件访问没就绪时为 null */
    private val openTransport: suspend () -> ScanTransport?,
    private val tasks: BackgroundTasks,
    private val appScope: CoroutineScope,
) {
    private var job: Job? = null

    /** 有待补或待核对的行时在后台跑一轮；已在跑则忽略 */
    @Synchronized
    fun startIfNeeded() {
        if (job?.isActive == true) return
        job = appScope.launch {
            val pending = imageDao.countMissingSize()
            var check = settings.imageSizeCheckVersion.first() < CHECK_VERSION
            if (check && imageDao.countWithSmallSize(CHECK_MAX_SIDE) == 0 && backups.countWithSmallOriginal(CHECK_MAX_SIDE) == 0) {
                // 没有要核对的（新装、或库里都是扫描时读的文件头）
                settings.setImageSizeCheckVersion(CHECK_VERSION)
                check = false
            }
            if (pending == 0 && !check) return@launch
            tasks.track("读取图片尺寸") { handle ->
                try {
                    run(pending, check, handle)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "ImageSizeBackfill failed")
                }
            }
        }
    }

    private suspend fun run(pending: Int, check: Boolean, handle: BackgroundTasks.Handle) {
        val transport = openTransport() ?: return
        try {
            // shell 降级通道读不了宽高：不记 -1，等特权服务可用时再补
            if (!transport.readsImageSizes) return
            if (pending > 0) {
                fill(transport, pending) { done -> handle.update("已读取 $done / $pending 张", done.toLong(), pending.toLong()) }
            }
            if (check) {
                val total = imageDao.countWithSmallSize(CHECK_MAX_SIDE)
                handle.update(title = "核对图片尺寸")
                recheck(transport, total) { done -> handle.update("已核对 $done / $total 张", done.toLong(), total.toLong()) }
                settings.setImageSizeCheckVersion(CHECK_VERSION)
            }
        } finally {
            transport.close()
        }
    }

    private suspend fun fill(transport: ScanTransport, pending: Int, onProgress: (Int) -> Unit) {
        var afterId = 0L
        var done = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val rows = imageDao.rowsMissingSize(afterId, PAGE)
            if (rows.isEmpty()) break
            afterId = rows.last().id
            val sizes = transport.readImageSizes(rows.map { it.path })
            imageDao.saveSizes(rows.mapIndexed { i, row ->
                val size = sizes.getOrNull(i)
                ImageDao.SizeWrite(row.id, size?.first ?: UNKNOWN, size?.second ?: UNKNOWN)
            })
            done += rows.size
            onProgress(done.coerceAtMost(pending))
        }
        Timber.i("ImageSizeBackfill: $done rows")
    }

    /** 重读长边小的图；读不到的保持原样 */
    private suspend fun recheck(transport: ScanTransport, total: Int, onProgress: (Int) -> Unit) {
        var afterId = 0L
        var done = 0
        var corrected = 0
        val fixes = ArrayList<ImageDao.SizeFix>()
        suspend fun flush() {
            if (fixes.isEmpty()) return
            imageDao.correctSizes(fixes)
            corrected += fixes.size
            fixes.clear()
        }
        while (true) {
            currentCoroutineContext().ensureActive()
            val rows = imageDao.rowsWithSmallSize(CHECK_MAX_SIDE, afterId, PAGE)
            if (rows.isEmpty()) break
            afterId = rows.last().id
            val sizes = transport.readImageSizes(rows.map { it.path })
            rows.forEachIndexed { i, row ->
                val (width, height) = sizes.getOrNull(i) ?: return@forEachIndexed
                if (width != row.width || height != row.height) fixes += ImageDao.SizeFix(row.id, row.width, row.height, width, height)
            }
            // 攒一批再写：每次写入图库与统计都会重查
            if (fixes.size >= WRITE_BATCH) flush()
            done += rows.size
            onProgress(done.coerceAtMost(total))
        }
        flush()
        val records = recheckRecords(transport)
        Timber.i("ImageSizeBackfill: checked $done rows, corrected $corrected, compression records $records")
    }

    /** 压缩记录里压缩前的尺寸，返回改了几条 */
    private suspend fun recheckRecords(transport: ScanTransport): Int {
        var afterId = 0L
        var corrected = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val records = backups.recordsWithSmallOriginal(CHECK_MAX_SIDE, afterId, PAGE)
            if (records.isEmpty()) break
            afterId = records.last().id
            val withBackup = records.filter { it.backupPath != null }
            val sizes = if (withBackup.isEmpty()) emptyList() else transport.readImageSizes(withBackup.map { it.backupPath!! })
            val read = withBackup.indices.mapNotNull { i -> sizes.getOrNull(i)?.let { withBackup[i].id to it } }.toMap()
            for (record in records) {
                val size = read[record.id]
                    // 读不到备份：压缩不会放大，压缩前比压缩后还小的一定是缩小的旧尺寸
                    ?: (0 to 0).takeIf {
                        maxOf(record.originalWidth, record.originalHeight) < maxOf(record.compressedWidth, record.compressedHeight)
                    }
                    ?: continue
                if (size.first == record.originalWidth && size.second == record.originalHeight) continue
                backups.correctOriginalSize(record.id, record.originalWidth, record.originalHeight, size.first, size.second)
                corrected++
            }
        }
        return corrected
    }

    private companion object {
        const val PAGE = 400

        /** 读不到宽高：记 -1，界面按「未知尺寸」显示，不再补读 */
        const val UNKNOWN = -1

        /** 核对版本：1 起重读旧版分析写入的缩小尺寸（0.3.6） */
        const val CHECK_VERSION = 1

        /** 旧版分析解码时长边不超过 768（向上取整可能多 1），留出余量 */
        const val CHECK_MAX_SIDE = 1024

        /** 核对结果攒够这么多再写 */
        const val WRITE_BATCH = 2_000
    }
}
