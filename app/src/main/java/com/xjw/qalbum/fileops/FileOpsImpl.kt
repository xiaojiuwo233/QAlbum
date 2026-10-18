package com.xjw.qalbum.fileops

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.system.ErrnoException
import android.system.StructStat
import android.os.Build
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile

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

    /** 压缩 / 还原原图时在同目录写的临时文件后缀（扫描跳过这类文件，见 ScanPipeline） */
    const val TEMP_SUFFIX = ".qalbum-tmp"

    const val REPLACE_OK = 0
    const val REPLACE_CHANGED = 1
    const val REPLACE_DENIED = 2
    const val REPLACE_IO = 3
    const val REPLACE_MISSING = 4

    const val REPAIR_NONE = 0
    const val REPAIR_DONE = 1
    const val REPAIR_FAILED = 2

    /** [pathStats] 的状态 */
    const val PATH_PRESENT = 1L
    const val PATH_MISSING = 0L
    const val PATH_DENIED = -1L
    const val PATH_ERROR = -2L

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
        val guard = MutationGuard()
        return BooleanArray(sources.size) { i ->
            var reserved: StructStat? = null
            try {
                check(guard.allowed(sources[i]) && guard.allowed(targets[i]))
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
        val guard = MutationGuard()
        return BooleanArray(paths.size) { i ->
            try {
                check(guard.allowed(paths[i]) && matches(paths[i], stamps[i]))
                check(File(paths[i]).delete())
                true
            } catch (_: Exception) { false }
        }
    }

    /** 每个路径两个 int（宽、高），读不到为 0；只读文件头，见 [ImageHeaderParser] */
    fun readImageInfo(paths: Array<String>): IntArray {
        val out = IntArray(paths.size * 2)
        for (i in paths.indices) {
            val size = try {
                RandomAccessFile(paths[i], "r").use { file ->
                    ImageHeaderParser.parse(file.length()) { offset, length -> file.readAt(offset, length) }
                }
            } catch (_: Exception) {
                null
            } ?: continue
            out[i * 2] = size.width
            out[i * 2 + 1] = size.height
        }
        return out
    }

    private fun RandomAccessFile.readAt(offset: Long, length: Int): ByteArray {
        if (offset < 0 || offset >= length()) return ByteArray(0)
        seek(offset)
        val buf = ByteArray(minOf(length.toLong(), length() - offset).toInt())
        var total = 0
        while (total < buf.size) {
            val r = read(buf, total, buf.size - total)
            if (r < 0) break
            total += r
        }
        return if (total == buf.size) buf else buf.copyOf(total)
    }

    /**
     * 原图压缩的替换（见 IFileOps.replaceFileChecked）。全程不留下「原路径没有文件」的窗口：
     * 备份是复制出来的，新内容先落成同目录临时文件，最后一步原子改名覆盖原图。
     * 临时文件由本进程（root / shell）新建，改名前先换成原文件的属主与权限（见 [matchOwnership]），否则 QQ 读不到自己的缓存。
     */
    fun replaceFileChecked(target: String, content: ParcelFileDescriptor, backup: String, stamp: String, mtimeMs: Long): Int {
        val guard = MutationGuard()
        val temp = File("$target$TEMP_SUFFIX")
        var backupCreated = false
        try {
            if (!guard.allowed(target) || (backup.isNotEmpty() && !guard.allowed(backup))) return REPLACE_DENIED
            if (!matches(target, stamp)) return REPLACE_CHANGED
            if (backup.isNotEmpty()) {
                copyExclusive(File(target), File(backup))
                backupCreated = true
            }
            temp.delete()
            ParcelFileDescriptor.AutoCloseInputStream(content).use { input ->
                FileOutputStream(temp).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            matchOwnership(temp.path, ownerReference(target))
            temp.setLastModified(mtimeMs)
            if (!matches(target, stamp)) {
                temp.delete()
                if (backupCreated) File(backup).delete()
                return REPLACE_CHANGED
            }
            Os.rename(temp.path, target)
            return REPLACE_OK
        } catch (_: Exception) {
            temp.delete()
            if (backupCreated) File(backup).delete()
            return REPLACE_IO
        }
    }

    /** 还原原图（见 IFileOps.restoreFileChecked） */
    fun restoreFileChecked(backup: String, target: String, stamp: String, mtimeMs: Long): Int {
        val guard = MutationGuard()
        val temp = File("$target$TEMP_SUFFIX")
        try {
            if (!guard.allowed(target) || !guard.allowed(backup)) return REPLACE_DENIED
            if (!File(backup).isFile) return REPLACE_MISSING
            val targetOk = if (stamp.isEmpty()) !File(target).exists() else matches(target, stamp)
            if (!targetOk) return REPLACE_CHANGED
            temp.delete()
            FileInputStream(backup).use { input ->
                FileOutputStream(temp).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            matchOwnership(temp.path, ownerReference(target))
            temp.setLastModified(mtimeMs)
            val stillOk = if (stamp.isEmpty()) !File(target).exists() else matches(target, stamp)
            if (!stillOk) {
                temp.delete()
                return REPLACE_CHANGED
            }
            Os.rename(temp.path, target)
            File(backup).delete()
            return REPLACE_OK
        } catch (_: Exception) {
            temp.delete()
            return REPLACE_IO
        }
    }

    /** 从回收站送回（见 IFileOps.writeFileChecked）：不覆盖已有文件，新文件的属主与权限跟所在目录一致 */
    fun writeFileChecked(target: String, content: ParcelFileDescriptor, mtimeMs: Long): Int {
        val guard = MutationGuard()
        val temp = File("$target$TEMP_SUFFIX")
        try {
            if (!guard.allowed(target)) return REPLACE_DENIED
            if (File(target).exists()) return REPLACE_CHANGED
            val parent = File(target).parentFile ?: return REPLACE_DENIED
            temp.delete()
            ParcelFileDescriptor.AutoCloseInputStream(content).use { input ->
                FileOutputStream(temp).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            matchOwnership(temp.path, ownerReference(target))
            temp.setLastModified(mtimeMs)
            if (File(target).exists()) {
                temp.delete()
                return REPLACE_CHANGED
            }
            Os.rename(temp.path, target)
            return REPLACE_OK
        } catch (_: Exception) {
            temp.delete()
            return REPLACE_IO
        }
    }

    /**
     * 修正 QQ 目录里属主不对的文件（0.2.1 压缩 / 还原时由 root / shell 新建，QQ 读不到）：与所在目录属主不同的，
     * 改成目录的属主，改不了就放开读写。每个路径返回 0 无需修改、1 已修正、2 失败。
     */
    fun repairOwnership(paths: Array<String>): IntArray {
        val guard = MutationGuard()
        return IntArray(paths.size) { i ->
            try {
                if (!guard.allowed(paths[i])) return@IntArray REPAIR_FAILED
                val file = Os.lstat(paths[i])
                if (!OsConstants.S_ISREG(file.st_mode)) return@IntArray REPAIR_FAILED
                val reference = ownerReference(paths[i])
                if (file.st_uid == reference.st_uid) return@IntArray REPAIR_NONE
                matchOwnership(paths[i], reference)
                REPAIR_DONE
            } catch (_: Exception) {
                REPAIR_FAILED
            }
        }
    }

    /**
     * 新文件该跟谁一致：原文件属于所在目录的属主时照抄原文件（连同权限）；没有原文件、或原文件本身属主就不对
     * （0.2.1 留下的）时按所在目录（QQ 的目录属于 QQ）。
     */
    private fun ownerReference(target: String): StructStat {
        val dir = Os.lstat(File(target).parent ?: "/")
        val file = try {
            Os.lstat(target)
        } catch (_: ErrnoException) {
            return dir
        }
        return if (file.st_uid == dir.st_uid) file else dir
    }

    /**
     * 让本进程新建的 [path] 与 [reference] 同属主、同组、同权限：QQ 的缓存文件属于 QQ（组 ext_data_rw，权限 660），
     * root / shell 新建的文件 QQ 读不到。root 直接改属主；shell（Shizuku）改不了属主，就放开读写权限 ——
     * 文件所在的 QQ 目录只有 QQ 和系统能进，别的应用仍然碰不到它。
     */
    private fun matchOwnership(path: String, reference: StructStat) {
        val owned = try {
            Os.chown(path, reference.st_uid, reference.st_gid)
            true
        } catch (_: ErrnoException) {
            false
        }
        val mode = if (OsConstants.S_ISDIR(reference.st_mode)) 0x1B0 else reference.st_mode and 0x1FF
        Os.chmod(path, if (owned) mode else 0x1B6)
    }

    /** 独占创建 [to]（已存在则失败，不覆盖）后整份复制 [from] 并落盘；fd 由输出流关闭 */
    private fun copyExclusive(from: File, to: File) {
        to.parentFile?.mkdirs()
        val fd = Os.open(to.path, OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_WRONLY, 0x180)
        try {
            FileOutputStream(fd).use { output ->
                FileInputStream(from).use { input -> input.copyTo(output) }
                output.fd.sync()
            }
        } catch (e: Exception) {
            to.delete()
            throw e
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

    /**
     * 关联清理只能操作图片缓存；即使调用方意外传入 QQ 数据库，也拒绝修改。
     * 按父目录解析真实路径（目录级符号链接照样识别）：同一批文件只分布在少数几个目录里，逐个 canonicalPath
     * 要对每一级路径各 stat 一次，是批量移动里最慢的一步，这里按目录缓存。最后一级是符号链接的，
     * 移动 / 删除前的 lstat 核验（必须是普通文件）与独占创建都会拒绝它。
     */
    private class MutationGuard {
        private val dirs = HashMap<String, String>()

        fun allowed(path: String): Boolean {
            if (!path.startsWith('/') || path.split('/').any { it == ".." }) return false
            val slash = path.lastIndexOf('/')
            val parent = if (slash > 0) path.substring(0, slash) else "/"
            val resolved = dirs.getOrPut(parent) { File(parent).canonicalPath }.trimEnd('/') + "/" + path.substring(slash + 1)
            return !QQ_DATABASES.containsMatchIn(resolved)
        }
    }

    private val QQ_DATABASES = Regex("/data/(?:user/\\d+|user_de/\\d+|data)/com\\.tencent\\.mobileqq/databases(?:/|$)")

    // ---- QQ 缓存页 ----

    /** 每个路径 [状态, 文件数, 字节数]（见 IFileOps.pathStats） */
    fun pathStats(paths: Array<String>): LongArray {
        val out = LongArray(paths.size * 3)
        paths.forEachIndexed { i, path ->
            val tree = measure(path)
            out[i * 3] = tree.state
            out[i * 3 + 1] = tree.files
            out[i * 3 + 2] = tree.bytes
        }
        return out
    }

    /** [dir] 下占用最大的 [limit] 项（见 IFileOps.largestChildren） */
    fun largestChildren(dir: String, limit: Int): Array<String> {
        val names = File(dir).list() ?: return emptyArray()
        class Child(val kind: Char, val tree: Tree, val name: String)
        return names.mapNotNull { name ->
            val child = "$dir/$name"
            val stat = try {
                Os.lstat(child)
            } catch (_: ErrnoException) {
                return@mapNotNull null
            }
            if (OsConstants.S_ISDIR(stat.st_mode)) Child('D', measure(child), name) else Child('F', Tree(PATH_PRESENT, 1, stat.st_size), name)
        }
            .sortedByDescending { it.tree.bytes }
            .take(limit)
            .map { "${it.kind}|${it.tree.files}|${it.tree.bytes}|${it.name}" }
            .toTypedArray()
    }

    /**
     * 清空 QQ 的缓存目录（见 IFileOps.clearQqCache）：与 QQ 自己的 FileUtils.deleteFilesInDirectory 一样删掉里面的全部内容、
     * 保留目录本身。只接受 [com.xjw.qalbum.qq.QqCacheTargets] 登记的目录（按真实路径核对）；遍历不跟随符号链接，链接本身照删。
     */
    fun clearQqCache(paths: Array<String>): LongArray {
        val out = LongArray(paths.size * 3)
        paths.forEachIndexed { i, path ->
            val target = com.xjw.qalbum.qq.QqCacheTargets.clearablePath(path)
            if (target == null) {
                out[i * 3] = -1
                return@forEachIndexed
            }
            val result = clearContents(target)
            out[i * 3] = result.files
            out[i * 3 + 1] = result.bytes
            out[i * 3 + 2] = result.failed
        }
        return out
    }

    /** 清空 [path] 里的内容、不核对是不是 QQ 登记的目录（[clearQqCache] 核对过再调；测试直接用）：[删掉的文件数, 字节数, 没删掉的项数] */
    internal fun clearContentsUnchecked(path: String): LongArray = clearContents(path).let { longArrayOf(it.files, it.bytes, it.failed) }

    private class Tree(val state: Long = PATH_PRESENT, var files: Long = 0, var bytes: Long = 0, var failed: Long = 0)

    private fun stateOf(errno: Int): Long = when (errno) {
        OsConstants.ENOENT, OsConstants.ENOTDIR -> PATH_MISSING
        OsConstants.EACCES, OsConstants.EPERM -> PATH_DENIED
        else -> PATH_ERROR
    }

    /** 递归统计 [path]：不跟随符号链接，读不了的子目录跳过 */
    private fun measure(path: String): Tree {
        val stat = try {
            Os.lstat(path)
        } catch (e: ErrnoException) {
            return Tree(stateOf(e.errno))
        }
        if (!OsConstants.S_ISDIR(stat.st_mode)) return Tree(PATH_PRESENT, 1, stat.st_size)
        if (File(path).list() == null) return Tree(PATH_DENIED)
        val tree = Tree()
        val pending = ArrayDeque<String>()
        pending.add(path)
        while (pending.isNotEmpty()) {
            val dir = pending.removeLast()
            val names = File(dir).list() ?: continue
            for (name in names) {
                val child = "$dir/$name"
                val st = try {
                    Os.lstat(child)
                } catch (_: ErrnoException) {
                    continue
                }
                if (OsConstants.S_ISDIR(st.st_mode)) {
                    pending.add(child)
                } else {
                    tree.files++
                    tree.bytes += st.st_size
                }
            }
        }
        return tree
    }

    /** 删掉 [path] 里的全部内容（[path] 是文件时删掉它）：先删文件，再从最深处删空目录 */
    private fun clearContents(path: String): Tree {
        val stat = try {
            Os.lstat(path)
        } catch (e: ErrnoException) {
            return if (stateOf(e.errno) == PATH_MISSING) Tree() else Tree(failed = 1)
        }
        if (!OsConstants.S_ISDIR(stat.st_mode)) {
            return if (File(path).delete()) Tree(files = 1, bytes = stat.st_size) else Tree(failed = 1)
        }
        val result = Tree()
        // 先序发现的子目录：倒过来删就是先删子目录、再删父目录
        val dirs = ArrayList<String>()
        val pending = ArrayDeque<String>()
        pending.add(path)
        while (pending.isNotEmpty()) {
            val dir = pending.removeLast()
            val names = File(dir).list()
            if (names == null) {
                result.failed++
                continue
            }
            for (name in names) {
                val child = "$dir/$name"
                val st = try {
                    Os.lstat(child)
                } catch (e: ErrnoException) {
                    if (stateOf(e.errno) != PATH_MISSING) result.failed++
                    continue
                }
                if (OsConstants.S_ISDIR(st.st_mode)) {
                    dirs += child
                    pending.add(child)
                } else if (File(child).delete()) {
                    result.files++
                    result.bytes += st.st_size
                } else {
                    result.failed++
                }
            }
        }
        for (dir in dirs.asReversed()) {
            if (!File(dir).delete()) result.failed++
        }
        return result
    }

    // ---- 视频页 ----

    private class WalkSnapshot(val root: String, val entries: Array<String>)

    @Volatile
    private var walkSnapshot: WalkSnapshot? = null

    /** 递归列出 [root] 下的普通文件（见 IFileOps.walkFiles）；同一 root 从 offset 0 起的连续翻页基于同一份快照 */
    fun walkFiles(root: String, offset: Int, limit: Int): Array<String> {
        val cached = walkSnapshot
        val snap = if (offset > 0 && cached != null && cached.root == root) cached else {
            WalkSnapshot(root, collectFiles(root)).also { walkSnapshot = it }
        }
        val entries = snap.entries
        if (offset >= entries.size) {
            if (walkSnapshot === snap) walkSnapshot = null
            return emptyArray()
        }
        val end = minOf(offset + limit, entries.size)
        val out = ArrayList<String>(end - offset + 1)
        for (i in offset until end) out += entries[i]
        if (end < entries.size) out += MORE_MARKER else if (walkSnapshot === snap) walkSnapshot = null
        return out.toTypedArray()
    }

    private fun collectFiles(root: String): Array<String> {
        val found = ArrayList<Pair<String, String>>()
        val pending = ArrayDeque<Pair<String, Int>>()
        pending.add("" to 0)
        while (pending.isNotEmpty()) {
            val (relative, depth) = pending.removeLast()
            val dir = if (relative.isEmpty()) root else "$root/$relative"
            val names = File(dir).list() ?: continue
            for (name in names) {
                if (name.startsWith('.')) continue
                val childRelative = if (relative.isEmpty()) name else "$relative/$name"
                val st = try {
                    Os.lstat("$root/$childRelative")
                } catch (_: ErrnoException) {
                    continue
                }
                when {
                    OsConstants.S_ISDIR(st.st_mode) -> if (depth < MAX_WALK_DEPTH) pending.add(childRelative to depth + 1)
                    OsConstants.S_ISREG(st.st_mode) -> found += childRelative to "${st.st_size}|${st.st_mtime * 1000L}|$childRelative"
                }
            }
        }
        found.sortBy { it.first }
        return Array(found.size) { found[it].second }
    }

    private const val MAX_WALK_DEPTH = 12

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
