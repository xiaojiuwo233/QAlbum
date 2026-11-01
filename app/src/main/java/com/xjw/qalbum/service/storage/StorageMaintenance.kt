package com.xjw.qalbum.service.storage

import coil3.ImageLoader
import com.xjw.qalbum.analytics.Analytics
import com.xjw.qalbum.analytics.cacheCleared
import com.xjw.qalbum.analytics.databaseSlimmed
import com.xjw.qalbum.analytics.elapsedMs
import com.xjw.qalbum.data.db.DatabaseSlimmer
import com.xjw.qalbum.data.db.StatsCache
import com.xjw.qalbum.data.db.StatsCache.Companion.holding
import com.xjw.qalbum.log.AppLog
import com.xjw.qalbum.qq.QqSourceRepository
import com.xjw.qalbum.service.work.BackgroundTasks
import com.xjw.qalbum.ui.common.formatBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/** 设置 → 存储空间：数据库、图片缓存、日志的占用，精简数据库与清理缓存（精简在后台进行，离开页面也不中断） */
class StorageMaintenance(
    private val slimmer: DatabaseSlimmer,
    private val sources: QqSourceRepository,
    private val statsCache: StatsCache,
    private val tasks: BackgroundTasks,
    private val imageLoader: ImageLoader,
    private val appScope: CoroutineScope,
    private val analytics: Analytics? = null,
) {
    data class Usage(val database: DatabaseSlimmer.Usage, val cacheBytes: Long, val logBytes: Long)
    data class Progress(val label: String, val done: Long = 0, val total: Long = 0)

    private val _usage = MutableStateFlow<Usage?>(null)
    val usage: StateFlow<Usage?> = _usage.asStateFlow()
    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun acknowledgeMessage() { _message.value = null }

    suspend fun refresh() {
        val database = slimmer.usage()
        val cache = withContext(Dispatchers.IO) { imageLoader.diskCache?.size ?: 0L }
        _usage.value = Usage(database, cache, withContext(Dispatchers.IO) { AppLog.diskSizeBytes() })
    }

    /** 精简数据库；正在精简时忽略 */
    fun slim() {
        if (!_progress.compareAndSet(null, Progress("准备精简"))) return
        appScope.launch(Dispatchers.IO) {
            val started = System.nanoTime()
            try {
                val saved = sources.exclusive("精简数据库") {
                    statsCache.holding {
                        tasks.track("精简数据库") { handle ->
                            slimmer.slim { label, done, total ->
                                _progress.value = Progress(label, done, total)
                                handle.update(label, done, total)
                            }
                        }
                    }
                }
                _message.value = when {
                    saved == null -> "正在读取聊天来源，读完再精简"
                    saved > 0 -> "数据库小了 ${formatBytes(saved)}"
                    else -> "已经很精简了"
                }
                analytics?.databaseSlimmed(if (saved == null) "busy" else "ok", saved ?: 0L, elapsedMs(started))
            } catch (e: CancellationException) {
                analytics?.databaseSlimmed("cancelled", 0, elapsedMs(started))
                _message.value = "已停止，空出来的空间下次接着释放"
            } catch (e: Exception) {
                Timber.w(e, "Database slimming failed")
                analytics?.databaseSlimmed("failed", 0, elapsedMs(started))
                _message.value = "精简没有完成，请稍后重试"
            } finally {
                _progress.value = null
                runCatching { refresh() }
            }
        }
    }

    fun clearCache() {
        appScope.launch(Dispatchers.IO) {
            analytics?.cacheCleared("images", imageLoader.diskCache?.size ?: 0)
            imageLoader.memoryCache?.clear()
            imageLoader.diskCache?.clear()
            _message.value = "图片缓存已清除"
            refresh()
        }
    }

    fun clearLogs() {
        appScope.launch(Dispatchers.IO) {
            analytics?.cacheCleared("logs", AppLog.diskSizeBytes())
            AppLog.clear()
            _message.value = "日志已清除"
            refresh()
        }
    }
}
