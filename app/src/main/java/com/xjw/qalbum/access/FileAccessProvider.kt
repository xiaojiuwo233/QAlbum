package com.xjw.qalbum.access

import com.xjw.qalbum.domain.model.AccessMode
import com.xjw.qalbum.domain.model.FileInfo
import kotlinx.coroutines.flow.Flow
import java.io.InputStream
import java.util.Base64

/**
 * 统一文件访问接口：屏蔽 Shizuku / Root 之间的差异。
 *
 * 实现者必须保证：
 * 1. 所有 IO 操作运行在合适的 Dispatcher（IO）上
 * 2. 路径接受绝对路径形式
 * 3. 操作失败时抛出 [AccessException] 或返回明确的失败值
 */
interface FileAccessProvider {

    /** 当前访问模式 */
    fun getAccessMode(): AccessMode

    /** 该提供者当前是否可用（已授权且能正常工作） */
    suspend fun isAvailable(): Boolean

    /** 路径是否存在 */
    suspend fun exists(path: String): Boolean

    /** 当前身份能否读取并进入目录；路径存在不代表有目录枚举权限。 */
    suspend fun canReadDir(dir: String): Boolean = false

    /** 特权身份对目录 [dir] 是否可写入（写+搜索）。用于批量移动前一次性探测目标目录，
     *  跳过注定失败的逐张 mv——Shizuku shell（uid 2000）写不进 App 私有目录。
     *  未覆写时保守返回 false。 */
    suspend fun canWriteDir(dir: String): Boolean = false

    /** 列出目录下的所有文件/子目录（不递归） */
    fun listFiles(dir: String): Flow<FileInfo>

    /** 读取文件流；调用方负责关闭 */
    suspend fun readFile(path: String): InputStream

    /**
     * 只读取文件的前 [n] 字节（用于 magic bytes 类型探测），读完即关闭。
     * 比 [readFile] 读整流再截断更省 IO（尤其是跨进程场景）。失败返回 null。
     */
    suspend fun readHeader(path: String, n: Int): ByteArray?

    /**
     * 批量读文件头：返回与 [paths] 等长、顺序对应的结果，失败/空文件为 null。
     *
     * 默认实现逐个回退 [readHeader]；shell 型 provider 应覆写为「一次进程/命令读一批」，
     * 否则兜底扫描 200w 文件逐个 fork `head` 需数小时。
     */
    suspend fun readHeaders(paths: List<String>, n: Int): List<ByteArray?> =
        paths.map { readHeader(it, n) }

    /** 获取单个文件的元信息 */
    suspend fun getFileInfo(path: String): FileInfo?

    /** 删除文件，返回是否成功 */
    suspend fun deleteFile(path: String): Boolean

    /**
     * 批量删除：返回与 [paths] 等长、顺序对应的成功标志。
     *
     * 默认实现逐个回退到 [deleteFile]；各 provider 应覆写为「一次进程/命令删一批」，
     * 否则在数万张清理时每张起一个特权 shell 进程会非常慢（4w 张约 45min）。
     */
    suspend fun deleteFiles(paths: List<String>): BooleanArray =
        BooleanArray(paths.size) { i -> deleteFile(paths[i]) }

    /** 移动文件，返回是否成功 */
    suspend fun moveFile(src: String, dst: String): Boolean

    /**
     * 不覆盖目标的移动。默认实现提供兼容兜底；Root/Shizuku 必须覆写为同一 shell 内的
     * `mv -n` + 结果复核，避免恢复旧图片时覆盖原路径上后来重新下载的新文件。
     */
    suspend fun moveFileNoReplace(src: String, dst: String): Boolean =
        !exists(dst) && moveFile(src, dst)

    /**
     * 批量移动：[pairs] 为 (src, dst) 列表，返回等长、顺序对应的成功标志。
     *
     * 默认实现逐对回退到 [moveFile]；shell 型 provider 应覆写为「一次进程/命令移一批」，
     * 否则批量移入回收站时每张一次进程派生（数万张分钟级到小时级）。
     */
    suspend fun moveFiles(pairs: List<Pair<String, String>>): BooleanArray =
        BooleanArray(pairs.size) { i -> moveFile(pairs[i].first, pairs[i].second) }
}

class AccessException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** 单次 shell 批删的路径数上限：控制单脚本长度，留足 ARG_MAX/单参数长度余量 */
internal const val SHELL_DELETE_CHUNK = 200

/** 单次 shell 批量移动的 (src,dst) 对数上限：每对两条路径，脚本长度约为批删的两倍 */
internal const val SHELL_MOVE_CHUNK = 100

/** 单次 shell 批量读文件头的路径数上限 */
internal const val SHELL_HEADER_CHUNK = 200

/** 用单引号包裹并转义，安全拼进 shell 命令 */
internal fun String.shellSingleQuote(): String = "'" + replace("'", "'\\''") + "'"

/**
 * 构造一条批删脚本：逐项 `rm -f` 并按**退出码**回报，每行一个——成功→`1`，失败→`0`，
 * 输出行数与 [paths] 一一对应。脚本内 fork rm 远比每文件一次进程派生/命令往返便宜。
 *
 * 不用「一次 rm + 逐项 `[ -e ]` 试存在性」：对无 stat 权限的路径（父目录不可搜索），
 * `[ -e ]` 返回 false 与「文件真的删掉了」不可区分，会把 rm 失败误报成删除成功；
 * rm -f 的退出码两者兼顾（目标本就不存在时同样返回 0，语义仍是「已不存在即成功」）。
 *
 * 整段包进子 shell 并丢弃 stderr：大量删除失败时错误输出可写满无人消费的管道
 * 卡死整个脚本；且 Root 路径跑在 libsu **常驻** shell 里，裸 `exec 2>/dev/null`
 * 会永久改掉常驻 shell 的 stderr，必须用 `( … )` 限定作用域。
 */
