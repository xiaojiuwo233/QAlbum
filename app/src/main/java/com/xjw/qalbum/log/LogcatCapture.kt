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
        // 最近一条成功镜像行的 threadtime 时间戳：重启 logcat 时作为 -T 起点。
        // 不带 -T 的重启会把系统缓冲区里该 pid 的历史整段重放，而 AppLog 无去重。
        var sinceTime: String? = null
        while (attempts < MAX_ATTEMPTS && !Thread.currentThread().isInterrupted) {
            try {
                val cmd = mutableListOf("logcat", "-v", "threadtime")
                val since = sinceTime
                when {
                    // 首次启动不加 -T：dump 缓冲区历史是期望行为
                    since != null -> { cmd += "-T"; cmd += since }
                    attempts > 0 -> { cmd += "-T"; cmd += "1" }
                }
                cmd += listOf("--pid", pid) + QUIET_TAGS + "*:V"
                val process = ProcessBuilder(cmd).redirectErrorStream(true).start()

                val startedAt = System.currentTimeMillis()
                process.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (line.isEmpty() || line.startsWith("---------")) continue
                        AppLog.ingestLogcatLine(line)
                        if (line.length >= TIMESTAMP_LEN && line[2] == '-' && line[5] == ' ') {
                            sinceTime = line.substring(0, TIMESTAMP_LEN)
                        }
                    }
                }
                // 走到这里说明 logcat 进程结束了（被系统回收 / 出错），退避后重试；
                // 已正常运行足够久的结束不算失败，重置计数以免长生命周期进程耗尽重试额度后镜像永久静默
                if (System.currentTimeMillis() - startedAt >= HEALTHY_RUN_MS) attempts = 0 else attempts++
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

    /**
     * 不镜像的系统日志：HyperOS 等每解码 / 编码一张 JPEG 就打两行 skia-debug（删一万张图生成回收站缩略图就是几万行），
     * 还有读不到厂商属性的警告，会把日志文件很快轮转掉、冲走有用的内容。libc 只留错误（崩溃信号照常记下）。
     */
    private val QUIET_TAGS = listOf("SkJpegCodec:S", "SkJpegEncoder:S", "SkPngCodec:S", "SkPngEncoder:S", "SkWebpCodec:S", "skia:W", "libc:E")

    private const val MAX_ATTEMPTS = 8
    private const val RETRY_DELAY_MS = 1500L

    /** 单次 logcat 存活超过此时长即视为健康运行，其结束不计入失败重试次数 */
    private const val HEALTHY_RUN_MS = 60_000L

    /** threadtime 行首时间戳 "MM-dd HH:mm:ss.SSS" 的长度（同为 logcat -T 接受的格式） */
    private const val TIMESTAMP_LEN = 18
}
