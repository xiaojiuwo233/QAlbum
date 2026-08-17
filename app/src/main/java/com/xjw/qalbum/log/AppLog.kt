package com.xjw.qalbum.log

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 全局日志引擎：把全 App 的日志（[FileLoggingTree] 接来的 Timber 日志、[LogcatCapture] 镜像的
 * 第三方/原生 logcat、[CrashHandler] 捕获的崩溃栈）统一写入磁盘并保留在内存环形缓冲里。
 *
 * 设计要点：
 * - **持久化**：append 到 [logDir]/app.log，按大小轮转（app.1.log … app.N.log）。进程重启 / 闪退后
 *   不清空、继续 append，启动时把上次的尾部回填进内存缓冲，所以「再次打开能看到之前的日志」。
 * - **单写线程**：所有文件写入与环形缓冲改动都在 [handler] 线程串行执行，天然有序、无锁竞争；
 *   调用方只在自己线程上构造 [LogEntry]（保证时间戳/线程号准确）再 post 过来。
 * - **统一行格式**：`MM-dd HH:mm:ss.SSS PID TID L TAG: message`，与 logcat threadtime 一致，
 *   所以镜像来的行可原样落盘、也能被同一个解析器读回。
 *
 * 这是基础设施（与 Timber 同级的全局单例），用 [init] 在 App.onCreate 最早期装好，不走 DI。
 */
object AppLog {

    // ---- 可调参数 ----
    private const val MAX_FILE_BYTES = 2L * 1024 * 1024      // 单文件 2MB 触发轮转
    private const val MAX_ROTATED_FILES = 4                  // 另存 4 个历史文件（合计上限 ~10MB）
    private const val RING_CAPACITY = 5000                   // 内存里最多保留多少条供查看
    private const val SEED_TAIL_BYTES = 512L * 1024          // 启动时回填的尾部字节数
    private const val FLUSH_DELAY_MS = 200L                  // 写入后合并刷盘的延迟
    private const val ROTATE_RETRY_BACKOFF_MS = 30_000L      // 轮转失败（磁盘满）后的重试退避

