package com.xjw.qalbum.fileops

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.system.ErrnoException
import android.system.StructStat
import android.os.Build
import java.io.File
import java.io.FileInputStream

/**
 * [IFileOps] 的真正实现，运行在特权进程内（Root 进程或 Shizuku 的 shell 进程）。
 *
 * 性能要点：
 * - `File.list()` 一次 getdents 拿目录项名字，再对每项 `Os.lstat` 一次拿
 *   类型/大小/mtime —— 每个文件恰好 1 次 stat 系统调用（java.io.File 的
 *   isFile/length/lastModified 是 3 次）。
 * - mtime 统一**截断到整秒**（×1000 返回毫秒）：与 shell `stat -c %Y` 及历史
 *   入库数据保持同一精度。若返回更高精度，升级后首次扫描会把所有未变文件
 *   误判为"已变化"，导致 200w 行的分析状态被整体重置 —— 绝不能发生。
 *
 * 分页约定（规避 binder 1MB 事务上限）：
 * 对排序后的目录项按 [offset, offset+limit) 取窗口；窗口内不符合类型的项被
 * 过滤掉（所以单页返回数可能小于 limit）；若窗口之后还有原始项，结果末尾
 * 追加哨兵 [MORE_MARKER]，客户端据此继续翻页。同一目录从 offset==0 开始的
 * 连续翻页保证基于同一份快照（见 [DirSnapshot]），目录并发变动不会漏项/重项。
 */
internal object FileOpsImpl {

    /** 「还有下一页」哨兵。真实条目都含 '|'，不会冲突。 */
    const val MORE_MARKER = "+"

    fun listSubdirs(root: String, offset: Int, limit: Int): Array<String> =
        listEntries(root, offset, limit, dirsOnly = true)

    fun listFiles(dir: String, offset: Int, limit: Int): Array<String> =
        listEntries(dir, offset, limit, dirsOnly = false)

    fun readHeaders(paths: Array<String>, n: Int): ByteArray {
        val headers = ArrayList<ByteArray?>(paths.size)
        val buf = ByteArray(n)
        for (path in paths) {
            headers.add(readHeader(path, n, buf))
        }
        return HeaderBatchCodec.pack(headers)
    }

    /**
     * 只读打开文件并返回 [ParcelFileDescriptor]：客户端拿到的 fd 指向特权进程已
     * 打开的文件（权限在 open 时校验），可跨进程直读、不走文本管道、不派生进程。
     * 不可读 / 不存在返回 null（客户端按损坏处理）。
     */
    fun openRead(path: String): ParcelFileDescriptor? = try {
        ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
    } catch (_: Exception) {
        null
    }

    /**
     * 批量删除：进程内原生 unlink，每个路径一个布尔结果（顺序对应）。
     * 「本就不存在」按成功处理——与历史 shell `rm -f` 的语义一致（缺失文件返回 0）。
     */
    fun deleteFiles(paths: Array<String>): BooleanArray {
        val res = BooleanArray(paths.size)
        for (i in paths.indices) {
            res[i] = try {
                val f = File(paths[i])
                !f.exists() || f.delete()
            } catch (_: Exception) {
                false
            }
        }
        return res
    }

    fun statFiles(paths: Array<String>): Array<String> = paths.map { path ->
        try {
            val stat = Os.lstat(path)
            if (!OsConstants.S_ISREG(stat.st_mode)) "O"
            else "S|${stat.st_size}|${stat.st_mtime * 1000L}|${stamp(stat)}"
        } catch (e: ErrnoException) { if (e.errno == OsConstants.ENOENT) "M" else "E" }
        catch (_: Exception) { "E" }
    }.toTypedArray()

    fun moveFilesChecked(sources: Array<String>, targets: Array<String>, stamps: Array<String>): BooleanArray {
        require(sources.size == targets.size && sources.size == stamps.size)
        return BooleanArray(sources.size) { i ->
            var reserved: StructStat? = null
            try {
                check(mutationAllowed(sources[i]) && mutationAllowed(targets[i]))
                check(matches(sources[i], stamps[i]))
                // 独占创建自己的空占位：不会覆盖既有隔离文件，也不依赖先 exists 再 rename 的竞态窗口。
                val fd = Os.open(targets[i], OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_WRONLY, 0x180)
                try { reserved = Os.fstat(fd) } finally { Os.close(fd) }
                check(matches(sources[i], stamps[i]))
                Os.rename(sources[i], targets[i])
                reserved = null
                true
            } catch (_: Exception) { false }
            finally {
                reserved?.let { own ->
                    runCatching { val actual = Os.lstat(targets[i]); if (actual.st_ino == own.st_ino && actual.st_dev == own.st_dev) File(targets[i]).delete() }
                }
            }
        }
    }

