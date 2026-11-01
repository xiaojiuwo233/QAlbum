package com.xjw.qalbum.service.cleanup

import android.os.DeadObjectException
import com.xjw.qalbum.access.AccessManager
import com.xjw.qalbum.analytics.Analytics
import com.xjw.qalbum.analytics.elapsedMs
import com.xjw.qalbum.analytics.qqCacheCleared
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.domain.model.AccessMode
import com.xjw.qalbum.fileops.BoundFileOps
import com.xjw.qalbum.fileops.FileOpsClient
import com.xjw.qalbum.fileops.FileOpsImpl
import com.xjw.qalbum.qq.QqCacheTargets
import com.xjw.qalbum.qq.QqStorageRoots
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 「QQ 缓存」页：统计并清空 QQ 自己的「清理缓存」会清空的目录（见 [QqCacheTargets]）。统计与清空都在特权进程里做；
 * 内部目录（/data/user/N/com.tencent.mobileqq）只有 Root 进得去，Shizuku 只能处理外部的 Android/data 目录。
 */
class QqCacheCleaner(
    private val accessManager: AccessManager,
    private val fileOpsClient: FileOpsClient,
    private val settings: SettingsRepository,
    private val analytics: Analytics? = null,
) {
    enum class State { PRESENT, MISSING, DENIED, ERROR }

    data class Entry(val target: QqCacheTargets.Target, val path: String, val state: State, val files: Long, val bytes: Long) {
        val clearable: Boolean get() = state == State.PRESENT && files > 0
    }

    data class Child(val name: String, val directory: Boolean, val files: Long, val bytes: Long)

    /** 清空的结果：删掉的文件数与大小、没删掉的项数、不允许清的路径数 */
    data class Report(val files: Long, val bytes: Long, val failed: Long, val refused: Int)

    suspend fun mode(): AccessMode = accessManager.current().getAccessMode()

    /** 各目录的统计（登记顺序：QQ 清理时整个清空的三个在前）；文件访问没就绪时为 null */
    suspend fun scan(): List<Entry>? = withBound { bound ->
        val roots = QqStorageRoots.fromChatpic(settings.chatpicPath.first())
        val targets = QqCacheTargets.all
        val paths = targets.map(roots::pathOf)
        val stats = paths.chunked(STAT_CHUNK).flatMap { chunk ->
            bound.callWithTimeout(TREE_TIMEOUT_MS) { it.pathStats(chunk.toTypedArray()) }.toList()
        }
        targets.mapIndexed { i, target -> Entry(target, paths[i], stateOf(stats[i * 3]), stats[i * 3 + 1], stats[i * 3 + 2]) }
    }

    /** [path] 里占用最大的几项（简要预览） */
    suspend fun children(path: String, limit: Int = CHILD_LIMIT): List<Child> = withBound { bound ->
        bound.callWithTimeout(TREE_TIMEOUT_MS) { it.largestChildren(path, limit) }.mapNotNull(::parseChild)
    }.orEmpty()

    /** 逐个清空 [paths]，[onProgress]（已处理, 总数）每个目录回调一次；文件访问没就绪时为 null */
    suspend fun clear(paths: List<String>, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): Report? = withBound { bound ->
        val started = System.nanoTime()
        var files = 0L
        var bytes = 0L
        var failed = 0L
        var refused = 0
        paths.forEachIndexed { index, path ->
            val result = bound.callWithTimeout(TREE_TIMEOUT_MS) { it.clearQqCache(arrayOf(path)) }
            if (result[0] < 0) {
                refused++
            } else {
                files += result[0]
                bytes += result[1]
                failed += result[2]
            }
            onProgress(index + 1, paths.size)
        }
        Timber.i("QQ cache cleared: dirs=%d files=%d bytes=%d failed=%d refused=%d", paths.size, files, bytes, failed, refused)
        analytics?.qqCacheCleared(paths.size, files, bytes, failed, refused, elapsedMs(started))
        Report(files, bytes, failed, refused)
    }

    /** 连上特权进程执行 [block]；连接中途断了（服务进程被回收）就重连再做一遍（统计、清空都可以重做） */
    private suspend fun <T> withBound(block: suspend (BoundFileOps) -> T): T? = withContext(Dispatchers.IO) {
        repeat(2) { attempt ->
            val provider = accessManager.current()
            if (!provider.isAvailable()) return@withContext null
            val bound = fileOpsClient.bind(provider.getAccessMode()) ?: return@withContext null
            try {
                return@withContext block(bound)
            } catch (e: DeadObjectException) {
                if (attempt > 0) throw e
                Timber.w(e, "特权进程断开，重新连接")
            } finally {
                bound.close()
            }
        }
        null
    }

    companion object {
        /** 一次统计几个目录：缓存目录可能有几万个文件，分开调用免得一次 binder 调用等太久 */
        private const val STAT_CHUNK = 8
        private const val TREE_TIMEOUT_MS = 180_000L
        private const val CHILD_LIMIT = 8

        internal fun stateOf(code: Long): State = when (code) {
            FileOpsImpl.PATH_PRESENT -> State.PRESENT
            FileOpsImpl.PATH_MISSING -> State.MISSING
            FileOpsImpl.PATH_DENIED -> State.DENIED
            else -> State.ERROR
        }

        /** "D|文件数|字节数|名字" / "F|1|字节数|名字"（名字可能含 |） */
        internal fun parseChild(line: String): Child? {
            val parts = line.split('|', limit = 4)
            if (parts.size < 4) return null
            return Child(
                name = parts[3],
                directory = parts[0] == "D",
                files = parts[1].toLongOrNull() ?: return null,
                bytes = parts[2].toLongOrNull() ?: return null,
            )
        }
    }
}
