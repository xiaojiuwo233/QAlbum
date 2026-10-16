package com.xjw.qalbum.fileops

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

/**
 * 在不改动别人数据库的前提下，暂停它的 WAL checkpoint：对 `-shm` 的 checkpoint 锁字节和 DMS 字节加共享锁
 * （fcntl F_RDLCK，不写任何内容）。
 *
 * - SQLite 做 checkpoint 先要拿 checkpoint 锁的排他锁，被别的进程占着时直接返回 SQLITE_BUSY，不会等待
 *   （sqlite3WalCheckpoint：「Even if there is a busy-handler configured, it will not be invoked」）。
 *   于是主库文件在此期间不被改写，写入照常追加到 WAL。
 * - DMS 共享锁让此期间新打开数据库的连接沿用现有 `-shm`（不会当成第一个连接去截断重建）。
 *
 * 锁字节是 SQLite unix VFS 的固定布局：120 起依次为 WRITE、CKPT、RECOVER、READ0–4，128 为 DMS。
 * 进程退出时锁由内核自动释放。
 */
class WalCheckpointPin private constructor(
    private val file: RandomAccessFile,
    private val locks: List<FileLock>,
) : Closeable {

    override fun close() {
        locks.forEach { runCatching { it.release() } }
        runCatching { file.close() }
    }

    companion object {
        const val CKPT_LOCK_BYTE = 121L
        const val DMS_LOCK_BYTE = 128L

        /** 在 [timeoutMs] 内拿到两把共享锁返回 pin；一直有 checkpoint 或初始化在进行则返回 null */
        fun acquire(shm: File, timeoutMs: Long): WalCheckpointPin? {
            val file = RandomAccessFile(shm, "r")
            val channel = file.channel
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            try {
                while (true) {
                    val checkpoint = channel.tryLock(CKPT_LOCK_BYTE, 1, true)
                    if (checkpoint != null) {
                        val dms = channel.tryLock(DMS_LOCK_BYTE, 1, true)
                        if (dms != null) return WalCheckpointPin(file, listOf(checkpoint, dms))
                        checkpoint.release()
                    }
                    if (System.nanoTime() > deadline) break
                    Thread.sleep(50)
                }
            } catch (_: OverlappingFileLockException) {
                // 本进程已经锁着同一个文件（上一次没释放）：交给调用方按「没拿到」处理
            } catch (e: Exception) {
                file.close()
                throw e
            }
            file.close()
            return null
        }
    }
}
