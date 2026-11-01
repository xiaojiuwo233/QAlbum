package com.xjw.qalbum.log

import android.util.Log
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.system.exitProcess

/**
 * 全局未捕获异常处理：把崩溃栈写进 [AppLog] 并**同步刷盘**，然后交还给原有处理器
 * （release 里是 Crashlytics，再往后是系统）。这样「闪退后再次打开能看到之前的崩溃日志」。
 * 交出去的那份先经 forUpload 处理（release 去掉消息里的路径、QQ 号等，见 CrashSanitizer），本机日志里是完整的。
 *
 * 注意：只能捕获 JVM 层未捕获异常；原生硬崩溃（SIGSEGV 等）进程瞬间死亡，无法保证记录，
 * 那类信息要靠 [LogcatCapture] 在死亡前刷到的部分，属固有限制。
 */
object CrashHandler {

    fun install(forUpload: (Throwable) -> Throwable = { it }) {
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
                previous.uncaughtException(thread, runCatching { forUpload(throwable) }.getOrDefault(throwable))
            } else {
                exitProcess(2)
            }
        }
    }
}
