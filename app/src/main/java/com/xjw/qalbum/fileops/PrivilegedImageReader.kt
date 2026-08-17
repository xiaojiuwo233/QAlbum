package com.xjw.qalbum.fileops

import android.os.DeadObjectException
import android.os.ParcelFileDescriptor
import com.xjw.qalbum.access.AccessException
import com.xjw.qalbum.access.AccessManager
import com.xjw.qalbum.access.FileAccessProvider
import com.xjw.qalbum.access.ImageBytesReader
import com.xjw.qalbum.domain.model.AccessMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * 分析期图片字节读取器：把「每图一次跨进程读」从旧的「派生 `cat` 进程（Shizuku）/
 * libsu 逐文件往返（Root）」换成原生 AIDL [IFileOps.openRead]——特权进程内 `open`
 * 文件、把 fd 经 binder 传回，app 侧直读 fd。每图开销从「进程派生（数十毫秒）」降到
 * 「一次 binder 往返（亚毫秒）+ fd 直读」。
 *
 * **一轮分析只绑定一次特权服务，整轮复用**（与扫描的 AidlScanTransport 同范式），
 * [close] 解绑回收特权进程。绑定失败时降级到 [provider] 的 shell/libsu 读；binder
 * 中途死亡（Shizuku 重启、phantom process killer）时重绑一次，仍失败则整体失活走
 * provider——绝不逐图白抛 binder 异常。
 *
 * 失败语义（调用方据此落库，见 AnalysisPipeline）：
 * - 返回 null = **该文件本身不可读**（缺失 / 权限 / 持久坏块），可按损坏处理；
 * - 抛 [AccessException] = **读取基础设施失败**（Shizuku/Root 整体不可用），文件
 *   可读性未知，严禁落库为 is_corrupt——否则整轮剩余的健康图片会被批量误标损坏，
 *   进而被「损坏清理」推荐真实删除。
 *
 * 线程安全：读操作无共享可变状态；[IFileOps] 服务端 binder 线程池天然支持并发调用。
 */
