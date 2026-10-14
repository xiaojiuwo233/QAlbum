package com.xjw.qalbum.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.xjw.qalbum.service.work.BackgroundTasks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 数据库升级进度。升级（Room 迁移）在第一次打开数据库时进行，期间所有查询都在等，页面一片空白；
 * 启动时先看文件里的版本号，需要升级就由 [DatabaseOpener] 立即打开数据库，界面显示进度弹窗。
 * 迁移里按步骤调用 [report]（整理大表时带行数），打开完成后 [finish]。
 */
object DatabaseUpgrade {

    sealed interface State {
        /** [done] / [total] 为 0 时是不确定进度 */
        data class Running(val step: String, val done: Long = 0, val total: Long = 0) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state.asStateFlow()

    /** 数据库文件存在且版本低于 [target] 时返回文件里的版本号；新装、已是最新、读不了时返回 null */
    fun pendingFrom(context: Context, target: Int): Int? {
        val file = context.getDatabasePath(AppDatabase.DB_NAME)
        if (!file.isFile) return null
        val version = runCatching {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { it.version }
        }.onFailure { Timber.w(it, "DatabaseUpgrade: read version failed") }.getOrNull() ?: return null
        return version.takeIf { it in 1 until target }
    }

    internal fun begin() {
        _state.value = State.Running("准备升级…")
    }

    /** 迁移里报告进度；没在显示升级进度时（新装、测试）什么也不做 */
    fun report(step: String, done: Long = 0, total: Long = 0) {
        if (_state.value != null) _state.value = State.Running(step, done, total)
    }

    internal fun finish() {
        _state.value = null
    }

    internal fun fail(message: String) {
        _state.value = State.Failed(message)
    }
}

/**
 * 需要升级时立即打开数据库（触发迁移），登记为后台任务：升级途中切到后台不被系统冻结，通知栏同步显示进度。
 * 失败时弹窗显示原因，可以重试（迁移在一个事务里，失败会整体回滚）。
 */
class DatabaseOpener(
    private val context: Context,
    private val db: AppDatabase,
    private val tasks: BackgroundTasks,
    private val appScope: CoroutineScope,
) {
    /** 启动时调用：需要升级就开始并返回 true */
    fun upgradeIfNeeded(): Boolean {
        DatabaseUpgrade.pendingFrom(context, AppDatabase.VERSION) ?: return false
        start()
        return true
    }

    fun start() {
        DatabaseUpgrade.begin()
        appScope.launch {
            tasks.track("升级数据库", cancellable = false) { handle ->
                val mirror = launch {
                    DatabaseUpgrade.state.collect { state ->
                        if (state is DatabaseUpgrade.State.Running) handle.update(state.step, state.done, state.total)
                    }
                }
                try {
                    withContext(Dispatchers.IO) { db.openHelper.writableDatabase }
                    DatabaseUpgrade.finish()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "DatabaseUpgrade: migration failed")
                    DatabaseUpgrade.fail(e.message ?: e.javaClass.simpleName)
                } finally {
                    mirror.cancel()
                }
            }
        }
    }
}
