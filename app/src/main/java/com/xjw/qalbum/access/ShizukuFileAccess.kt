package com.xjw.qalbum.access

import android.content.pm.PackageManager
import com.xjw.qalbum.domain.model.AccessMode
import com.xjw.qalbum.domain.model.FileInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import timber.log.Timber
import java.io.InputStream

/**
 * Shizuku 模式文件访问实现
 *
 * 命令执行通过 Shizuku.newProcess(...) 派发到 shell 用户（UID 2000），
 * 文件读取通过 `cat` / `head -c` 的进程 stdout 管道完成。
 *
 * 说明：[Shizuku.newProcess] 在 13.x 之后是 hidden API（@RestrictTo），
 * 这里经 [ShizukuShell] 反射调用。**扫描管线已不走这里**——它优先使用
 * AIDL UserService（com.xjw.qalbum.fileops），仅在绑定失败时降级 shell。
 */
class ShizukuFileAccess : FileAccessProvider {

    override fun getAccessMode(): AccessMode = AccessMode.SHIZUKU

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            // pingBinder = true 表示 Shizuku 服务已经在跑
            // 同时需要 App 已经被授权（PERMISSION_GRANTED）
            Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (t: Throwable) {
            Timber.w(t, "Shizuku isAvailable() failed")
            false
        }
    }

    override suspend fun exists(path: String): Boolean {
        if (!isAvailable()) return false
        return runShell("test -e '${path.escape()}' && echo 1 || echo 0").trim() == "1"
    }

    override fun listFiles(dir: String): Flow<FileInfo> = flow {
        if (!isAvailable()) return@flow
        // 单目录列举：用 stat 一次性拿元信息（不依赖 GNU 的 find -printf）
        val out = runShell(
            "find '${dir.escape()}' -mindepth 1 -maxdepth 1 -exec stat -c '%s|%Y|%n' {} + 2>/dev/null"
        )
        out.lineSequence().forEach { line ->
            parseStatLine(line)?.let { emit(it) }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun readFile(path: String): InputStream = withContext(Dispatchers.IO) {
        if (!isAvailable()) throw AccessException("Shizuku 不可用")
        val process = invokeNewProcess(arrayOf("sh", "-c", "cat '${path.escape()}' 2>/dev/null"))
            ?: throw AccessException("Shizuku newProcess 失败: $path")
        ProcessInputStream(process)
    }

    override suspend fun readHeader(path: String, n: Int): ByteArray? = withContext(Dispatchers.IO) {
        if (!isAvailable()) return@withContext null
        try {
            val process = invokeNewProcess(
                arrayOf("sh", "-c", "head -c $n '${path.escape()}' 2>/dev/null"),
            ) ?: return@withContext null
            val bytes = process.inputStream.use { it.readBytes() }
            process.waitFor()
            bytes.takeIf { it.isNotEmpty() }
        } catch (t: Throwable) {
            Timber.w(t, "Shizuku readHeader failed: $path")
            null
        }
    }

    override suspend fun getFileInfo(path: String): FileInfo? {
        if (!isAvailable()) return null
        val out = runShell(
            "stat -c '%s|%Y|%n' '${path.escape()}' 2>/dev/null"
        ).lineSequence().firstOrNull() ?: return null
        return parseStatLine(out)
    }

    override suspend fun deleteFile(path: String): Boolean {
        if (!isAvailable()) return false
        return runShellExit("rm -f '${path.escape()}'") == 0
    }

    override suspend fun deleteFiles(paths: List<String>): BooleanArray {
        if (paths.isEmpty()) return BooleanArray(0)
        if (!isAvailable()) return BooleanArray(paths.size) // 全部失败
        val result = BooleanArray(paths.size)
        var base = 0
        for (chunk in paths.chunked(SHELL_DELETE_CHUNK)) {
            // 整批一次起进程：内部一次 rm 再逐个回报，避免每文件一次 newProcess
            val out = runShell(buildBatchDeleteScript(chunk))
            val lines = out.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            if (lines.size != chunk.size) {
                Timber.w("Shizuku deleteFiles: expected ${chunk.size} results, got ${lines.size}")
            }
            for (i in chunk.indices) {
                result[base + i] = lines.getOrNull(i) == "1"
            }
            base += chunk.size
        }
        return result
    }

    override suspend fun moveFile(src: String, dst: String): Boolean {
        if (!isAvailable()) return false
        return runShellExit("mv '${src.escape()}' '${dst.escape()}'") == 0
    }

    // ---- 内部实现 ----

    /**
     * 在 Shizuku 派发的 shell 进程中执行命令，返回 stdout 字符串
     */
    private suspend fun runShell(cmd: String): String = withContext(Dispatchers.IO) {
        try {
            val process = invokeNewProcess(arrayOf("sh", "-c", cmd)) ?: return@withContext ""
            val out = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            out
        } catch (t: Throwable) {
            Timber.w(t, "Shizuku shell exec failed: $cmd")
            ""
        }
    }

    private suspend fun runShellExit(cmd: String): Int = withContext(Dispatchers.IO) {
        try {
            val process = invokeNewProcess(arrayOf("sh", "-c", cmd)) ?: return@withContext -1
            process.waitFor()
        } catch (t: Throwable) {
            Timber.w(t, "Shizuku shell exec failed: $cmd")
            -1
        }
    }

    /** 反射调用 Shizuku.newProcess —— 已抽到 [ShizukuShell] 与扫描兜底共用 */
    private fun invokeNewProcess(cmd: Array<String>): Process? = ShizukuShell.newProcess(cmd)

    /** 解析 `size|mtimeSec|/full/path` 一行（stat -c '%s|%Y|%n' 的输出） */
    private fun parseStatLine(line: String): FileInfo? {
        if (line.isBlank()) return null
        val parts = line.split('|', limit = 3)
        if (parts.size < 3) return null
        val size = parts[0].trim().toLongOrNull() ?: 0L
        val mtimeSec = parts[1].trim().toLongOrNull() ?: 0L
        val path = parts[2]
        if (path.isEmpty()) return null
        return FileInfo(
            path = path,
            name = path.substringAfterLast('/'),
            size = size,
            lastModified = mtimeSec * 1000L,
            isDirectory = false,
        )
    }

    /** 对 shell 字符串中的单引号做转义 */
    private fun String.escape(): String = replace("'", "'\\''")

    /**
     * 包装 Shizuku 子进程的 stdout 流：close 时一并回收子进程，避免进程泄漏。
     */
    private class ProcessInputStream(private val process: Process) : InputStream() {
        private val delegate = process.inputStream
        override fun read(): Int = delegate.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)
        override fun available(): Int = delegate.available()
        override fun close() {
            try {
                delegate.close()
            } finally {
                try {
                    process.destroy()
                } catch (_: Throwable) {
                }
            }
        }
    }
}
