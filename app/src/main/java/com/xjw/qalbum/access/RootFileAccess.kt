package com.xjw.qalbum.access

import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.io.SuFile
import com.topjohnwu.superuser.io.SuFileInputStream
import com.xjw.qalbum.domain.model.AccessMode
import com.xjw.qalbum.domain.model.FileInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.InputStream
import java.util.concurrent.Executor

/**
 * Root 模式文件访问实现（基于 libsu）
 *
 * Root 模式比 Shizuku 多了访问 QQ 私有目录的能力（QQNT 数据库）。
 * **扫描管线不走这里**——它优先使用 AIDL RootService（com.xjw.qalbum.fileops，
 * 原生 IO），仅在绑定失败时降级 shell 流式命令。
 */
class RootFileAccess : FileAccessProvider {

    override fun getAccessMode(): AccessMode = AccessMode.ROOT

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            // libsu 在第一次调用时会异步请求 root，需要主动等待结果
            Shell.getShell().isRoot
        } catch (t: Throwable) {
            Timber.w(t, "RootFileAccess isAvailable() failed")
            false
        }
    }

    override suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) {
        SuFile(path).exists()
    }

    override suspend fun canWriteDir(dir: String): Boolean = withContext(Dispatchers.IO) {
        val f = SuFile(dir)
        f.isDirectory && f.canWrite()
    }

    override fun listFiles(dir: String): Flow<FileInfo> = channelFlow {
        // 一条 find+stat 批量命令逐行回调发射：SuFile 的 length()/lastModified()/isDirectory
        // 每条目各一次 root shell 串行往返（N 文件 3N 次），大目录分钟级起；这里与 Shizuku 同构
        val cmd = "find ${dir.shellSingleQuote()} -mindepth 1 -maxdepth 1 " +
            "-exec stat -c '%f|%s|%Y|%n' {} + 2>/dev/null"
        val callback = object : CallbackList<String>(Executor { it.run() }) {
            override fun onAddElement(line: String) {
                // 缓冲满则阻塞 gobbler 线程形成背压；collector 取消后发送失败，忽略即可
                parseShellStatLine(line)?.let { trySendBlocking(it) }
            }
        }
        // exec 阻塞至命令结束且输出收集完毕（运行在 IO dispatcher）
        Shell.cmd(cmd).to(callback).exec()
    }.flowOn(Dispatchers.IO)

    override suspend fun readFile(path: String): InputStream = withContext(Dispatchers.IO) {
        SuFileInputStream.open(SuFile(path))
    }

    override suspend fun readHeader(path: String, n: Int): ByteArray? = withContext(Dispatchers.IO) {
        try {
            SuFileInputStream.open(SuFile(path)).use { stream ->
                val buf = ByteArray(n)
                var total = 0
                while (total < n) {
                    val r = stream.read(buf, total, n - total)
                    if (r == -1) break
                    total += r
                }
                if (total == 0) null else buf.copyOf(total)
            }
        } catch (t: Throwable) {
            Timber.w(t, "Root readHeader failed: $path")
            null
        }
    }

    override suspend fun readHeaders(paths: List<String>, n: Int): List<ByteArray?> =
        withContext(Dispatchers.IO) {
            if (paths.isEmpty()) return@withContext emptyList()
            val result = ArrayList<ByteArray?>(paths.size)
            for (chunk in paths.chunked(SHELL_HEADER_CHUNK)) {
                // 整批一条命令走 libsu 常驻 shell：脚本内逐项 head|base64 带定界输出，
                // 避免每文件一次 SuFileInputStream 开流往返
                val parsed = parseBatchHeaderOutput(runScriptOut(buildBatchReadHeaderScript(chunk, n)), chunk.size)
                if (parsed != null) {
                    result.addAll(parsed)
                } else {
                    // 定界段数不符（输出被截断/base64 不可用）：该批退回逐文件读，保证正确性
                    for (p in chunk) result.add(readHeader(p, n))
                }
            }
            result
        }

    override suspend fun getFileInfo(path: String): FileInfo? = withContext(Dispatchers.IO) {
        val f = SuFile(path)
        if (!f.exists()) return@withContext null
        FileInfo(
            path = f.absolutePath,
            name = f.name,
            size = f.length(),
            lastModified = f.lastModified(),
            isDirectory = f.isDirectory,
        )
    }

    override suspend fun deleteFile(path: String): Boolean = withContext(Dispatchers.IO) {
        SuFile(path).delete()
    }

    override suspend fun deleteFiles(paths: List<String>): BooleanArray = withContext(Dispatchers.IO) {
        if (paths.isEmpty()) return@withContext BooleanArray(0)
        val result = BooleanArray(paths.size)
        var base = 0
        for (chunk in paths.chunked(SHELL_DELETE_CHUNK)) {
            // 整批一条命令走 libsu 常驻 shell：脚本内逐项 rm 回报，避免每文件一次往返
            val script = buildBatchDeleteScript(chunk)
            var parsed = parseBatchResultLines(runScriptOut(script), chunk.size)
            if (parsed == null) {
                // 输出行数不符=结果丢失（shell 中途死亡/输出被截断），rm -f 幂等：重跑同一
                // 脚本复核，已删的会回报成功，避免「文件已删、DB 却不标 deleted」的幽灵行
                Timber.w("Root deleteFiles: 结果行数不符，重跑复核（chunk=${chunk.size}）")
                parsed = parseBatchResultLines(runScriptOut(script), chunk.size)
            }
            // 复核仍不符：该块保持 false（保守记失败），交上层重试
            parsed?.copyInto(result, base)
            base += chunk.size
        }
        result
    }

    override suspend fun moveFile(src: String, dst: String): Boolean = withContext(Dispatchers.IO) {
        SuFile(src).renameTo(SuFile(dst))
    }

    override suspend fun moveFileNoReplace(src: String, dst: String): Boolean = withContext(Dispatchers.IO) {
        val quotedSrc = src.shellSingleQuote()
        val quotedDst = dst.shellSingleQuote()
        val script = "[ ! -e $quotedDst ] || exit 1; " +
            "mv -n -- $quotedSrc $quotedDst 2>/dev/null; " +
            "[ ! -e $quotedSrc ] && [ -e $quotedDst ] || exit 1; " +
            // 跨应用目录时 mv 退化为复制，新文件属于 shell / root：改成与所在目录同属主，改不了就放开读写，否则 QQ 读不到
            "o=\$(stat -c %u:%g -- \"\$(dirname -- $quotedDst)\"); " +
            "[ \"\$(stat -c %u -- $quotedDst)\" = \"\${o%%:*}\" ] || chown \"\$o\" -- $quotedDst 2>/dev/null || chmod 666 -- $quotedDst 2>/dev/null; " +
            "exit 0"
        try {
            Shell.cmd(script).exec().isSuccess
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            false
        }
    }

    override suspend fun moveFiles(pairs: List<Pair<String, String>>): BooleanArray =
        withContext(Dispatchers.IO) {
            if (pairs.isEmpty()) return@withContext BooleanArray(0)
            val result = BooleanArray(pairs.size)
            var base = 0
            for (chunk in pairs.chunked(SHELL_MOVE_CHUNK)) {
                // 整批一条命令：同分区 mv 即 rename，秒级完成；避免每张一次 renameTo 往返
                var parsed = parseBatchResultLines(runScriptOut(buildBatchMoveScript(chunk)), chunk.size)
                if (parsed == null) {
                    // mv 不幂等，结果丢失时不能重跑；用只读脚本按「源已不在且目标已在」复核
                    Timber.w("Root moveFiles: 结果行数不符，按存在性复核（chunk=${chunk.size}）")
                    parsed = parseBatchResultLines(runScriptOut(buildBatchMoveVerifyScript(chunk)), chunk.size)
                }
                parsed?.copyInto(result, base)
                base += chunk.size
            }
            result
        }

    /** 在 libsu 常驻 root shell 执行脚本，返回 stdout 各行拼接（失败返回空串） */
    private fun runScriptOut(script: String): String =
        runCatching { Shell.cmd(script).exec().out.joinToString("\n") }.getOrDefault("")
}
