package com.xjw.qalbum.fileops

import android.os.ParcelFileDescriptor
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

    override fun qqAccountCatalog(userId: Int): String = error("QQ 图片来源需要 Root 权限")
    override fun qqDatabaseState(userId: Int, account: String): String = error("QQ 图片来源需要 Root 权限")
    override fun openQqDatabaseRead(userId: Int, account: String, name: String): ParcelFileDescriptor =
        error("QQ 图片来源需要 Root 权限")
    override fun pinQqDatabase(userId: Int, account: String, name: String): Int = error("QQ 图片来源需要 Root 权限")
    override fun unpinQqDatabase(handle: Int) = Unit
    override fun forceStopQq(userId: Int): Int = error("QQ 图片来源需要 Root 权限")

    override fun pathStats(paths: Array<String>): LongArray = FileOpsImpl.pathStats(paths)
    override fun largestChildren(dir: String, limit: Int): Array<String> = FileOpsImpl.largestChildren(dir, limit)
    override fun clearQqCache(paths: Array<String>): LongArray = FileOpsImpl.clearQqCache(paths)
    override fun walkFiles(root: String, offset: Int, limit: Int): Array<String> = FileOpsImpl.walkFiles(root, offset, limit)
    override fun findFiles(dirs: Array<String>, prefixes: Array<String>): Array<String> = FileOpsImpl.findFiles(dirs, prefixes)
    override fun allowRenameInto(dir: String): Int = FileOpsImpl.allowRenameInto(dir)
}