    fun deleteFilesChecked(paths: Array<String>, stamps: Array<String>): BooleanArray {
        require(paths.size == stamps.size)
        return BooleanArray(paths.size) { i ->
            try {
                check(mutationAllowed(paths[i]) && matches(paths[i], stamps[i]))
                check(File(paths[i]).delete())
                true
            } catch (_: Exception) { false }
        }
    }

    private fun matches(path: String, expected: String): Boolean {
        val stat = Os.lstat(path)
        return OsConstants.S_ISREG(stat.st_mode) && stamp(stat) == expected
    }

    private fun stamp(stat: StructStat): String {
        val modified = if (Build.VERSION.SDK_INT >= 27) "${stat.st_mtim.tv_sec}:${stat.st_mtim.tv_nsec}" else stat.st_mtime.toString()
        val changed = if (Build.VERSION.SDK_INT >= 27) "${stat.st_ctim.tv_sec}:${stat.st_ctim.tv_nsec}" else stat.st_ctime.toString()
        return "${stat.st_dev}:${stat.st_ino}:${stat.st_size}:$modified:$changed"
    }

    private fun mutationAllowed(path: String): Boolean {
        if (!path.startsWith('/') || path.split('/').any { it == ".." }) return false
        // 关联清理只能操作图片缓存；即使调用方意外传入 QQ 数据库，也拒绝修改。
        val resolved = File(path).canonicalPath
        return !Regex("/data/(?:user/\\d+|user_de/\\d+|data)/com\\.tencent\\.mobileqq/databases(?:/|$)").containsMatchIn(resolved)
    }

    // ---- 内部实现 ----

    /**
     * 目录列举快照：同一目录的所有分页基于同一份不可变的排序名单。
     *
     * 早期实现每页都重新 list+sort 再按 offset 取窗——目录被其他 app 并发增删时
     * 排序数组整体位移，窗口边界的条目会被漏掉（在架图片被误标 missing）或重复发出；
     * 大目录（>limit 条）还要付 O(页数 × m·log m) 的重复 readdir+排序。
     * 现在 offset==0 时构建一次快照并缓存，后续页直接切片：既根除窗口漂移，
     * 也把大目录列举降为一次 O(m·log m)。扫描按目录串行翻页，单槽足够；
     * 未命中（特权进程重启 / 交叉调用）时退回临时快照，行为等同旧实现。
     */
    private class DirSnapshot(val dir: String, val names: Array<String>)

    @Volatile
    private var dirSnapshot: DirSnapshot? = null

    private fun listEntries(dir: String, offset: Int, limit: Int, dirsOnly: Boolean): Array<String> {
        val cached = dirSnapshot
        val snap = if (offset > 0 && cached != null && cached.dir == dir) cached else {
            val names = File(dir).list() ?: run {
                dirSnapshot = null
                return emptyArray()
            }
            // 排序保证翻页窗口稳定（readdir 原始序在并发修改时不可靠）
            names.sort()
            DirSnapshot(dir, names).also { dirSnapshot = it }
        }
        val names = snap.names
        if (offset >= names.size) {
            if (dirSnapshot === snap) dirSnapshot = null
            return emptyArray()
        }
        val end = minOf(offset + limit, names.size)
        val out = ArrayList<String>(end - offset + 1)
        for (i in offset until end) {
            val name = names[i]
            try {
                val st = Os.lstat("$dir/$name")
                val isDir = OsConstants.S_ISDIR(st.st_mode)
                if (dirsOnly != isDir) continue
                if (!dirsOnly && !OsConstants.S_ISREG(st.st_mode)) continue
                val mtimeMs = st.st_mtime * 1000L
                out.add(
                    if (dirsOnly) "$mtimeMs|$name"
                    else "${st.st_size}|$mtimeMs|$name"
                )
            } catch (_: Exception) {
                // 单项 lstat 失败（瞬时删除/权限）：跳过该项，不影响整页
            }
        }
        if (end < names.size) {
            out.add(MORE_MARKER)
        } else if (dirSnapshot === snap) {
            // 末页发出后释放快照，长驻特权进程不长期钉住大目录的名单内存
            dirSnapshot = null
        }
        return out.toTypedArray()
    }

    private fun readHeader(path: String, n: Int, buf: ByteArray): ByteArray? = try {
        FileInputStream(path).use { stream ->
            var total = 0
            while (total < n) {
                val r = stream.read(buf, total, n - total)
                if (r == -1) break
                total += r
            }
            if (total == 0) null else buf.copyOf(total)
        }
    } catch (_: Exception) {
        null
    }
}
