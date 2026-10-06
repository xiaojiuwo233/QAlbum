package com.xjw.qalbum.qq

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.Process
import com.xjw.qalbum.fileops.BoundFileOps
import com.xjw.qalbum.fileops.FileOpsClient
import com.xjw.qalbum.domain.model.AccessMode
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.UUID

internal data class QqAccount(val key: String, val uid: String)
internal data class QqCopiedDatabase(val file: File, val header: QqCipherHeader)
internal class QqDatabaseSnapshot(val directory: File, val databases: Map<String, QqCopiedDatabase>) : AutoCloseable {
    override fun close() {
        directory.deleteRecursively()
    }
}

internal class QqSnapshotReader(context: Context, private val client: FileOpsClient) {
    private val root = File(context.cacheDir, "qq-source-snapshots").apply {
        // 崩溃遗留的加密副本不跨启动保留，也不会进入 Android 备份。
        if (exists()) deleteRecursively()
        mkdirs()
    }
    private val userId = Process.myUid() / 100_000

    suspend fun bind(): BoundFileOps {
        // Root 首次授权/初始化不占用 RootService 的短绑定超时窗口。
        check(Shell.getShell().isRoot) { "未取得 QAlbum 的 Root 授权" }
        return client.bind(AccessMode.ROOT) ?: error("Root 已授权，但文件服务未能启动")
    }

    suspend fun accounts(bound: BoundFileOps): List<QqAccount> {
        val catalog = JSONArray(bound.callWithTimeout { it.qqAccountCatalog(userId) })
        return (0 until catalog.length()).map { index ->
            val account = catalog.getJSONObject(index)
            QqAccount(account.getString("account"), account.getString("uid"))
        }
    }

    suspend fun snapshot(bound: BoundFileOps, account: QqAccount, onProgress: (Long, Long) -> Unit): QqDatabaseSnapshot {
        repeat(3) { attempt ->
            currentCoroutineContext().ensureActive()
            val directory = File(root, UUID.randomUUID().toString()).apply { check(mkdir()) }
            var beforeState: String? = null
            try {
                beforeState = bound.callWithTimeout { it.qqDatabaseState(userId, account.key) }
                val before = JSONObject(beforeState)
                val names = before.keys().asSequence().filter { !it.endsWith("-shm") && !before.isNull(it) }.sorted().toList()
                check("nt_msg.db" in names) { "QQ 消息数据库不存在" }
                val total = names.sumOf { before.getJSONObject(it).getLong("size") }
                check(total > 0 && root.usableSpace > total + 32L * 1024 * 1024) { "空间不足，无法创建 QQ 只读副本" }
                var done = 0L
                val copies = mutableMapOf<String, QqCopiedDatabase>()
                for (name in names) {
                    currentCoroutineContext().ensureActive()
                    val fd = bound.callWithTimeout(onAbandoned = { it.close() }) { it.openQqDatabaseRead(userId, account.key, name) }
                    val output = File(directory, name)
                    ParcelFileDescriptor.AutoCloseInputStream(fd).use { input ->
                        val originalSize = before.getJSONObject(name).getLong("size")
                        val isDatabase = name.endsWith(".db")
                        if (isDatabase) {
                            val header = ByteArray(QqCipherHeader.SIZE)
                            input.readExactly(header)
                            copies[name] = QqCopiedDatabase(output, QqCipherHeader.decode(header))
                            done += header.size
                        }
                        var remaining = originalSize - if (isDatabase) QqCipherHeader.SIZE else 0
                        check(remaining >= 0) { "QQ 数据库文件不完整" }
                        output.outputStream().use { stream ->
                            val buffer = ByteArray(128 * 1024)
                            while (remaining > 0) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                                check(count > 0) { "QQ 数据库在读取期间发生变化" }
                                stream.write(buffer, 0, count)
                                remaining -= count
                                done += count
                                onProgress(done, total)
                            }
                        }
                        check(input.read() == -1) { "QQ 数据库在读取期间发生变化" }
                    }
                }
                val after = JSONObject(bound.callWithTimeout { it.qqDatabaseState(userId, account.key) })
                val stable = before.keys().asSequence().all { name -> before.opt(name)?.toString() == after.opt(name)?.toString() }
                if (stable) return QqDatabaseSnapshot(directory, copies)
            } catch (t: Throwable) {
                directory.deleteRecursively()
                if (t is CancellationException) throw t
                // WAL 轮换可能让读取在 stat 复核前就失败；此时仍只丢弃副本并重试。
                if (attempt < 2 && beforeState != null) {
                    val current = runCatching { bound.callWithTimeout { it.qqDatabaseState(userId, account.key) } }.getOrNull()
                    if (current != null && current != beforeState) return@repeat
                }
                throw t
            }
            directory.deleteRecursively()
        }
        error("QQ 数据库正在更新，请稍后重试；原有来源索引仍保留")
    }

    private fun InputStream.readExactly(bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val n = read(bytes, offset, bytes.size - offset)
            check(n > 0) { "QQ 数据库文件头不完整" }
            offset += n
        }
    }
}
