package com.xjw.qalbum.service.work

import android.content.Context
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 后台长任务登记处：扫描、读取聊天来源、规则清理、原图压缩、清空回收站等。
 *
 * 有任务在跑时启动前台服务（[TaskForegroundService]，通知栏显示进度）：App 切到后台后进程仍是前台优先级，
 * 不会被系统降速或冻结（HyperOS 等对后台进程限制很严，扫描 / 读来源切后台后会慢一大半甚至出错）。
 * 全部任务结束后通知自动消失。
 */
class BackgroundTasks(private val context: Context) {

    data class Task(
        val id: Long,
        val title: String,
        val detail: String? = null,
        val done: Long = 0,
        val total: Long = 0,
        val cancellable: Boolean = false,
    )

    private val _tasks = MutableStateFlow<List<Task>>(emptyList())
    val tasks: StateFlow<List<Task>> = _tasks.asStateFlow()

    private val cancels = ConcurrentHashMap<Long, () -> Unit>()
    private val nextId = AtomicLong(1)

    /** 正在运行的一项任务；[finish] 可重复调用 */
    inner class Handle internal constructor(val id: Long) {
        fun update(detail: String? = null, done: Long = 0, total: Long = 0, title: String? = null) {
            _tasks.update { list ->
                list.map { if (it.id == id) it.copy(title = title ?: it.title, detail = detail, done = done, total = total) else it }
            }
        }

        fun finish() {
            cancels.remove(id)
            _tasks.update { list -> list.filterNot { it.id == id } }
        }
    }

    /** 登记一项任务；[onCancel] 非空时通知栏与页面上可以停止它 */
    fun begin(title: String, onCancel: (() -> Unit)? = null): Handle {
        val id = nextId.getAndIncrement()
        onCancel?.let { cancels[id] = it }
        _tasks.update { it + Task(id, title, cancellable = onCancel != null) }
        TaskForegroundService.ensureRunning(context)
        return Handle(id)
    }

    /** 在登记期间执行 [block]；[cancellable] 时「停止」会取消调用方所在的协程 */
    suspend fun <T> track(title: String, cancellable: Boolean = true, block: suspend (Handle) -> T): T {
        val job = currentCoroutineContext()[Job]
        val handle = begin(title, if (cancellable && job != null) ({ job.cancel() }) else null)
        try {
            return block(handle)
        } finally {
            handle.finish()
        }
    }

    fun cancel(id: Long) {
        cancels[id]?.invoke()
    }
}
