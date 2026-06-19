package com.xjw.qalbum.fileops

import android.os.ParcelFileDescriptor
import com.xjw.qalbum.access.AccessManager
import com.xjw.qalbum.access.FileAccessProvider
import com.xjw.qalbum.access.ImageBytesReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * 分析期图片字节读取器：把「每图一次跨进程读」从旧的「派生 `cat` 进程（Shizuku）/
 * libsu 逐文件往返（Root）」换成原生 AIDL [IFileOps.openRead]——特权进程内 `open`
 * 文件、把 fd 经 binder 传回，app 侧直读 fd。每图开销从「进程派生（数十毫秒）」降到
 * 「一次 binder 往返（亚毫秒）+ fd 直读」。
 *
 * **一轮分析只绑定一次特权服务，整轮复用**（与扫描的 AidlScanTransport 同范式），
 * [close] 解绑回收特权进程。绑定失败（AIDL 不可用）时降级到 [provider] 的 shell/libsu
 * 读，行为与改造前一致——永不因服务问题导致分析失败。
 *
 * 线程安全：读操作无共享可变状态；[IFileOps] 服务端 binder 线程池天然支持并发调用。
 */
class PrivilegedImageReader internal constructor(
    private val bound: BoundFileOps?,
    private val provider: FileAccessProvider,
) : ImageBytesReader, AutoCloseable {

    /** 是否走原生快路径（已绑定 AIDL）。仅用于日志/诊断。 */
    val nativeFast: Boolean get() = bound != null

    override suspend fun read(path: String, cap: Int): ByteArray? = withContext(Dispatchers.IO) {
        val ops = bound?.ops ?: return@withContext readViaProvider(path, cap)
        val pfd = try {
            ops.openRead(path)
        } catch (t: Throwable) {
            // 特权进程意外死亡（DeadObjectException）等：本图降级 provider 读，不中断整轮分析
            Timber.w(t, "openRead 异常，降级 provider 读：$path")
            return@withContext readViaProvider(path, cap)
        } ?: return@withContext null // 绑定正常但文件不可读 → 损坏/缺失，provider 也读不到，不再重试
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readCapped(cap) }
    }

    private suspend fun readViaProvider(path: String, cap: Int): ByteArray? =
        runCatching { provider.readFile(path).use { it.readCapped(cap) } }.getOrNull()

    override fun close() {
        bound?.close()
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
            val bound = runCatching { fileOpsClient.bind(provider.getAccessMode()) }.getOrNull()
            if (bound != null) {
                Timber.i("PrivilegedImageReader: 原生 AIDL fd 直读就绪 (${provider.getAccessMode()})")
            } else {
                Timber.w("PrivilegedImageReader: AIDL 绑定失败，降级 provider 读 (${provider.getAccessMode()})")
            }
            return PrivilegedImageReader(bound, provider)
        }
    }
}

/** 读流到字节数组，最多 [maxBytes] 字节；空内容返回 null。 */
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