internal fun buildBatchDeleteScript(paths: List<String>): String = buildString {
    append("(\n")
    for (p in paths) {
        append("rm -f -- ").append(p.shellSingleQuote()).append(" && echo 1 || echo 0\n")
    }
    append(") 2>/dev/null")
}

/**
 * 构造一条批量移动脚本：逐项 `mv -f` 按退出码回报，成功→`1`，失败→`0`，
 * 输出行数与 [pairs] 一一对应。子 shell 包裹与 stderr 处理同 [buildBatchDeleteScript]。
 */
internal fun buildBatchMoveScript(pairs: List<Pair<String, String>>): String = buildString {
    append("(\n")
    for ((src, dst) in pairs) {
        append("mv -f -- ").append(src.shellSingleQuote()).append(' ')
        append(dst.shellSingleQuote()).append(" && echo 1 || echo 0\n")
    }
    append(") 2>/dev/null")
}

/**
 * 批量移动结果的只读复核脚本：按「源已不在 且 目标已在」逐项回报。
 * mv 不幂等（重跑会把已成功的误报失败），主脚本输出丢失时用它复核；全 shell 内建、无副作用。
 */
internal fun buildBatchMoveVerifyScript(pairs: List<Pair<String, String>>): String = buildString {
    append("(\n")
    for ((src, dst) in pairs) {
        append("[ ! -e ").append(src.shellSingleQuote()).append(" ] && [ -e ")
        append(dst.shellSingleQuote()).append(" ] && echo 1 || echo 0\n")
    }
    append(") 2>/dev/null")
}

/**
 * 解析批量脚本的逐行 `1`/`0` 回报。行数与 [expected] 不符（特权进程中途被杀、
 * 流读取失败导致输出被截断/为空）时返回 null——此时按位对齐会张冠李戴，
 * 由调用方决定复核或整块记失败，禁止直接对位。
 */
internal fun parseBatchResultLines(out: String, expected: Int): BooleanArray? {
    val lines = out.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    if (lines.size != expected) return null
    return BooleanArray(expected) { i -> lines[i] == "1" }
}

/** 批量读头输出的分段定界行：含 base64 字母表外的字符，正常数据中不会出现 */
internal const val HEADER_DELIMITER = "----QALBUM-HDR----"

/**
 * 构造一条批量读文件头脚本：逐项 `head -c n | base64`，段间以 [HEADER_DELIMITER] 行分隔，
 * 把每文件一次进程派生压成每批一次。开头探测 base64 可用性，缺失则直接空输出，
 * 让解析侧按段数不符走逐文件兜底（而不是静默产出整批 null）。
 * 子 shell 包裹（exit 只退出子 shell）与 stderr 处理同 [buildBatchDeleteScript]。
 */
internal fun buildBatchReadHeaderScript(paths: List<String>, n: Int): String = buildString {
    append("(\n")
    append("command -v base64 >/dev/null || exit 127\n")
    for (p in paths) {
        append("head -c ").append(n).append(' ').append(p.shellSingleQuote()).append(" | base64\n")
        append("echo ").append(HEADER_DELIMITER).append('\n')
    }
    append(") 2>/dev/null")
}

/**
 * 解析批量读头脚本输出：按定界行切段、段内 base64 多行拼接后解码，
 * 失败/空文件为 null。段数与 [expected] 不符（输出被截断、base64 不可用）返回 null，
 * 由调用方逐文件兜底。
 */
internal fun parseBatchHeaderOutput(out: String, expected: Int): List<ByteArray?>? {
    val result = ArrayList<ByteArray?>(expected)
    val sb = StringBuilder()
    for (line in out.lineSequence()) {
        val t = line.trim()
        if (t == HEADER_DELIMITER) {
            if (result.size >= expected) return null
            result.add(
                runCatching { Base64.getDecoder().decode(sb.toString()) }
                    .getOrNull()?.takeIf { it.isNotEmpty() }
            )
            sb.setLength(0)
        } else if (t.isNotEmpty()) {
            sb.append(t)
        }
    }
    return if (result.size == expected) result else null
}

/**
 * 解析 `stat -c '%f|%s|%Y|%n'` 的一行输出：`十六进制mode|size|mtimeSec|/full/path`。
 * %f 的类型位（S_IFDIR=0x4000）用于回报 isDirectory，避免把子目录当成
 * size≈4096 的普通文件。path 放行尾，limit 切分容忍路径里的 '|'。
 */
internal fun parseShellStatLine(line: String): FileInfo? {
    if (line.isBlank()) return null
    val parts = line.split('|', limit = 4)
    if (parts.size < 4) return null
    val mode = parts[0].trim().toIntOrNull(16) ?: return null
    val size = parts[1].trim().toLongOrNull() ?: 0L
    val mtimeSec = parts[2].trim().toLongOrNull() ?: 0L
    val path = parts[3]
    if (path.isEmpty()) return null
    return FileInfo(
        path = path,
        name = path.substringAfterLast('/'),
        size = size,
        lastModified = mtimeSec * 1000L,
        isDirectory = (mode and 0xF000) == 0x4000,
    )
}
