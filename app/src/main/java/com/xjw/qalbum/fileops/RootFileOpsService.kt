package com.xjw.qalbum.fileops

import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
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

        override fun deleteFiles(paths: Array<String>): BooleanArray =
            FileOpsImpl.deleteFiles(paths)

        override fun openRead(path: String): ParcelFileDescriptor? =
            FileOpsImpl.openRead(path)

        override fun statFiles(paths: Array<String>): Array<String> = FileOpsImpl.statFiles(paths)
        override fun moveFilesChecked(sources: Array<String>, targets: Array<String>, stamps: Array<String>): BooleanArray =
            FileOpsImpl.moveFilesChecked(sources, targets, stamps)
        override fun deleteFilesChecked(paths: Array<String>, stamps: Array<String>): BooleanArray =
            FileOpsImpl.deleteFilesChecked(paths, stamps)
        override fun readImageInfo(paths: Array<String>): IntArray = FileOpsImpl.readImageInfo(paths)
        override fun replaceFileChecked(target: String, content: ParcelFileDescriptor, backup: String, stamp: String, mtimeMs: Long): Int =
            FileOpsImpl.replaceFileChecked(target, content, backup, stamp, mtimeMs)
        override fun restoreFileChecked(backup: String, target: String, stamp: String, mtimeMs: Long): Int =
            FileOpsImpl.restoreFileChecked(backup, target, stamp, mtimeMs)
        override fun writeFileChecked(target: String, content: ParcelFileDescriptor, mtimeMs: Long): Int =
            FileOpsImpl.writeFileChecked(target, content, mtimeMs)
        override fun repairOwnership(paths: Array<String>): IntArray = FileOpsImpl.repairOwnership(paths)

        override fun qqAccountCatalog(userId: Int): String = QqReadAccess.catalog(userId)
        override fun qqDatabaseState(userId: Int, account: String): String = QqReadAccess.state(userId, account)
        override fun openQqDatabaseRead(userId: Int, account: String, name: String): ParcelFileDescriptor =
            QqReadAccess.openRead(userId, account, name)
        override fun pinQqDatabase(userId: Int, account: String, name: String): Int = QqReadAccess.pin(userId, account, name)
        override fun unpinQqDatabase(handle: Int) = QqReadAccess.unpin(handle)
    }
}
