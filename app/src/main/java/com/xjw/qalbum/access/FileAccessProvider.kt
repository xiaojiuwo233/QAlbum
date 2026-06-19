package com.xjw.qalbum.access

import com.xjw.qalbum.domain.model.AccessMode
import com.xjw.qalbum.domain.model.FileInfo
import kotlinx.coroutines.flow.Flow
import java.io.InputStream

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

    /** 列出目录下的所有文件/子目录（不递归） */
    fun listFiles(dir: String): Flow<FileInfo>

    /** 读取文件流；调用方负责关闭 */
    suspend fun readFile(path: String): InputStream

    /**
     * 只读取文件的前 [n] 字节（用于 magic bytes 类型探测），读完即关闭。
     * 比 [readFile] 读整流再截断更省 IO（尤其是跨进程场景）。失败返回 null。
     */
    suspend fun readHeader(path: String, n: Int): ByteArray?

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
}

class AccessException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** 单次 shell 批删的路径数上限：路径在命令里出现两次，留足 ARG_MAX/单参数长度余量 */
internal const val SHELL_DELETE_CHUNK = 200

/** 用单引号包裹并转义，安全拼进 shell 命令 */
internal fun String.shellSingleQuote(): String = "'" + replace("'", "'\\''") + "'"

/**
 * 构造一条 shell 命令：先一次 `rm -f` 删掉整批（单次 exec），再逐个测存在性回报结果，
 * 每行一个——仍存在→`0`(失败)，已不存在→`1`(成功)。输出行数与 [paths] 一一对应。
 *
 * 用「一次 rm + 逐个内建 test」而非「逐个 rm」，把每文件一次进程派生（Shizuku）或
 * 一条命令往返（Root）压成每批一次。
 */
internal fun buildBatchDeleteScript(paths: List<String>): String = buildString {
    append("rm -f")
    for (p in paths) {
        append(' ')
        append(p.shellSingleQuote())
    }
    append("; for p in")
    for (p in paths) {
        append(' ')
        append(p.shellSingleQuote())
    }
    append("; do [ -e \"\$p\" ] && echo 0 || echo 1; done")
}
