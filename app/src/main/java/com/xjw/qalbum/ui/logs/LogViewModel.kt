package com.xjw.qalbum.ui.logs

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xjw.qalbum.log.AppLog
import com.xjw.qalbum.log.LogEntry
import com.xjw.qalbum.log.LogLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 日志查看页状态。 */
data class LogUiState(
    val entries: List<LogEntry> = emptyList(),
    val totalCount: Int = 0,
    val minLevel: LogLevel = LogLevel.V,
    val query: String = "",
    val diskSizeBytes: Long = 0L,
)

class LogViewModel : ViewModel() {

    private val minLevel = MutableStateFlow(LogLevel.V)
    private val query = MutableStateFlow("")

    val uiState: StateFlow<LogUiState> = combine(
        // 写入计数节流到 4Hz，避免高频日志把 UI 刷爆；onStart 立刻出一帧
        AppLog.version.sample(SAMPLE_MS).onStart { emit(AppLog.version.value) },
        minLevel,
        query,
    ) { _, level, q ->
        val all = AppLog.snapshot()
        val filtered = all.filter { entry ->
            entry.level.priority >= level.priority &&
                (q.isBlank() || entry.tag.contains(q, ignoreCase = true) ||
                    entry.message.contains(q, ignoreCase = true))
        }
        LogUiState(
            entries = filtered,
            totalCount = all.size,
            minLevel = level,
            query = q,
            diskSizeBytes = AppLog.diskSizeBytes(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LogUiState())

    fun setMinLevel(level: LogLevel) { minLevel.value = level }
    fun setQuery(text: String) { query.value = text }

    /** 导出全部日志为可分享文件，回调拿到 content Uri（null = 失败）。 */
    fun export(onResult: (Uri?) -> Unit) {
        viewModelScope.launch {
            val uri = withContext(Dispatchers.IO) { AppLog.exportShareUri() }
            onResult(uri)
        }
    }

    fun clear() = AppLog.clear()

    private companion object {
        const val SAMPLE_MS = 250L
    }
}
