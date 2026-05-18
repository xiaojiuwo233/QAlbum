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
 * Root 模式比 Shizuku 多了访问 QQ 私有目录的能力（QQNT 数据库），
 * 此处先实现通用文件接口，Phase 5 再实现数据库提取相关 API。
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
}
