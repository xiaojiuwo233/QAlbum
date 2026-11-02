package com.xjw.qalbum.fileops

import android.os.ParcelFileDescriptor

/**
 * 在测试进程里直接调用特权进程用的 [FileOpsImpl]（文件都在本 App 目录下，不需要 Root / Shizuku）。
 * [renames] 为 false 时像 Shizuku 那样改名移不进别的应用目录，只能复制再删。
 */
internal class InProcessFileOps(private val renames: Boolean = true) : IFileOps.Stub() {
    override fun destroy() {}
    override fun listSubdirs(root: String, offset: Int, limit: Int): Array<String> = FileOpsImpl.listSubdirs(root, offset, limit)
    override fun listFiles(dir: String, offset: Int, limit: Int): Array<String> = FileOpsImpl.listFiles(dir, offset, limit)
    override fun readHeaders(paths: Array<String>, n: Int): ByteArray = FileOpsImpl.readHeaders(paths, n)
    override fun deleteFiles(paths: Array<String>): BooleanArray = FileOpsImpl.deleteFiles(paths)
    override fun openRead(path: String): ParcelFileDescriptor? = FileOpsImpl.openRead(path)
    override fun qqAccountCatalog(userId: Int): String = ""
    override fun qqDatabaseState(userId: Int, account: String): String = ""
    override fun openQqDatabaseRead(userId: Int, account: String, name: String): ParcelFileDescriptor? = null
    override fun pinQqDatabase(userId: Int, account: String, name: String): Int = 0
    override fun unpinQqDatabase(handle: Int) = Unit
    override fun statFiles(paths: Array<String>): Array<String> = FileOpsImpl.statFiles(paths)
    override fun moveFilesChecked(sources: Array<String>, targets: Array<String>, stamps: Array<String>): BooleanArray =
        if (renames) FileOpsImpl.moveFilesChecked(sources, targets, stamps) else BooleanArray(sources.size)
    override fun deleteFilesChecked(paths: Array<String>, stamps: Array<String>): BooleanArray = FileOpsImpl.deleteFilesChecked(paths, stamps)
    override fun readImageInfo(paths: Array<String>): IntArray = FileOpsImpl.readImageInfo(paths)
    override fun replaceFileChecked(target: String, content: ParcelFileDescriptor, backup: String, stamp: String, mtimeMs: Long): Int =
        FileOpsImpl.replaceFileChecked(target, content, backup, stamp, mtimeMs)
    override fun restoreFileChecked(backup: String, target: String, stamp: String, mtimeMs: Long): Int =
        FileOpsImpl.restoreFileChecked(backup, target, stamp, mtimeMs)
    override fun writeFileChecked(target: String, content: ParcelFileDescriptor, mtimeMs: Long): Int =
        FileOpsImpl.writeFileChecked(target, content, mtimeMs)
    override fun repairOwnership(paths: Array<String>): IntArray = FileOpsImpl.repairOwnership(paths)
    override fun pathStats(paths: Array<String>): LongArray = FileOpsImpl.pathStats(paths)
    override fun largestChildren(dir: String, limit: Int): Array<String> = FileOpsImpl.largestChildren(dir, limit)
    override fun clearQqCache(paths: Array<String>): LongArray = FileOpsImpl.clearQqCache(paths)
    override fun walkFiles(root: String, offset: Int, limit: Int): Array<String> = FileOpsImpl.walkFiles(root, offset, limit)
    override fun findFiles(dirs: Array<String>, prefixes: Array<String>): Array<String> = FileOpsImpl.findFiles(dirs, prefixes)
    override fun allowRenameInto(dir: String): Int = if (renames) FileOpsImpl.allowRenameInto(dir) else 0
}

/** 绑定好的进程内文件操作 */
internal fun inProcessFiles(renames: Boolean = true) = BoundFileOps(InProcessFileOps(renames)) {}
