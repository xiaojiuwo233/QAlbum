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

    override suspend fun deleteFiles(paths: List<String>): BooleanArray = withContext(Dispatchers.IO) {
        if (paths.isEmpty()) return@withContext BooleanArray(0)
        val result = BooleanArray(paths.size)
        var base = 0
        for (chunk in paths.chunked(SHELL_DELETE_CHUNK)) {
            // 整批一条命令走 libsu 常驻 shell：一次 rm 再逐个回报，避免每文件一次往返
            val res = runCatching { Shell.cmd(buildBatchDeleteScript(chunk)).exec() }.getOrNull()
            val lines = res?.out?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
            if (lines.size != chunk.size) {
                Timber.w("Root deleteFiles: expected ${chunk.size} results, got ${lines.size}")
            }
            for (i in chunk.indices) {
                result[base + i] = lines.getOrNull(i) == "1"
            }
            base += chunk.size
        }
        result
    }

    override suspend fun moveFile(src: String, dst: String): Boolean = withContext(Dispatchers.IO) {
        SuFile(src).renameTo(SuFile(dst))
    }

}
