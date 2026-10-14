package com.xjw.qalbum.service.indexer

import com.xjw.qalbum.data.db.dao.ImageDao
import com.xjw.qalbum.service.indexer.transport.ScanTransportFactory
import com.xjw.qalbum.service.work.BackgroundTasks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 补读宽高：扫描时新文件已经顺带读了文件头，这里只补以前入库、宽高为 0 的行（升级后首次约几分钟，之后几乎为空）。
 * 只解析文件头、不解码；读不到的记 -1，不再反复尝试。
 */
class ImageSizeBackfill(
    private val imageDao: ImageDao,
    private val transportFactory: ScanTransportFactory,
    private val tasks: BackgroundTasks,
    private val appScope: CoroutineScope,
) {
    private var job: Job? = null

    /** 有待补的行时在后台补一轮；已在跑则忽略 */
    @Synchronized
    fun startIfNeeded() {
        if (job?.isActive == true) return
        job = appScope.launch {
            val pending = imageDao.countMissingSize()
            if (pending == 0) return@launch
            tasks.track("读取图片尺寸") { handle ->
                try {
                    run(pending) { done -> handle.update("已读取 $done / $pending 张", done.toLong(), pending.toLong()) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "ImageSizeBackfill failed")
                }
            }
        }
    }

    private suspend fun run(pending: Int, onProgress: (Int) -> Unit) {
        val transport = transportFactory.create() ?: return
        try {
            // shell 降级通道读不了宽高：不记 -1，等特权服务可用时再补
            if (!transport.readsImageSizes) return
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
        } finally {
            transport.close()
        }
    }

    private companion object {
        const val PAGE = 400

        /** 读不到宽高：记 -1，界面按「未知尺寸」显示，不再补读 */
        const val UNKNOWN = -1
    }
}
