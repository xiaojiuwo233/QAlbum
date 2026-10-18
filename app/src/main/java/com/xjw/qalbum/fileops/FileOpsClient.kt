package com.xjw.qalbum.fileops

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.os.SystemClock
import com.topjohnwu.superuser.ipc.RootService
import com.xjw.qalbum.BuildConfig
import com.xjw.qalbum.domain.model.AccessMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/** binder 调用超时：特权进程活着但卡死在存储 IO（坏卡 / FUSE 挂起）。调用方应视同 binder 死亡走降级路径。 */
class BinderCallTimeoutException(message: String) : RemoteException(message)

/** 被放弃的超时 binder 调用在此挂到自然返回（同步 binder 事务不可中断），完成后只做迟到资源的清理。 */
private val abandonedCallScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** 已绑定的文件服务句柄；[close] 使引用计数减一，最后一个句柄关闭时解绑并回收特权进程（幂等）。 */
class BoundFileOps(
    val ops: IFileOps,
    private val unbinder: () -> Unit,
) {
    private val closed = AtomicBoolean(false)

    fun close() {
        if (closed.compareAndSet(false, true)) unbinder()
    }

    /**
     * 带超时的 binder 调用：特权进程卡死在存储 IO 时同步 binder 事务永不回复，
     * 裸调用会让扫描 / 分析 / 清理无限期挂起。超时抛 [BinderCallTimeoutException]
     * （调用方按 binder 死亡降级即可）；被放弃的调用在后台挂到自然返回，其迟到的
     * 返回值经 [onAbandoned] 清理（如关闭 fd），不清理则直接丢弃。
     */
    suspend fun <T> callWithTimeout(
        timeoutMs: Long = CALL_TIMEOUT_MS,
        onAbandoned: ((T) -> Unit)? = null,
        block: (IFileOps) -> T,
    ): T {
        val deferred = abandonedCallScope.async { runCatching { block(ops) } }
        val result = try {
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } catch (ce: CancellationException) {
            abandon(deferred, onAbandoned)
            throw ce
        }
        if (result == null) {
            abandon(deferred, onAbandoned)
            throw BinderCallTimeoutException("binder 调用超过 ${timeoutMs}ms 未回复")
        }
        return result.getOrThrow()
    }

    private fun <T> abandon(deferred: Deferred<Result<T>>, onAbandoned: ((T) -> Unit)?) {
        if (onAbandoned == null) return
        abandonedCallScope.launch {
            runCatching { deferred.await().getOrNull()?.let(onAbandoned) }
        }
    }

    companion object {
        /** 单次 binder 调用超时上限；批量大事务（readHeaders / deleteFiles）调用方可按批规模放宽 */
        const val CALL_TIMEOUT_MS = 30_000L
    }
}

/**
 * 绑定 [IFileOps] 特权服务：Root → libsu [RootService]；Shizuku → UserService。
 *
 * 共享绑定 + 引用计数：扫描 / 分析 / 清理等消费方并发 bind 时复用同一底层连接。
 * Shizuku 侧同一 UserServiceArgs 本就对应同一个 UserService 进程，若各消费方独立
 * unbind(remove=true)，先结束的一方会把还在用的服务一并销毁；现在每个 [BoundFileOps]
 * 句柄 close 一次引用计数减一，归零后闲置一小段时间（见 [release]）才真正解绑回收特权进程。
 *
 * 绑定失败 / 超时返回 null，由调用方（ScanTransportFactory 等）降级到 shell 传输，
 * 扫描永远不会因为服务绑定问题而直接失败。
 */
