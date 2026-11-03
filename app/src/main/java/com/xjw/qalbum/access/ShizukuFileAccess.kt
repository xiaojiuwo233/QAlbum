package com.xjw.qalbum.access

import android.content.pm.PackageManager
import com.xjw.qalbum.domain.model.AccessMode
import com.xjw.qalbum.domain.model.FileInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import timber.log.Timber
import java.io.IOException
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

    override suspend fun canReadDir(dir: String): Boolean {
        if (!isAvailable()) return false
        val q = "'${dir.escape()}'"
        return runShellExit("test -d $q && test -r $q && test -x $q") == 0
    }

    override suspend fun canWriteDir(dir: String): Boolean {
        if (!isAvailable()) return false
        val q = "'${dir.escape()}'"
        return runShellExit("test -d $q && test -w $q && test -x $q") == 0
    }

    override fun listFiles(dir: String): Flow<FileInfo> = flow {
        if (!isAvailable()) return@flow
        // 单目录列举：stat 一次性拿元信息（%f 原始 mode 用于目录判定，不依赖 GNU find -printf）；
        // stdout 逐行流式解析发射——数十万文件的大目录绝不能整读成单个 String
        val process = invokeNewProcess(
            arrayOf(
                "sh", "-c",
                "find '${dir.escape()}' -mindepth 1 -maxdepth 1 -exec stat -c '%f|%s|%Y|%n' {} + 2>/dev/null",
            )
        ) ?: return@flow
        try {
            process.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    parseShellStatLine(line)?.let { emit(it) }
                }
            }
            process.waitFor()
        } finally {
            try {
                process.destroy()
            } catch (_: Throwable) {
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun readFile(path: String): InputStream = withContext(Dispatchers.IO) {
        if (!isAvailable()) throw AccessException("Shizuku 不可用")
        val process = invokeNewProcess(arrayOf("sh", "-c", "cat '${path.escape()}' 2>/dev/null"))
            ?: throw AccessException("Shizuku newProcess 失败: $path")
        ProcessInputStream(process)
    }

    override suspend fun readHeader(path: String, n: Int): ByteArray? {
        if (!isAvailable()) return null
        return execShell("head -c $n '${path.escape()}'") { process ->
            val bytes = process.inputStream.use { it.readBytes() }
            process.waitFor()
            bytes
        }?.takeIf { it.isNotEmpty() }
    }

    override suspend fun readHeaders(paths: List<String>, n: Int): List<ByteArray?> {
        if (paths.isEmpty()) return emptyList()
        if (!isAvailable()) return List(paths.size) { null }
        val result = ArrayList<ByteArray?>(paths.size)
        for (chunk in paths.chunked(SHELL_HEADER_CHUNK)) {
            // 整批一次起进程：脚本内逐项 head|base64 带定界输出，避免每文件一次 newProcess
            val parsed = parseBatchHeaderOutput(runShell(buildBatchReadHeaderScript(chunk, n)), chunk.size)
            if (parsed != null) {
                result.addAll(parsed)
            } else {
                // 定界段数不符（输出被截断/base64 不可用）：该批退回逐文件读，保证正确性
                for (p in chunk) result.add(readHeader(p, n))
            }
        }
        return result
    }

    override suspend fun getFileInfo(path: String): FileInfo? {
        if (!isAvailable()) return null
        val out = runShell(
            "stat -c '%f|%s|%Y|%n' '${path.escape()}' 2>/dev/null"
        ).lineSequence().firstOrNull() ?: return null
        return parseShellStatLine(out)
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
            // 整批一次起进程：脚本内逐项 rm 回报，避免每文件一次 newProcess
            val script = buildBatchDeleteScript(chunk)
            var parsed = parseBatchResultLines(runShell(script), chunk.size)
            if (parsed == null) {
                // 输出行数不符=结果丢失（进程中途被杀/流读取失败），rm -f 幂等：重跑同一
                // 脚本复核，已删的会回报成功，避免「文件已删、DB 却不标 deleted」的幽灵行
                Timber.w("Shizuku deleteFiles: 结果行数不符，重跑复核（chunk=${chunk.size}）")
                parsed = parseBatchResultLines(runShell(script), chunk.size)
            }
            // 复核仍不符：该块保持 false（保守记失败），交上层重试
            parsed?.copyInto(result, base)
            base += chunk.size
        }
        return result
    }

    override suspend fun moveFile(src: String, dst: String): Boolean {
        if (!isAvailable()) return false
        return runShellExit("mv '${src.escape()}' '${dst.escape()}'") == 0
    }

    override suspend fun moveFileNoReplace(src: String, dst: String): Boolean {
        if (!isAvailable()) return false
        val quotedSrc = src.shellSingleQuote()
        val quotedDst = dst.shellSingleQuote()
        // 检查与移动放在同一个 shell，mv -n 在最终 rename 时拒绝覆盖并发新建的目标。
        val script = "[ ! -e $quotedDst ] || exit 1; " +
            "mv -n -- $quotedSrc $quotedDst 2>/dev/null; " +
            "[ ! -e $quotedSrc ] && [ -e $quotedDst ] || exit 1; " +
            // 跨应用目录时 mv 退化为复制，新文件属于 shell / root：改成与所在目录同属主，改不了就放开读写，否则 QQ 读不到
            "o=\$(stat -c %u:%g -- \"\$(dirname -- $quotedDst)\"); " +
            "[ \"\$(stat -c %u -- $quotedDst)\" = \"\${o%%:*}\" ] || chown \"\$o\" -- $quotedDst 2>/dev/null || chmod 666 -- $quotedDst 2>/dev/null; " +
            "exit 0"
        return runShellExit(script) == 0
    }

    override suspend fun moveFiles(pairs: List<Pair<String, String>>): BooleanArray {
        if (pairs.isEmpty()) return BooleanArray(0)
        if (!isAvailable()) return BooleanArray(pairs.size)
        val result = BooleanArray(pairs.size)
        var base = 0
        for (chunk in pairs.chunked(SHELL_MOVE_CHUNK)) {
            var parsed = parseBatchResultLines(runShell(buildBatchMoveScript(chunk)), chunk.size)
            if (parsed == null) {
                // mv 不幂等，结果丢失时不能重跑；用只读脚本按「源已不在且目标已在」复核
                Timber.w("Shizuku moveFiles: 结果行数不符，按存在性复核（chunk=${chunk.size}）")
                parsed = parseBatchResultLines(runShell(buildBatchMoveVerifyScript(chunk)), chunk.size)
            }
            parsed?.copyInto(result, base)
            base += chunk.size
        }
        return result
    }

    // ---- 内部实现 ----

    /**
     * 起 shell 子进程执行 [cmd]，在当前协程消费其输出后返回；进程创建失败/执行异常返回 null。
     *
     * - 整段脚本 stderr 重定向 /dev/null：无人消费的 stderr 管道写满会把子进程与阻塞读一起卡死
     * - 管道阻塞读不可被线程 interrupt 打断，取消时由 watcher 协程 destroy 关闭管道解除阻塞
     * - finally 兜底 destroy，杜绝子进程与管道 fd 泄漏
     */
    private suspend fun <T : Any> execShell(cmd: String, consume: (Process) -> T): T? =
        withContext(Dispatchers.IO) {
            val process = invokeNewProcess(arrayOf("sh", "-c", "exec 2>/dev/null\n$cmd"))
                ?: return@withContext null
            try {
                coroutineScope {
                    // UNDISPATCHED：确保先挂起在 awaitCancellation 再开始阻塞读，取消钩子必定就位
                    val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            awaitCancellation()
                        } finally {
                            runCatching { process.destroy() }
                        }
                    }
                    try {
                        consume(process)
                    } finally {
                        watcher.cancel()
                    }
                }
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                Timber.w(t, "Shizuku shell exec failed: $cmd")
                null
            } finally {
                runCatching { process.destroy() }
            }
        }

    /**
     * 在 Shizuku 派发的 shell 进程中执行命令，返回 stdout 字符串（失败返回空串）
     */
    private suspend fun runShell(cmd: String): String = execShell(cmd) { process ->
        val out = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        out
    } ?: ""

    private suspend fun runShellExit(cmd: String): Int = execShell(cmd) { process ->
        process.inputStream.use { input ->
            // 丢弃 stdout：子进程写满无人消费的管道会阻塞
            val buf = ByteArray(1024)
            while (input.read(buf) != -1) continue
        }
        process.waitFor()
    } ?: -1

    /** 反射调用 Shizuku.newProcess —— 已抽到 [ShizukuShell] 与扫描兜底共用 */
    private fun invokeNewProcess(cmd: Array<String>): Process? = ShizukuShell.newProcess(cmd)

    /** 对 shell 字符串中的单引号做转义 */
    private fun String.escape(): String = replace("'", "'\\''")

    /**
     * 包装 Shizuku 子进程的 stdout 流：close 时一并回收子进程，避免进程泄漏。
     *
     * EOF 既可能是正常读完，也可能是 cat 失败（SELinux 拒读得 0 字节）或子进程中途
     * 被杀导致的提前断流。这里在 EOF 处校验退出码、非 0 抛出，禁止消费方（如隔离
     * 拷贝兜底）把空/截断流当完整文件写完备份后删掉原图。
     */
    private class ProcessInputStream(private val process: Process) : InputStream() {
        private val delegate = process.inputStream
        private var exitChecked = false

        override fun read(): Int = checkOnEof(delegate.read())
        override fun read(b: ByteArray, off: Int, len: Int): Int = checkOnEof(delegate.read(b, off, len))
        override fun available(): Int = delegate.available()

        private fun checkOnEof(r: Int): Int {
            if (r == -1 && !exitChecked) {
                exitChecked = true
                val exit = try {
                    process.waitFor()
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("等待 cat 子进程退出被中断", e)
                }
                if (exit != 0) {
                    throw IOException("cat 子进程异常退出（exit=$exit），内容可能为空或截断")
                }
            }
            return r
        }

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
