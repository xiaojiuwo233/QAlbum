package com.xjw.qalbum.qq

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.Process
import com.xjw.qalbum.fileops.BoundFileOps
import com.xjw.qalbum.fileops.FileOpsClient
import com.xjw.qalbum.domain.model.AccessMode
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CancellationException
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

    /**
     * 复制这个账号的数据库成私有副本（见 [QqSnapshotCopier]：QQ 在运行时先暂停它的 checkpoint，
     * 主库不变、WAL 可以继续增长；不一致就按库重试）。失败时删掉已复制的部分。
     */
    suspend fun snapshot(bound: BoundFileOps, account: QqAccount, onProgress: (Long, Long) -> Unit): QqDatabaseSnapshot {
        val directory = File(root, UUID.randomUUID().toString()).apply { check(mkdir()) }
        try {
            val source = object : QqSnapshotCopier.Source {
                override suspend fun states(): Map<String, QqSnapshotCopier.FileState> {
                    val json = JSONObject(bound.callWithTimeout { it.qqDatabaseState(userId, account.key) })
                    return buildMap {
                        json.keys().forEach { name ->
                            if (name.endsWith("-shm") || json.isNull(name)) return@forEach
                            val state = json.getJSONObject(name)
                            put(name, QqSnapshotCopier.FileState(state.getLong("size"), state.toString()))
                        }
                    }
                }

                override suspend fun open(name: String): InputStream = ParcelFileDescriptor.AutoCloseInputStream(
                    bound.callWithTimeout(onAbandoned = { it.close() }) { it.openQqDatabaseRead(userId, account.key, name) },
                )

                override suspend fun pin(name: String): Int = try {
                    bound.callWithTimeout { it.pinQqDatabase(userId, account.key, name) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // 加不上锁照样复制：按状态核对、不一致就重试
                    -1
                }

                override suspend fun unpin(handle: Int) {
                    bound.callWithTimeout { it.unpinQqDatabase(handle) }
                }
            }
            val copies = QqSnapshotCopier(source, directory, onProgress).copyAll(root.usableSpace)
            return QqDatabaseSnapshot(directory, copies)
        } catch (t: Throwable) {
            directory.deleteRecursively()
            throw t
        }
    }
}