class FileOpsClient(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 串行化建连：并发消费方在此排队，后到者直接复用先到者刚建好的连接 */
    private val bindMutex = Mutex()

    /** 守护 [sharedByMode] 与引用计数（close 是非 suspend 路径，不能复用 [bindMutex]） */
    private val stateLock = Any()

    /** Root 与 Shizuku 的旧任务可跨设置切换并存，不能用单槽让新模式覆盖仍有引用的旧连接。 */
    private val sharedByMode = mutableMapOf<AccessMode, SharedBinding>()

    /** 共享连接：refCount 记录未关闭的 [BoundFileOps] 句柄数，归零后闲置 [IDLE_RELEASE_MS] 才真正解绑 */
    private class SharedBinding(
        val mode: AccessMode,
        val ops: IFileOps,
        private val unbind: () -> Unit,
    ) {
        var refCount = 1

        /** 最近一次归零的时间（uptimeMillis） */
        var idleSince = 0L
        private val unbound = AtomicBoolean(false)

        fun unbindOnce() {
            if (unbound.compareAndSet(false, true)) unbind()
        }
    }

    suspend fun bind(mode: AccessMode): BoundFileOps? = bindMutex.withLock {
        var before: SharedBinding? = null
        var stale: SharedBinding? = null
        synchronized(stateLock) {
            val cur = sharedByMode[mode]
            if (cur != null && cur.mode == mode && cur.ops.asBinder().pingBinder()) {
                cur.refCount++
                return@withLock BoundFileOps(cur.ops) { release(cur) }
            }
            // 无连接 / 连接已死 / 模式切换：记住旧值，据此识别本次新建的连接
            before = cur
            if (cur != null) {
                sharedByMode.remove(mode)
                stale = cur
            }
        }
        // binder 已死时引用计数已无意义；先一次性解绑退休，旧句柄之后 close 不得再影响新服务。
        stale?.unbindOnce()
        val binding = try {
            withTimeoutOrNull(BIND_TIMEOUT_MS) {
                // RootService.bind / Shizuku.bindUserService 都要求主线程调用
                withContext(Dispatchers.Main.immediate) {
                    when (mode) {
                        AccessMode.ROOT -> bindRoot()
                        AccessMode.SHIZUKU -> bindShizuku()
                    }
                }
            }
        } catch (ce: CancellationException) {
            // 取消必须向上传播：吞掉会被调用方误判成「绑定失败」而继续降级跑 shell 流程
            releaseOrphan(mode, before)
            throw ce
        } catch (t: Throwable) {
            Timber.w(t, "FileOpsClient: bind($mode) failed")
            releaseOrphan(mode, before)
            null
        }
        if (binding == null) {
            // 超时 / 失败；回调可能已注册连接但返回值被取消丢弃（极窄竞态），一并回收
            releaseOrphan(mode, before)
            return@withLock null
        }
        BoundFileOps(binding.ops) { release(binding) }
    }

    private suspend fun bindRoot(): SharedBinding? = suspendCancellableCoroutine { cont ->
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val ops = binder?.let { IFileOps.Stub.asInterface(it) }
                if (ops == null || !cont.isActive) {
                    unbindRootSafely(this)
                    // binder 无效时立即失败返回，不让调用方干等满 5s 超时才降级
                    if (cont.isActive) cont.resume(null)
                    return
                }
                // 先注册再 resume：即使返回值被并发取消 / 超时丢弃，连接也已挂在
                // 模式对应的共享槽中，由 onCancellation 或 bind() 的 releaseOrphan 回收，
                // 不会泄漏 uid 0 的特权常驻进程
                val binding = SharedBinding(AccessMode.ROOT, ops) { unbindRootSafely(this) }
                synchronized(stateLock) { sharedByMode[AccessMode.ROOT] = binding }
                cont.resume(binding) { _, value, _ -> release(value) }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                // binder 死亡：进行中的调用会抛 DeadObjectException，由消费方降级；
                // 下次 bind() 经 pingBinder 检出死连接并重建
            }
        }
        cont.invokeOnCancellation { unbindRootSafely(conn) }
        RootService.bind(Intent(context, RootFileOpsService::class.java), conn)
    }

    private suspend fun bindShizuku(): SharedBinding? = suspendCancellableCoroutine { cont ->
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
                if (ops == null || !cont.isActive) {
                    unbindShizukuSafely(args, this)
                    if (cont.isActive) cont.resume(null)
                    return
                }
                val binding = SharedBinding(AccessMode.SHIZUKU, ops) { unbindShizukuSafely(args, this) }
                synchronized(stateLock) { sharedByMode[AccessMode.SHIZUKU] = binding }
                cont.resume(binding) { _, value, _ -> release(value) }
            }

            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        cont.invokeOnCancellation { unbindShizukuSafely(args, conn) }
        Shizuku.bindUserService(args, conn)
    }

    /**
     * 句柄关闭：引用计数减一。归零后先闲置一会儿再解绑（Shizuku 侧 remove=true 销毁 UserService 进程）：
     * 接连的操作（扫描完紧接着读时长、统计完紧接着展开目录）不必每次重启特权进程——Shizuku 每次新起
     * UserService 要两三秒，紧跟着解绑又重绑还可能拿不到连接。闲置期间再有人绑定就接着用。
     */
    private fun release(binding: SharedBinding) {
        synchronized(stateLock) {
            if (binding.refCount <= 0) return
            binding.refCount--
            if (binding.refCount > 0) return
            binding.idleSince = SystemClock.uptimeMillis()
        }
        mainHandler.postDelayed({ retireIfIdle(binding) }, IDLE_RELEASE_MS)
    }

    /** 闲置够久（期间没人再用）才真正解绑；期间被重新用过的，由它下次归零时安排的检查处理 */
    private fun retireIfIdle(binding: SharedBinding) {
        synchronized(stateLock) {
            if (binding.refCount > 0) return
            if (SystemClock.uptimeMillis() - binding.idleSince < IDLE_RELEASE_MS - 50) return
            if (sharedByMode[binding.mode] === binding) sharedByMode.remove(binding.mode)
        }
        binding.unbindOnce()
    }

    /** 回收「回调已注册、但返回值在送达前被取消 / 超时丢弃」的孤儿连接（见 [bind] 的失败路径） */
    private fun releaseOrphan(mode: AccessMode, before: SharedBinding?) {
        val orphan = synchronized(stateLock) { sharedByMode[mode]?.takeIf { it !== before } }
        if (orphan != null) release(orphan)
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

        /** 最后一个句柄关闭后保留连接的时长 */
        private const val IDLE_RELEASE_MS = 15_000L
    }
}
