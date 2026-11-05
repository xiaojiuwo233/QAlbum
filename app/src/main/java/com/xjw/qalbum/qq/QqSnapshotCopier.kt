package com.xjw.qalbum.qq

import android.os.Build
import android.system.ErrnoException
import android.system.Int64Ref
import android.system.Os
import com.xjw.qalbum.fileops.QqReadAccess
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/** QQ 正在写数据库，这次没复制到一致的副本（不重试，来源页会问要不要强制停止 QQ 再读） */
internal class QqDatabaseBusyException : IllegalStateException("QQ 正在写入数据库")

/**
 * 把一个账号的 QQ 数据库（主库 + WAL）复制成私有副本。QQ 在运行、边收消息边写时也能得到一致的副本：
 *
 * - 先让 Root 侧暂停 QQ 的 checkpoint 与 WAL 重新开始（[Source.pin]）：复制期间主库不被改写，WAL 只往后追加。
 * - WAL 要复制到开始复制它时的长度（之前 checkpoint 进主库的帧都在这一段里，之后追加的不必复制），且复制到的文件头
 *   与复制完时的一样（复制途中没有重新开始、没被截断：只复制到上一轮的前一段时，里面的页比主库里回写的旧）。
 *   再满足下面任一条就一致：
 *   1. WAL 从复制主库之前到复制完自己，文件头（salt）一直没变：WAL 期间只追加、没有重新开始，复制主库途中
 *      checkpoint 写进主库的页都还在复制到的 WAL 里，SQLite 打开副本时这些页以 WAL 为准，主库被改了也不要紧；
 *      末尾没写完的事务打开时会忽略。
 *   2. 主库从开始复制到 WAL 复制完都没变（大小、inode、修改时间）：WAL 在复制它之前重新开始了，复制到的是新一轮的帧，
 *      都比主库新；SQLite 按 salt 和校验和只认有效的前缀。
 * - 不重试（每次要复制几个 GB）：QQ 一直占着 checkpoint 锁时不复制；复制主库途中两条都已不可能满足就停下，不复制剩下的。
 *   这些情况都抛 [QqDatabaseBusyException]。
 */
