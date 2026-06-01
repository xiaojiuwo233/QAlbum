package com.xjw.qalbum.access

import com.xjw.qalbum.domain.model.FileInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import timber.log.Timber

/**
 * 基于 shell 的递归文件枚举，供 Shizuku / Root 两种 provider 共享。
 *
 * 策略（只依赖 toybox / busybox / coreutils 都支持的最基础命令，保证跨设备兼容）：
 * 1. `find <roots> -mindepth M -maxdepth N -type f` 一次列出所有候选路径
 * 2. 把路径按 [STAT_BATCH] 分批，`stat -c '%s|%Y|%n' <batch>` 批量取 size / mtime
 * 3. 每批解析后立即发射，使上层能实时显示「已发现 N」进度
 *
 * 之所以不用 `find -printf`：那是 GNU 扩展，Android 自带的 toybox find 并不支持
 * （旧实现用 -printf，在纯 Shizuku/toybox 环境下会静默返回空 —— 这正是默认 Shizuku
 * 模式下扫不出图片的原因之一）。
 */
internal object ShellEnumerator {

    /** 单条 stat 命令携带的路径数量上限（远小于 ARG_MAX，避免命令行过长） */
    const val STAT_BATCH = 200

    /**
     * @param exec   执行一条 shell 命令并返回完整 stdout 文本
     *               （Shizuku → newProcess；Root → libsu Shell.cmd 注入）
     * @param escape 把单个路径转义成可安全放进单引号里的形式
     */
    fun enumerate(
        roots: List<String>,
        minDepth: Int,
        maxDepth: Int,
        escape: (String) -> String,
        exec: suspend (String) -> String,
    ): Flow<FileInfo> = flow {
        if (roots.isEmpty()) return@flow
        val rootArgs = roots.joinToString(" ") { "'${escape(it)}'" }
        val findCmd =
            "find $rootArgs -mindepth $minDepth -maxdepth $maxDepth -type f 2>/dev/null"
        val paths = exec(findCmd)
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        if (paths.isEmpty()) {
            Timber.w("ShellEnumerator: find returned no files for roots=$roots")
            return@flow
        }

        for (batch in paths.chunked(STAT_BATCH)) {
            val args = batch.joinToString(" ") { "'${escape(it)}'" }
            val statOut = exec("stat -c '%s|%Y|%n' $args 2>/dev/null")
            statOut.lineSequence().forEach { line ->
                parseStatLine(line)?.let { emit(it) }
            }
        }
    }

    /** 解析 `size|mtimeSec|/full/path` 一行 */
    private fun parseStatLine(line: String): FileInfo? {
        if (line.isBlank()) return null
        // 路径放最后，极少数路径可能含 '|'，limit=3 保证前两段是 size/mtime，其余整体为 path
        val parts = line.split('|', limit = 3)
        if (parts.size < 3) return null
        val size = parts[0].trim().toLongOrNull() ?: return null
        val mtimeSec = parts[1].trim().toLongOrNull() ?: 0L
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
}
