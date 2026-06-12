package com.xjw.qalbum.fileops

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.topjohnwu.superuser.ipc.RootService
import com.xjw.qalbum.BuildConfig
import com.xjw.qalbum.domain.model.AccessMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/** 已绑定的文件服务句柄；[close] 解绑并回收特权进程（幂等）。 */
class BoundFileOps(
    val ops: IFileOps,
    private val unbinder: () -> Unit,
) {
    private val closed = AtomicBoolean(false)

    fun close() {
        if (closed.compareAndSet(false, true)) unbinder()
    }
}

/**
 * 绑定 [IFileOps] 特权服务：Root → libsu [RootService]；Shizuku → UserService。
 *
 * 绑定失败 / 超时返回 null，由调用方（ScanTransportFactory）降级到 shell 传输，
 * 扫描永远不会因为服务绑定问题而直接失败。
 */
class FileOpsClient(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())

    suspend fun bind(mode: AccessMode): BoundFileOps? = try {
        withTimeoutOrNull(BIND_TIMEOUT_MS) {
            // RootService.bind / Shizuku.bindUserService 都要求主线程调用
            withContext(Dispatchers.Main.immediate) {
                when (mode) {
                    AccessMode.ROOT -> bindRoot()
                    AccessMode.SHIZUKU -> bindShizuku()
                }
            }
        }
    } catch (t: Throwable) {
        Timber.w(t, "FileOpsClient: bind($mode) failed")
        null
    }

    private suspend fun bindRoot(): BoundFileOps = suspendCancellableCoroutine { cont ->
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val ops = binder?.let { IFileOps.Stub.asInterface(it) }
                if (ops != null && cont.isActive) {
                    cont.resume(BoundFileOps(ops) { unbindRootSafely(this) })
                } else {
                    unbindRootSafely(this)
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                // binder 死亡：进行中的调用会抛 DeadObjectException，由扫描层上报失败
            }
        }
        cont.invokeOnCancellation { unbindRootSafely(conn) }
        RootService.bind(Intent(context, RootFileOpsService::class.java), conn)
    }

    private suspend fun bindShizuku(): BoundFileOps = suspendCancellableCoroutine { cont ->
        val args = Shizuku.UserServiceArgs(
            ComponentName(context.packageName, FileOpsUserService::class.java.name),
        )
            .daemon(false)
            .processNameSuffix("fileops")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)

        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val ops = binder
                    ?.takeIf { it.pingBinder() }
                    ?.let { IFileOps.Stub.asInterface(it) }
                if (ops != null && cont.isActive) {
                    cont.resume(BoundFileOps(ops) { unbindShizukuSafely(args, this) })
                } else {
                    unbindShizukuSafely(args, this)
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        cont.invokeOnCancellation { unbindShizukuSafely(args, conn) }
        Shizuku.bindUserService(args, conn)
    }

    private fun unbindRootSafely(conn: ServiceConnection) {
        mainHandler.post {
            try {
                RootService.unbind(conn)
            } catch (t: Throwable) {
                Timber.w(t, "RootService.unbind failed")
            }
        }
    }

    private fun unbindShizukuSafely(args: Shizuku.UserServiceArgs, conn: ServiceConnection) {
        mainHandler.post {
            try {
                Shizuku.unbindUserService(args, conn, true)
            } catch (t: Throwable) {
                Timber.w(t, "Shizuku.unbindUserService failed")
            }
        }
    }

    companion object {
        private const val BIND_TIMEOUT_MS = 5_000L
    }
}