internal class QqSnapshotCopier(
    private val source: Source,
    private val directory: File,
    private val onProgress: (done: Long, total: Long) -> Unit,
    /** 复制主库时每复制这么多核对一次还能不能一致 */
    private val checkEveryBytes: Long = CHECK_EVERY_BYTES,
) {
    /** 文件状态：大小与签名（大小、inode、修改时间等，Root 侧给出；签名不同即内容可能变了） */
    data class FileState(val size: Long, val signature: String)

    interface Source {
        /** 三个数据库及其 WAL 的状态；不存在的文件不在其中 */
        suspend fun states(): Map<String, FileState>
        suspend fun open(name: String): InputStream

        /**
         * 暂停 [name] 的 checkpoint：> 0 句柄；[QqReadAccess.PIN_NOT_NEEDED] QQ 没在运行；
         * [QqReadAccess.PIN_BUSY] QQ 一直占着锁；[QqReadAccess.PIN_FAILED] 出错没加上
         */
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
        val started = System.currentTimeMillis()
        val handle = source.pin(name)
        val pinMs = System.currentTimeMillis() - started
        fun busy(reason: String): Nothing {
            Timber.i("QQ snapshot busy: database=%s reason=%s pin=%d pinMs=%d ms=%d",
                name, reason, handle, pinMs, System.currentTimeMillis() - started)
            throw QqDatabaseBusyException()
        }
        try {
            // 一直拿不到锁：QQ 正在 checkpoint（改写主库，随后多半让 WAL 重新开始），这时复制多半对不上，不白复制几个 GB
            if (handle == QqReadAccess.PIN_BUSY) busy("pin")
            val before = source.states()
            val main = before[name] ?: busy("main_missing")
            val hasWal = wal in before
            // WAL 文件头要在复制主库之前读：复制主库期间 WAL 没重新开始，副本才一致（见类注释第 1 条）
            val walBefore = if (hasWal) walHeader(wal) else ByteArray(0)
            suspend fun walKept() = isWalHeader(walBefore) && walBefore.contentEquals(walHeader(wal))
            suspend fun mainKept() = source.states()[name] == main
            // 主库变了、WAL 也重新开始了：两条都不可能满足了，不再复制剩下的
            val header = copyMain(name, main.size, progress) { if (!mainKept() && !walKept()) busy("changed_during_copy") }
                ?: busy("main_shrunk")
            val walCopy = File(directory, wal)
            if (hasWal) {
                // 开始复制 WAL 时的长度
                val walSize = source.states()[wal]?.size ?: busy("wal_missing")
                if (copyWal(wal, walSize) { progress(main.size + it) } != walSize) busy("wal_incomplete")
            } else {
                walCopy.delete()
            }
            // 复制到的 WAL 头与现在的不一样：复制途中重新开始了，复制到的可能只是上一轮的前一段
            val walCopied = if (walCopy.length() >= WAL_HEADER_BYTES) walCopy.inputStream().use(::readHeader) else ByteArray(0)
            if (walCopied.isNotEmpty() && !walCopied.contentEquals(walHeader(wal))) busy("wal_restarted")
            val walKept = isWalHeader(walBefore) && walBefore.contentEquals(walCopied)
            val mainKept = mainKept()
            if (!walKept && !mainKept) busy("changed")
            Timber.i("QQ snapshot copied: database=%s pin=%d pinMs=%d bytes=%d wal=%d walKept=%s mainKept=%s ms=%d",
                name, handle, pinMs, main.size, walCopy.length(), walKept, mainKept, System.currentTimeMillis() - started)
            return QqCopiedDatabase(File(directory, name), header, qqRunning = handle != QqReadAccess.PIN_NOT_NEEDED)
        } finally {
            if (handle > 0) runCatching { source.unpin(handle) }
        }
    }

    /**
     * 主库：去掉开头 1024 字节的 QQ 文件头（解出密钥参数），其余原样复制；复制中文件变短返回 null。
     * 每复制 [checkEveryBytes] 调一次 [verify]（发现已经对不上就抛出，停止复制）。
     */
    private suspend fun copyMain(name: String, size: Long, progress: (Long) -> Unit, verify: suspend () -> Unit): QqCipherHeader? =
        source.open(name).use { input ->
            val header = ByteArray(QqCipherHeader.SIZE)
            input.readExactly(header)
            val decoded = QqCipherHeader.decode(header)
            val remaining = size - QqCipherHeader.SIZE
            check(remaining >= 0) { "QQ 数据库文件不完整" }
            var verified = 0L
            val copied = FileOutputStream(File(directory, name)).use { output ->
                copyRange(input, QqCipherHeader.SIZE.toLong(), remaining, output) { done ->
                    progress(QqCipherHeader.SIZE + done)
                    if (done - verified >= checkEveryBytes) {
                        verified = done
                        verify()
                    }
                }
            }
            if (copied == remaining) decoded else null
        }

    /** WAL：复制开头 [size] 字节，返回复制了多少；中途被删掉或截断（QQ 关闭数据库、checkpoint 截断）时不到 [size] */
    private suspend fun copyWal(name: String, size: Long, progress: (Long) -> Unit): Long = try {
        source.open(name).use { input ->
            FileOutputStream(File(directory, name)).use { output -> copyRange(input, 0, size, output) { progress(it) } }
        }
    } catch (_: IOException) {
        -1
    } catch (_: IllegalStateException) {
        -1
    }

    /**
     * 把 [input] 从 [start]（[input] 已经读到这里）起的 [count] 字节写进 [output]，返回复制了多少（源文件变短时不到 [count]）。
     * Android 9 起用 sendfile 在内核里复制（不经过应用内存，几个 GB 少一半拷贝），不支持时按 128 KB 读写。
     */
    private suspend fun copyRange(
        input: InputStream,
        start: Long,
        count: Long,
        output: FileOutputStream,
        onCopied: suspend (Long) -> Unit,
    ): Long {
        var done = 0L
        if (input is FileInputStream && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val offset = Int64Ref(start)
            while (done < count) {
                currentCoroutineContext().ensureActive()
                val n = try {
                    Os.sendfile(output.fd, input.fd, offset, minOf(count - done, SENDFILE_CHUNK_BYTES))
                } catch (e: ErrnoException) {
                    // 一开始就不支持（还没复制）就退回读写；复制了一部分才出错按读失败处理
                    if (done > 0) throw IOException(e)
                    Timber.i("QQ snapshot sendfile unavailable: errno=%d", e.errno)
                    break
                }
                if (n <= 0) return done
                done += n
                onCopied(done)
            }
            if (done == count) return done
        }
        val buffer = ByteArray(BUFFER_BYTES)
        while (done < count) {
            currentCoroutineContext().ensureActive()
            val n = input.read(buffer, 0, minOf(count - done, buffer.size.toLong()).toInt())
            if (n <= 0) break
            output.write(buffer, 0, n)
            done += n
            onCopied(done)
        }
        return done
    }

    /** 读不到（文件刚被删掉）时返回空 */
    private suspend fun walHeader(name: String): ByteArray = try {
        source.open(name).use(::readHeader)
    } catch (_: IOException) {
        ByteArray(0)
    } catch (_: IllegalStateException) {
        ByteArray(0)
    }

    /** 有完整的文件头（不看魔数：只比较复制前后是否同一个头，WAL 重新开始时 salt 一定会变） */
    private fun isWalHeader(header: ByteArray): Boolean = header.size >= WAL_HEADER_BYTES

    private fun readHeader(input: InputStream): ByteArray {
        val header = ByteArray(WAL_HEADER_BYTES)
        var read = 0
        while (read < header.size) {
            val n = input.read(header, read, header.size - read)
            if (n < 0) break
            read += n
        }
        return header.copyOf(read)
    }

    companion object {
        const val MESSAGES = "nt_msg.db"
        val DATABASES = listOf(MESSAGES, "group_info.db", "profile_info.db")

        /** WAL 文件头：magic、版本、页大小、checkpoint 序号、salt 1/2、校验和，重新开始时 salt 与序号都会变 */
        const val WAL_HEADER_BYTES = 32
        private const val BUFFER_BYTES = 128 * 1024

        /** sendfile 一次复制这么多（之间更新进度、响应取消） */
        private const val SENDFILE_CHUNK_BYTES = 8L * 1024 * 1024
        private const val CHECK_EVERY_BYTES = 256L * 1024 * 1024
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
