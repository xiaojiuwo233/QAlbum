package com.xjw.qalbum.fileops

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
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
 * 追加哨兵 [MORE_MARKER]，客户端据此继续翻页。
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

    // ---- 内部实现 ----

    private fun listEntries(dir: String, offset: Int, limit: Int, dirsOnly: Boolean): Array<String> {
        val names = File(dir).list() ?: return emptyArray()
        // 排序保证翻页窗口稳定（readdir 原始序在并发修改时不可靠）
        names.sort()
        if (offset >= names.size) return emptyArray()
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
        if (end < names.size) out.add(MORE_MARKER)
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
