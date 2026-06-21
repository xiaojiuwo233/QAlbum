package com.xjw.qalbum.log

import android.os.Process
import kotlin.concurrent.thread

/**
 * 后台线程镜像本进程的 logcat（`logcat --pid=<自身>`）写入 [AppLog]，用来捕获**绕过 Timber 的**
 * 第三方库 / 原生日志（OkHttp、Coil、Koin androidLogger、OpenCV native、ART 警告等）。
 *
 * 只在 release 启动：release 不种 [timber.log.Timber.DebugTree]，App 自身的 Timber 日志不进 logcat，
 * 因此镜像到的都是非 Timber 日志，与 [FileLoggingTree] 写入的 Timber 日志**零重复**。
 *
 * `logcat` 读取本应用自身日志无需特殊权限（API 16+）；个别定制 ROM 可能限制，此时镜像取不到内容、
 * 自动退避重试，不影响 [FileLoggingTree] 对 App 日志的记录（优雅降级）。
 *
 * 不依赖 `--pid`（API 24+，本项目 minSdk 26 满足）以外的任何东西；进程随 App 一起被回收。
 */
object LogcatCapture {

    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true
        thread(name = "logcat-mirror", isDaemon = true) { runLoop() }
    }

    private fun runLoop() {
        val pid = Process.myPid().toString()
        var attempts = 0
        while (attempts < MAX_ATTEMPTS && !Thread.currentThread().isInterrupted) {
            try {
                val process = ProcessBuilder(
                    "logcat", "-v", "threadtime", "--pid", pid, "*:V",
                ).redirectErrorStream(true).start()

                process.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (line.isEmpty() || line.startsWith("---------")) continue
                        AppLog.ingestLogcatLine(line)
                    }
                }
                // 走到这里说明 logcat 进程结束了（被系统回收 / 出错），退避后重试
                attempts++
            } catch (ie: InterruptedException) {
                return
            } catch (_: Throwable) {
                attempts++
            }
            try {
                Thread.sleep(RETRY_DELAY_MS)
            } catch (ie: InterruptedException) {
                return
            }
        }
    }

    private const val MAX_ATTEMPTS = 8
    private const val RETRY_DELAY_MS = 1500L
}
