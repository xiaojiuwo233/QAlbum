package com.xjw.qalbum.fileops

import android.content.Intent
import android.os.IBinder
import com.topjohnwu.superuser.ipc.RootService

/**
 * Root 宿主：libsu [RootService]，运行在 uid 0 的独立进程。
 *
 * libsu 通过 su 启动该进程并自举 binder，不需要 manifest 声明；
 * 远程进程按类名反射实例化本类（见 proguard keep 规则）。
 */
class RootFileOpsService : RootService() {

    override fun onBind(intent: Intent): IBinder = object : IFileOps.Stub() {
        // 生命周期由 libsu 管理（unbind 即回收进程），destroy 只为满足接口
        override fun destroy() {}

        override fun listSubdirs(root: String, offset: Int, limit: Int): Array<String> =
            FileOpsImpl.listSubdirs(root, offset, limit)

        override fun listFiles(dir: String, offset: Int, limit: Int): Array<String> =
            FileOpsImpl.listFiles(dir, offset, limit)

        override fun readHeaders(paths: Array<String>, n: Int): ByteArray =
            FileOpsImpl.readHeaders(paths, n)
    }
}
