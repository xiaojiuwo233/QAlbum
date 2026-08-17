package com.xjw.qalbum.service.indexer.transport

import com.xjw.qalbum.access.AccessManager
import com.xjw.qalbum.domain.model.AccessMode
import com.xjw.qalbum.fileops.FileOpsClient
import timber.log.Timber

/**
 * 按当前访问模式装配扫描传输：优先 AIDL 特权服务，绑定失败自动降级 shell。
 * 返回 null 表示文件访问未就绪（未授权），由扫描层提示用户。
 */
class ScanTransportFactory(
    private val accessManager: AccessManager,
    private val fileOpsClient: FileOpsClient,
) {

    suspend fun create(): ScanTransport? {
        val provider = accessManager.current()
        if (!provider.isAvailable()) return null
        val mode = provider.getAccessMode()

        fileOpsClient.bind(mode)?.let { bound ->
            Timber.i("ScanTransportFactory: AIDL transport ready ($mode)")
            return AidlScanTransport(bound, mode)
        }

        Timber.w("ScanTransportFactory: AIDL bind failed, fallback to shell transport ($mode)")
        val shell = when (mode) {
            AccessMode.ROOT -> RootStreamingShell()
            AccessMode.SHIZUKU -> ShizukuStreamingShell()
        }
        // 批量委托：provider.readHeaders 每约 200 文件一次进程/命令，禁止退回逐文件 fork
        return ShellScanTransport(mode, shell, provider::readHeaders)
    }
}