class PrivilegedImageReader internal constructor(
    bound: BoundFileOps?,
    private val provider: FileAccessProvider,
    private val fileOpsClient: FileOpsClient? = null,
    private val mode: AccessMode? = null,
) : ImageBytesReader, AutoCloseable {

    /** 当前活跃的 AIDL 绑定；binder 失效后置 null（失活）或换成重绑的新句柄。 */
    @Volatile
    private var bound: BoundFileOps? = bound

    /** 失活 / 重绑决策互斥：块内并发读同时观察到 binder 死亡时，只有一个执行重绑。 */
    private val failoverMutex = Mutex()

    /** 整轮最多重绑一次：重绑后仍死说明特权侧环境已不可用，不再反复尝试。 */
    private var rebindTried = false

    /** 是否走原生快路径（已绑定 AIDL）。仅用于日志/诊断。 */
    val nativeFast: Boolean get() = bound != null

    override suspend fun read(path: String, cap: Int): ByteArray? = withContext(Dispatchers.IO) {
        val snapshot = bound ?: return@withContext readViaProvider(path, cap)
        val pfd = try {
            snapshot.callWithTimeout(onAbandoned = { pfd: ParcelFileDescriptor? -> pfd?.close() }) { it.openRead(path) }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            // binder 层失败（进程死亡 / 调用超时）：失活或重绑后，本图落到 provider 读
            onAidlFailure(snapshot, t, path)
            return@withContext readViaProvider(path, cap)
        } ?: return@withContext null // binder 正常但文件打不开 → 真不可读，provider 也读不到，不再重试
        try {
            readFd(pfd, cap)
        } catch (io: IOException) {
            // 读取中途 IO 错误（坏块 / 并发截断）只影响本图：换 provider 通道复读一次再定论，
            // 不让一张坏文件炸掉整轮分析
            Timber.w(io, "fd 读取失败，provider 复读一次：$path")
            readViaProvider(path, cap)
        }
    }

    /** 经 fd 读整文件：按 statSize 一次性精确分配，替代 BAOS 倍增+末尾拷贝（峰值 ≈3 倍文件大小）。 */
    private fun readFd(pfd: ParcelFileDescriptor, cap: Int): ByteArray? {
        val statSize = pfd.statSize
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
            if (statSize <= 0L) return input.readCapped(cap) // 大小未知（特殊文件）：回落流式读
            val target = minOf(statSize, cap.toLong()).toInt()
            val out = ByteArray(target)
            var total = 0
            while (total < target) {
                val read = input.read(out, total, target - total)
                if (read == -1) break
                total += read
            }
            return when {
                total == 0 -> null
                total == target -> out
                else -> out.copyOf(total) // 并发截断导致提前 EOF：按实际读到的截断
            }
        }
    }

    /**
     * binder 调用失败的善后：失活当前绑定；若是 binder 死亡且整轮尚未重试过，重绑一次
     * （Shizuku 服务重启 / phantom process killer 杀掉特权进程后可恢复快路径）。
     * 与 ThumbnailTrashManager.purgeChunk 的 aidlAlive 失活范式对齐，避免整轮剩余
     * 每张图都白抛一次 DeadObjectException 再退化到逐图 fork 慢读。
     */
    private suspend fun onAidlFailure(failed: BoundFileOps, cause: Throwable, path: String) {
        failoverMutex.withLock {
            if (bound !== failed) return // 并发失败已被处理（换新 / 失活）
            bound = null
            failed.close()
            if (cause is DeadObjectException && !rebindTried && fileOpsClient != null && mode != null) {
                rebindTried = true
                val fresh = fileOpsClient.bind(mode)
                if (fresh != null) {
                    bound = fresh
                    Timber.i("PrivilegedImageReader: binder 死亡，已重绑特权服务 ($mode)")
                    return
                }
            }
            Timber.w(cause, "PrivilegedImageReader: AIDL 失活，本轮剩余读取降级 provider：$path")
        }
    }

    /**
     * provider（shell/libsu）读，区分两类失败：
     * - [AccessException]（Shizuku/Root 整体不可用）= 基础设施失败，必须向上抛，
     *   文件可读性未知，绝不能折叠成 null 被上层误标 is_corrupt；
     * - 其余 IO 异常 = 单文件不可读，返回 null。
     */
    private suspend fun readViaProvider(path: String, cap: Int): ByteArray? = try {
        provider.readFile(path).use { it.readCapped(cap) }
    } catch (ce: CancellationException) {
        throw ce
    } catch (ae: AccessException) {
        throw ae
    } catch (t: Throwable) {
        Timber.w(t, "provider 读取失败：$path")
        null
    }

    override fun close() {
        bound?.close()
        bound = null
    }

    companion object {
        /** 单图读取字节上限（兜底罕见超大图）；与 HashEngine 解码上限一致。 */
        const val MAX_IMAGE_BYTES = 32 * 1024 * 1024

        /**
         * 按当前访问模式绑定特权文件服务并构造读取器。绑定失败返回降级到 provider 的实例
         * （仍可用，只是慢）。调用方用完务必 [close]。
         */
        suspend fun open(accessManager: AccessManager, fileOpsClient: FileOpsClient): PrivilegedImageReader {
            val provider = accessManager.current()
            val mode = provider.getAccessMode()
            val bound = fileOpsClient.bind(mode)
            if (bound != null) {
                Timber.i("PrivilegedImageReader: 原生 AIDL fd 直读就绪 ($mode)")
            } else {
                Timber.w("PrivilegedImageReader: AIDL 绑定失败，降级 provider 读 ($mode)")
            }
            return PrivilegedImageReader(bound, provider, fileOpsClient, mode)
        }
    }
}

/** 读流到字节数组，最多 [maxBytes] 字节；空内容返回 null。用于大小未知的流（provider / 特殊文件）。 */
private fun InputStream.readCapped(maxBytes: Int): ByteArray? {
    val buffer = ByteArray(64 * 1024)
    val out = ByteArrayOutputStream(64 * 1024)
    var total = 0
    while (total < maxBytes) {
        val read = read(buffer)
        if (read == -1) break
        val toWrite = minOf(read, maxBytes - total)
        out.write(buffer, 0, toWrite)
        total += toWrite
    }
    return if (total == 0) null else out.toByteArray()
}