    private const val CURRENT_NAME = "app.log"
    private val TIME_FORMAT = ThreadLocal.withInitial {
        SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    }
    private val FULL_TIME_FORMAT = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    }
    // 解析一行 threadtime 格式：日期 PID TID 级别 余下(=TAG: message)
    private val LINE_REGEX =
        Regex("""^(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\s+(\d+)\s+(\d+)\s+([VDIWEAF])\s+(.*)$""")
    private val PARSE_FORMAT = ThreadLocal.withInitial {
        // logcat 时间戳是设备本地时区、无年份；补当前年份以得到可排序的毫秒（仅用于内存排序/显示）
        SimpleDateFormat("yyyy MM-dd HH:mm:ss.SSS", Locale.US)
    }

    @Volatile private var initialized = false
    private lateinit var appContext: Context
    private lateinit var logDir: File
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    // 仅在 handler 线程访问
    private var writer: BufferedWriter? = null
    private var currentBytes = 0L
    // rename 失败（磁盘满）时 app.log 仍超限，退避到此时刻前不再触发 rotate，
    // 避免磁盘满期间每写一条日志都重跑一次轮转
    private var rotateRetryAt = 0L
    private val flushRunnable = Runnable { runCatching { writer?.flush() } }

    private val ring = ArrayDeque<LogEntry>(RING_CAPACITY)
    private val _version = MutableStateFlow(0L)
    /** 每写入一条 +1；查看页据此（节流后）拉 [snapshot]。 */
    val version: StateFlow<Long> = _version

    /** 在 App.onCreate 最早期调用，幂等。 */
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        logDir = File(appContext.filesDir, "logs").apply { mkdirs() }
        handlerThread = HandlerThread("AppLog").apply { start() }
        handler = Handler(handlerThread!!.looper)
        handler?.post {
            openWriter()
            seedRingFromDisk()
        }
    }

    val isReady: Boolean get() = initialized

    // ──────────────────────────────────────────────────────────────
    // 写入入口
    // ──────────────────────────────────────────────────────────────

    /** Timber 来的日志（[FileLoggingTree]）/崩溃栈（[CrashHandler]）走这里。 */
    fun log(priority: Int, tag: String?, message: String) {
        if (!initialized) return
        val entry = LogEntry(
            timeMillis = System.currentTimeMillis(),
            pid = Process.myPid(),
            tid = Process.myTid(),
            level = LogLevel.fromPriority(priority),
            tag = tag ?: "App",
            message = message,
        )
        val line = format(entry)
        handler?.post { append(entry, line) }
    }

    /** logcat 镜像来的整行（[LogcatCapture]）：已是 threadtime 格式，原样落盘、解析进缓冲。 */
    fun ingestLogcatLine(raw: String) {
        if (!initialized || raw.isEmpty()) return
        val entry = parseLine(raw) ?: LogEntry(
            timeMillis = System.currentTimeMillis(),
            pid = 0, tid = 0, level = LogLevel.I, tag = "logcat", message = raw,
        )
        handler?.post { append(entry, raw) }
    }

    // handler 线程：进缓冲 + 落盘
    private fun append(entry: LogEntry, line: String) {
        synchronized(ring) {
            ring.addLast(entry)
            while (ring.size > RING_CAPACITY) ring.removeFirst()
        }
        _version.value = _version.value + 1

        val w = writer ?: return
        runCatching {
            w.write(line)
            w.newLine()
            currentBytes += line.toByteArray(Charsets.UTF_8).size + 1
            if (currentBytes >= MAX_FILE_BYTES && SystemClock.uptimeMillis() >= rotateRetryAt) rotate()
        }
        handler?.removeCallbacks(flushRunnable)
        handler?.postDelayed(flushRunnable, FLUSH_DELAY_MS)
    }

    // ──────────────────────────────────────────────────────────────
    // 查看 / 导出 / 清空
    // ──────────────────────────────────────────────────────────────

    /** 当前内存缓冲快照（最旧→最新）。 */
    fun snapshot(): List<LogEntry> = synchronized(ring) { ArrayList(ring) }

    /** 磁盘上日志总大小（字节），用于 UI 展示。 */
    fun diskSizeBytes(): Long =
        if (initialized) logFilesOldestFirst().sumOf { it.length() } else 0L

    /**
     * 把全部保留的日志（含历史轮转文件、本次与上次运行）合并导出到可分享的临时文件，
     * 返回 content Uri。在 IO 线程调用。
     */
    fun exportShareUri(): Uri? {
        if (!initialized) return null
        flushBlocking()
        return runCatching {
            val shareDir = File(appContext.cacheDir, "share").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val dest = File(shareDir, "qalbum-log-$stamp.txt")
            dest.bufferedWriter().use { out ->
                out.write(exportHeader())
                logFilesOldestFirst().forEach { f ->
                    runCatching { f.forEachLine { out.appendLine(it) } }
                }
            }
            FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", dest)
        }.getOrNull()
    }

    /** 清空全部日志（磁盘 + 内存）。 */
    fun clear() {
        val h = handler
        if (h == null) {
            synchronized(ring) { ring.clear() }
            _version.value = _version.value + 1
            return
        }
        h.post {
            closeWriter()
            logFilesOldestFirst().forEach { it.delete() }
            currentBytes = 0
            rotateRetryAt = 0
            openWriter()
            synchronized(ring) { ring.clear() }
            _version.value = _version.value + 1
        }
    }

    /** 阻塞等待缓冲刷盘（崩溃前 / 导出前用）。 */
    fun flushBlocking(timeoutMs: Long = 2000) {
        val h = handler ?: return
        val latch = CountDownLatch(1)
        h.post {
            runCatching { writer?.flush() }
            latch.countDown()
        }
        runCatching { latch.await(timeoutMs, TimeUnit.MILLISECONDS) }
    }

    // ──────────────────────────────────────────────────────────────
    // 内部：文件 / 轮转 / 解析（均在 handler 线程或无状态）
    // ──────────────────────────────────────────────────────────────

    private fun openWriter() {
        runCatching {
            val current = File(logDir, CURRENT_NAME)
            writer = BufferedWriter(FileWriter(current, /* append = */ true))
            currentBytes = current.length()
        }
    }

    /** flush 与 close 分开兜底：flush 抛异常（磁盘满）时也必须走到 close，否则泄漏 fd。 */
    private fun closeWriter() {
        val w = writer ?: return
        writer = null
        runCatching { w.flush() }
        runCatching { w.close() }
    }

    private fun rotate() {
        closeWriter()
        runCatching {
            // 删最旧、整体后移：app.(N-1).log → app.N.log … app.log → app.1.log
            File(logDir, "$CURRENT_NAME.$MAX_ROTATED_FILES").delete()
            for (i in MAX_ROTATED_FILES - 1 downTo 1) {
                val from = File(logDir, "$CURRENT_NAME.$i")
                if (from.exists()) from.renameTo(File(logDir, "$CURRENT_NAME.${i + 1}"))
            }
            File(logDir, CURRENT_NAME).renameTo(File(logDir, "$CURRENT_NAME.1"))
        }
        currentBytes = 0
        openWriter()
        // openWriter 读回真实大小：仍超限说明 rename 未成功，退避一段时间再重试轮转
        rotateRetryAt = if (currentBytes >= MAX_FILE_BYTES) {
            SystemClock.uptimeMillis() + ROTATE_RETRY_BACKOFF_MS
        } else 0
    }

    /** 历史→当前 顺序的全部日志文件（存在的）。 */
    private fun logFilesOldestFirst(): List<File> {
        val files = ArrayList<File>()
        for (i in MAX_ROTATED_FILES downTo 1) {
            val f = File(logDir, "$CURRENT_NAME.$i")
            if (f.exists()) files.add(f)
        }
        val current = File(logDir, CURRENT_NAME)
        if (current.exists()) files.add(current)
        return files
    }

    private fun seedRingFromDisk() {
        val text = readTail(File(logDir, CURRENT_NAME), SEED_TAIL_BYTES)
        if (text.isEmpty()) return
        val parsed = ArrayList<LogEntry>()
        for (line in text.split('\n')) {
            if (line.isEmpty()) continue
            val e = parseLine(line)
            if (e != null) {
                parsed.add(e)
            } else if (parsed.isNotEmpty()) {
                // 续行（如堆栈）：拼到上一条
                val last = parsed.removeAt(parsed.lastIndex)
                parsed.add(last.copy(message = last.message + "\n" + line))
            } else {
                parsed.add(LogEntry(System.currentTimeMillis(), 0, 0, LogLevel.I, "logcat", line))
            }
        }
        synchronized(ring) {
            val start = (parsed.size - RING_CAPACITY).coerceAtLeast(0)
            for (i in start until parsed.size) ring.addLast(parsed[i])
        }
        _version.value = _version.value + 1
    }

    private fun readTail(file: File, maxBytes: Long): String {
        if (!file.exists()) return ""
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val len = raf.length()
                val start = (len - maxBytes).coerceAtLeast(0)
                raf.seek(start)
                val bytes = ByteArray((len - start).toInt())
                raf.readFully(bytes)
                var text = String(bytes, Charsets.UTF_8)
                if (start > 0) {
                    val nl = text.indexOf('\n')
                    if (nl >= 0) text = text.substring(nl + 1)
                }
                text
            }
        }.getOrDefault("")
    }

    private fun parseLine(line: String): LogEntry? {
        val m = LINE_REGEX.matchEntire(line) ?: return null
        val (date, pid, tid, lvl, rest) = m.destructured
        val sep = rest.indexOf(": ").let { if (it >= 0) it else rest.indexOf(':') }
        val tag = if (sep >= 0) rest.substring(0, sep) else rest
        val msg = if (sep >= 0) rest.substring(sep + (if (rest.getOrNull(sep + 1) == ' ') 2 else 1)) else ""
        val millis = runCatching {
            PARSE_FORMAT.get()!!.parse("${currentYear()} $date")!!.time
        }.getOrDefault(System.currentTimeMillis())
        return LogEntry(
            timeMillis = millis,
            pid = pid.toIntOrNull() ?: 0,
            tid = tid.toIntOrNull() ?: 0,
            level = LogLevel.fromChar(lvl[0]),
            tag = tag,
            message = msg,
        )
    }

    private fun currentYear(): Int =
        java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)

    private fun exportHeader(): String = buildString {
        val pkg = appContext.packageName
        val pInfo = runCatching { appContext.packageManager.getPackageInfo(pkg, 0) }.getOrNull()
        appendLine("==== QAlbum 日志导出 ====")
        appendLine("导出时间: ${FULL_TIME_FORMAT.get()!!.format(Date())}")
        appendLine("应用版本: ${pInfo?.versionName ?: "?"} (build ${pInfo?.longVersionCodeCompat ?: 0})")
        appendLine("包名: $pkg")
        appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("系统: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("=========================")
        appendLine()
    }

    private val android.content.pm.PackageInfo.longVersionCodeCompat: Long
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else @Suppress("DEPRECATION") versionCode.toLong()

    /** 把一条记录格式化成磁盘 / 显示用的一行（不含尾换行）。 */
    fun format(entry: LogEntry): String =
        "${TIME_FORMAT.get()!!.format(Date(entry.timeMillis))} ${entry.pid} ${entry.tid} " +
            "${entry.level.char} ${entry.tag}: ${entry.message}"

    /** 仅格式化时间，给 UI 用。 */
    fun formatTime(millis: Long): String = TIME_FORMAT.get()!!.format(Date(millis))
}
