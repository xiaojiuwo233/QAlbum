package com.xjw.qalbum.fileops

import kotlin.system.exitProcess

/**
 * Shizuku 宿主：UserService，由 Shizuku 服务端在 shell 进程（uid 2000）里
 * 按类名反射实例化（见 proguard keep 规则）。权限与现有 shell 命令完全一致。
 *
 * 相比此前每条命令 `Shizuku.newProcess` 反射起新进程，本服务整个扫描期间
 * 只有一个进程，调用走 binder（毫秒级），也顺带摆脱了 hidden API 反射。
 */
class FileOpsUserService : IFileOps.Stub() {

    /** Shizuku 约定：服务端通过 destroy() 通知 UserService 进程退出 */
    override fun destroy() {
        exitProcess(0)
    }

    override fun listSubdirs(root: String, offset: Int, limit: Int): Array<String> =
        FileOpsImpl.listSubdirs(root, offset, limit)

    override fun listFiles(dir: String, offset: Int, limit: Int): Array<String> =
        FileOpsImpl.listFiles(dir, offset, limit)

    override fun readHeaders(paths: Array<String>, n: Int): ByteArray =
        FileOpsImpl.readHeaders(paths, n)
}
