package com.xjw.qalbum.access

import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.io.SuFile
import com.topjohnwu.superuser.io.SuFileInputStream
import com.xjw.qalbum.domain.model.AccessMode
import com.xjw.qalbum.domain.model.FileInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.InputStream

/**
 * Root 模式文件访问实现（基于 libsu）
 *
 * Root 模式比 Shizuku 多了访问 QQ 私有目录的能力（QQNT 数据库）。
 * 枚举优先走 [ShellEnumerator]（一次 find + 分批 stat，数量级提速）；
 * 若 shell 路径在某些设备上不可用，回退到 SuFile 递归（可靠但较慢）。
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

    override fun listFiles(dir: String): Flow<FileInfo> = flow {
        val parent = SuFile(dir)
        if (!parent.exists() || !parent.isDirectory) return@flow
        parent.listFiles()?.forEach { f ->
            emit(
                FileInfo(
                    path = f.absolutePath,
                    name = f.name,
                    size = f.length(),
                    lastModified = f.lastModified(),
                    isDirectory = f.isDirectory,
                )
            )
        }
    }.flowOn(Dispatchers.IO)

    override fun enumerateFiles(roots: List<String>, minDepth: Int, maxDepth: Int): Flow<FileInfo> = flow {
        var count = 0
        ShellEnumerator.enumerate(
            roots = roots,
            minDepth = minDepth,
            maxDepth = maxDepth,
            escape = { it.escapeShell() },
            exec = { cmd -> Shell.cmd(cmd).exec().out.joinToString("\n") },
        ).collect { count++; emit(it) }

        // shell 路径没产出任何文件 → 回退到 SuFile 递归（兼容个别 find/stat 行为异常的设备）
        if (count == 0) {
            Timber.w("RootFileAccess.enumerateFiles: shell path empty, fallback to SuFile recursion")
            for (root in roots) {
                suFileRecurse(root, currentDepth = 1, minDepth = minDepth, maxDepth = maxDepth) { emit(it) }
            }
        }
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

    override suspend fun moveFile(src: String, dst: String): Boolean = withContext(Dispatchers.IO) {
        SuFile(src).renameTo(SuFile(dst))
    }

    // ---- 内部实现 ----

    /**
     * SuFile 递归枚举（回退路径）：把 [dir] 下深度在 [minDepth, maxDepth] 之间的普通文件发射出去。
     * 约定：[dir] 本身视为 depth 0，其直接子项为 [currentDepth]。
     */
    private suspend fun suFileRecurse(
        dir: String,
        currentDepth: Int,
        minDepth: Int,
        maxDepth: Int,
        emit: suspend (FileInfo) -> Unit,
    ) {
        if (currentDepth > maxDepth) return
        val children = SuFile(dir).listFiles() ?: return
        for (f in children) {
            if (f.isDirectory) {
                if (currentDepth < maxDepth) {
                    suFileRecurse(f.absolutePath, currentDepth + 1, minDepth, maxDepth, emit)
                }
            } else if (currentDepth in minDepth..maxDepth) {
                emit(
                    FileInfo(
                        path = f.absolutePath,
                        name = f.name,
                        size = f.length(),
                        lastModified = f.lastModified(),
                        isDirectory = false,
                    )
                )
            }
        }
    }

    /** 对 shell 字符串中的单引号做转义 */
    private fun String.escapeShell(): String = replace("'", "'\\''")
}
