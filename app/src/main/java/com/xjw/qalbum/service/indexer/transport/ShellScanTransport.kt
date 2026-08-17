package com.xjw.qalbum.service.indexer.transport

import com.xjw.qalbum.domain.model.AccessMode
import com.xjw.qalbum.domain.model.FileInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList

/**
 * Shell 传输（兜底）：AIDL 服务绑定失败时使用。
 *
 * 相比旧实现的关键改进：
 * - `find … -exec stat {} +` 由 find 在设备端自行批量调 stat，
 *   不再有「app 侧每 200 个文件一条 stat 命令」的万次跨进程往返；
 * - stdout 全程流式逐行解析（[StreamingShell]），不再缓冲完整输出；
 * - 文件头读取走批量委托（[headerReader]，FileAccessProvider.readHeaders：
 *   每约 200 文件一次进程/命令），不再逐文件 fork —— 首次全量 200w 图从
 *   小时级降到分钟级。深度校验仍建议优先在 AIDL 可用的环境跑。
 */
class ShellScanTransport(
    mode: AccessMode,
    private val shell: StreamingShell,
    private val headerReader: suspend (paths: List<String>, n: Int) -> List<ByteArray?>,
) : ScanTransport {

    override val name: String = "shell-${mode.name.lowercase()}"

    override suspend fun listSubdirs(root: String): List<DirStat> {
        val cmd = "find '${root.escapeShellArg()}' -mindepth 1 -maxdepth 1 -type d " +
            "-exec stat -c '%Y|%n' {} + 2>/dev/null"
        return shell.stream(cmd).toList().mapNotNull { parseDirLine(it) }
    }

    override fun listFiles(dirs: List<String>): Flow<FileInfo> = flow {
        for (chunk in dirs.chunked(FIND_DIRS_PER_CMD)) {
            val args = chunk.joinToString(" ") { "'${it.escapeShellArg()}'" }
            val cmd = "find $args -mindepth 1 -maxdepth 1 -type f " +
                "-exec stat -c '%s|%Y|%n' {} + 2>/dev/null"
            shell.stream(cmd).collect { line ->
                parseFileLine(line)?.let { emit(it) }
            }
        }
    }

    override suspend fun readHeaders(paths: List<String>, n: Int): List<ByteArray?> =
        headerReader(paths, n)

    override fun close() {}

    // ---- 解析（path 放行尾，limit 切分容忍路径里的 '|'）----

    /** "mtimeSec|/abs/path" */
    private fun parseDirLine(line: String): DirStat? {
        if (line.isBlank()) return null
        val parts = line.split('|', limit = 2)
        if (parts.size < 2) return null
        val mtimeSec = parts[0].trim().toLongOrNull() ?: return null
        val path = parts[1]
        if (path.isEmpty()) return null
        return DirStat(path = path, mtimeMs = mtimeSec * 1000L)
    }

    /** "size|mtimeSec|/abs/path" */
    private fun parseFileLine(line: String): FileInfo? {
        if (line.isBlank()) return null
        val parts = line.split('|', limit = 3)
        if (parts.size < 3) return null
        val size = parts[0].trim().toLongOrNull() ?: return null
        val mtimeSec = parts[1].trim().toLongOrNull() ?: return null
        val path = parts[2]
        if (path.isEmpty()) return null
        return FileInfo(
            path = path,
            name = path.substringAfterLast('/'),
            size = size,
            lastModified = mtimeSec * 1000L,
            isDirectory = false,
        )
    }

    private fun String.escapeShellArg(): String = replace("'", "'\\''")

    companion object {
        /** 一条 find 命令携带的目录参数数量（控制命令行长度与单命令产出） */
        private const val FIND_DIRS_PER_CMD = 150
    }
}
