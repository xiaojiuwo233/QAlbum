package com.xjw.qalbum.log

import android.util.Log
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.system.exitProcess

/**
 * 全局未捕获异常处理：把崩溃栈写进 [AppLog] 并**同步刷盘**，然后交还给系统原有处理器
 * （保留系统崩溃弹窗 / Play 上报）。这样「闪退后再次打开能看到之前的崩溃日志」。
 *
 * 注意：只能捕获 JVM 层未捕获异常；原生硬崩溃（SIGSEGV 等）进程瞬间死亡，无法保证记录，
 * 那类信息要靠 [LogcatCapture] 在死亡前刷到的部分，属固有限制。
 */
object CrashHandler {

    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                AppLog.log(
                    Log.ASSERT,
                    "CRASH",
                    "FATAL: 未捕获异常 @线程 '${thread.name}'\n$sw",
                )
                AppLog.flushBlocking(timeoutMs = 3000)
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                exitProcess(2)
            }
        }
    }
}
