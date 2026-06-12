package com.xjw.qalbum.service.indexer.transport

import com.xjw.qalbum.domain.model.AccessMode
import com.xjw.qalbum.domain.model.FileInfo
import com.xjw.qalbum.fileops.BoundFileOps
import com.xjw.qalbum.fileops.FileOpsImpl
import com.xjw.qalbum.fileops.HeaderBatchCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * AIDL 特权服务传输（首选）：所有文件操作是特权进程内的原生系统调用，
 * 跨进程只走 binder（毫秒级/批），没有任何进程创建与文本管道开销。
 */
class AidlScanTransport(
    private val bound: BoundFileOps,
    mode: AccessMode,
) : ScanTransport {

    override val name: String = "aidl-${mode.name.lowercase()}"

    override suspend fun listSubdirs(root: String): List<DirStat> = withContext(Dispatchers.IO) {
        val out = ArrayList<DirStat>()
        forEachPaged({ offset -> bound.ops.listSubdirs(root, offset, PAGE_LIMIT) }) { entry ->
            // "mtimeMs|name"
            val sep = entry.indexOf('|')
            if (sep > 0) {
                val mtime = entry.substring(0, sep).toLongOrNull()
                if (mtime != null) out.add(DirStat("$root/${entry.substring(sep + 1)}", mtime))
            }
        }
        out
    }

    override fun listFiles(dirs: List<String>): Flow<FileInfo> = flow {
        for (dir in dirs) {
            forEachPaged({ offset -> bound.ops.listFiles(dir, offset, PAGE_LIMIT) }) { entry ->
                // "size|mtimeMs|name"
                parseFileEntry(dir, entry)?.let { emit(it) }
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun readHeaders(paths: List<String>, n: Int): List<ByteArray?> = coroutineScope {
        val semaphore = Semaphore(HEADER_CONCURRENCY)
        paths.chunked(HEADER_BATCH).map { chunk ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val blob = bound.ops.readHeaders(chunk.toTypedArray(), n)
                    HeaderBatchCodec.unpack(blob, chunk.size)
                }
            }
        }.awaitAll().flatten()
    }

    override fun close() = bound.close()

    /**
     * 分页协议：空数组 = 结束；页末尾的 [FileOpsImpl.MORE_MARKER] = 还有下一页。
     * 内联保证 [consume] 里可以直接调用 suspend 的 Flow.emit。
     */
    private inline fun forEachPaged(
        fetch: (offset: Int) -> Array<String>,
        consume: (String) -> Unit,
    ) {
        var offset = 0
        while (true) {
            val page = fetch(offset)
            if (page.isEmpty()) return
            var hasMore = false
            for (entry in page) {
                if (entry == FileOpsImpl.MORE_MARKER) hasMore = true else consume(entry)
            }
            if (!hasMore) return
            offset += PAGE_LIMIT
        }
    }

    private fun parseFileEntry(dir: String, entry: String): FileInfo? {
        val parts = entry.split('|', limit = 3)
        if (parts.size < 3) return null
        val size = parts[0].toLongOrNull() ?: return null
        val mtime = parts[1].toLongOrNull() ?: return null
        val name = parts[2]
        if (name.isEmpty()) return null
        return FileInfo(
            path = "$dir/$name",
            name = name,
            size = size,
            lastModified = mtime,
            isDirectory = false,
        )
    }

    companion object {
        /** 单页条目上限：3000 × ~60 字符 × 2B ≈ 360KB，binder 1MB 限制内留足余量 */
        private const val PAGE_LIMIT = 3000

        /** 单次 binder 调用携带的 header 路径数 */
        private const val HEADER_BATCH = 400

        /** header 批次的并发 binder 调用数（服务端 binder 线程池并行读盘） */
        private const val HEADER_CONCURRENCY = 4
    }
}
