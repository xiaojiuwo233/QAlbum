package com.xjw.qalbum.qq

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * 把一个账号的 QQ 数据库（主库 + WAL）复制成私有副本。QQ 在运行、边收消息边写时也能得到一致的副本：
 *
 * - 先让 Root 侧暂停 QQ 的 checkpoint 与 WAL 重新开始（[Source.pin]）：复制期间主库不被改写，WAL 只往后追加。
 * - 满足下面任一条就一致：
 *   1. WAL 从复制主库之前到复制完自己，文件头（salt）一直没变：WAL 期间只追加、没有重新开始，复制主库途中
 *      checkpoint 写进主库的页都还在复制到的 WAL 里，SQLite 打开副本时这些页以 WAL 为准，主库被改了也不要紧；
 *      末尾没写完的事务打开时会忽略。
 *   2. 主库从开始复制到 WAL 复制完都没变（大小、inode、修改时间）：WAL 就算重新开始了，复制到的帧要么已经回写进
 *      主库（重放无害），要么是之后的新事务；SQLite 按 salt 和校验和只认有效的前缀。
 * - QQ 写得很频繁、两条都不满足时隔一会儿重试（还没拿到 pin 就再拿一次）。
 */
internal class QqSnapshotCopier(
    private val source: Source,
    private val directory: File,
    private val onProgress: (done: Long, total: Long) -> Unit,
    private val attempts: Int = 6,
    private val retryDelayMs: Long = 1_000,
) {
    /** 文件状态：大小与签名（大小、inode、修改时间等，Root 侧给出；签名不同即内容可能变了） */
    data class FileState(val size: Long, val signature: String)

    interface Source {
        /** 三个数据库及其 WAL 的状态；不存在的文件不在其中 */
        suspend fun states(): Map<String, FileState>
        suspend fun open(name: String): InputStream

        /** 暂停 [name] 的 checkpoint：> 0 句柄，0 不需要，-1 没拿到 */
        suspend fun pin(name: String): Int
        suspend fun unpin(handle: Int)
    }

    /** 复制全部数据库；[freeSpace] 为副本目录剩余空间 */
    suspend fun copyAll(freeSpace: Long): Map<String, QqCopiedDatabase> {
        val states = source.states()
        val names = DATABASES.filter { it in states }
        check(MESSAGES in names) { "QQ 消息数据库不存在" }
        val sizes = names.associateWith { states.getValue(it).size + (states["$it-wal"]?.size ?: 0) }
        val total = sizes.values.sum()
        check(total > 0 && freeSpace > total + 32L * 1024 * 1024) { "空间不足，无法创建 QQ 只读副本" }
        var base = 0L
        val copies = LinkedHashMap<String, QqCopiedDatabase>()
        for (name in names) {
            copies[name] = copyDatabase(name) { done -> onProgress((base + done).coerceAtMost(total), total) }
            base += sizes.getValue(name)
        }
        return copies
    }

    private suspend fun copyDatabase(name: String, progress: (Long) -> Unit): QqCopiedDatabase {
        val wal = "$name-wal"
        var handle = source.pin(name)
        try {
            repeat(attempts) { attempt ->
                currentCoroutineContext().ensureActive()
                // 上次没拿到 pin（QQ 正好在 checkpoint）就再拿一次
                if (handle <= 0 && attempt > 0) handle = source.pin(name)
                val before = source.states()
                val main = before[name] ?: error("QQ 数据库在读取期间发生变化")
                val hasWal = wal in before
                // WAL 文件头要在复制主库之前读：复制主库期间 WAL 没重新开始，副本才一致（见类注释第 1 条）
                val walBefore = if (hasWal) walHeader(wal) else ByteArray(0)
                val header = copyMain(name, main.size, progress)
                val walCopied = header != null && if (hasWal) copyWal(wal) { progress(main.size + it) } else {
                    File(directory, wal).delete()
                    true
                }
                val walAfter = if (hasWal && walCopied) walHeader(wal) else ByteArray(0)
                val walKept = isWalHeader(walBefore) && walBefore.contentEquals(walAfter)
                val mainKept = source.states()[name] == main
                if (header != null && walCopied && (walKept || mainKept)) {
                    Timber.i("QQ snapshot copied: database=%s attempt=%d pinned=%s bytes=%d walKept=%s mainKept=%s",
                        name, attempt + 1, handle > 0, main.size, walKept, mainKept)
                    return QqCopiedDatabase(File(directory, name), header)
                }
                Timber.i("QQ snapshot retry: database=%s attempt=%d pinned=%s complete=%s walCopied=%s walKept=%s mainKept=%s",
                    name, attempt + 1, handle > 0, header != null, walCopied, walKept, mainKept)
                delay(retryDelayMs * (attempt + 1))
            }
        } finally {
            if (handle > 0) runCatching { source.unpin(handle) }
        }
        error("QQ 数据库正在更新，请稍后重试；原有来源索引仍保留")
    }

    /** 主库：去掉开头 1024 字节的 QQ 文件头（解出密钥参数），其余原样复制；复制中文件变短返回 null */
    private suspend fun copyMain(name: String, size: Long, progress: (Long) -> Unit): QqCipherHeader? =
        source.open(name).use { input ->
            val header = ByteArray(QqCipherHeader.SIZE)
            input.readExactly(header)
            val decoded = QqCipherHeader.decode(header)
            var remaining = size - QqCipherHeader.SIZE
            check(remaining >= 0) { "QQ 数据库文件不完整" }
            var done = QqCipherHeader.SIZE.toLong()
            var complete = true
            File(directory, name).outputStream().use { output ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (remaining > 0) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                    if (count <= 0) {
                        complete = false
                        break
                    }
                    output.write(buffer, 0, count)
                    remaining -= count
                    done += count
                    progress(done)
                }
            }
            if (complete) decoded else null
        }

    /** WAL：一直复制到文件末尾；文件中途被删掉或截断（QQ 关闭数据库）返回 false */
    private suspend fun copyWal(name: String, progress: (Long) -> Unit): Boolean = try {
        var done = 0L
        source.open(name).use { input ->
            File(directory, name).outputStream().use { output ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (done < MAX_WAL_BYTES) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    done += count
                    progress(done)
                }
            }
        }
        true
    } catch (_: IOException) {
        // WAL 被删掉或截断（QQ 关闭数据库）：这一轮作废
        false
    } catch (_: IllegalStateException) {
        false
    }

    /** 读不到（文件刚被删掉）时返回空 */
    private suspend fun walHeader(name: String): ByteArray = try {
        readWalHeader(name)
    } catch (_: IOException) {
        ByteArray(0)
    } catch (_: IllegalStateException) {
        ByteArray(0)
    }

    /** 有完整的文件头（不看魔数：只比较复制前后是否同一个头，WAL 重新开始时 salt 一定会变） */
    private fun isWalHeader(header: ByteArray): Boolean = header.size >= WAL_HEADER_BYTES

    private suspend fun readWalHeader(name: String): ByteArray = source.open(name).use { input ->
        val header = ByteArray(WAL_HEADER_BYTES)
        var read = 0
        while (read < header.size) {
            val n = input.read(header, read, header.size - read)
            if (n < 0) break
            read += n
        }
        header.copyOf(read)
    }

    companion object {
        const val MESSAGES = "nt_msg.db"
        val DATABASES = listOf(MESSAGES, "group_info.db", "profile_info.db")

        /** WAL 文件头：magic、版本、页大小、checkpoint 序号、salt 1/2、校验和，重新开始时 salt 与序号都会变 */
        const val WAL_HEADER_BYTES = 32
        private const val BUFFER_BYTES = 128 * 1024

        /** QQ 一直在写时 WAL 复制到这么多就停（多出来的是复制开始后的新消息，不影响一致性） */
        private const val MAX_WAL_BYTES = 512L * 1024 * 1024
    }
}

internal fun InputStream.readExactly(bytes: ByteArray) {
    var offset = 0
    while (offset < bytes.size) {
        val n = read(bytes, offset, bytes.size - offset)
        check(n > 0) { "QQ 数据库文件头不完整" }
        offset += n
    }
}
