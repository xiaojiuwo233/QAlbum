package com.xjw.qalbum.log

import android.util.Log
import timber.log.Timber

/**
 * 把所有 Timber 日志转交给 [AppLog] 持久化。所有构建（含 release）都种这棵树，
 * 因此即便 release 不种 [Timber.DebugTree]、Timber 日志不进 logcat，也仍被完整记录到文件。
 *
 * 注意：本树**不**回显到 logcat，避免与 release 下的 [LogcatCapture] 镜像产生重复；
 * 开发期的 logcat 回显由 [Timber.DebugTree] 负责。
 */
class FileLoggingTree : Timber.Tree() {
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val full = if (t != null) "$message\n${Log.getStackTraceString(t)}" else message
        AppLog.log(priority, tag, full)
    }
}
