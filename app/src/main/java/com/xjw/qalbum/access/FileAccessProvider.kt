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

    /**
     * 高效递归枚举：用一条 shell 命令列出 [roots] 下深度在 [minDepth, maxDepth] 之间的
     * 所有普通文件（非目录），边解析边发射。
     *
     * 相比逐个目录调用 [listFiles]（chatpic 有成百上千个三字符索引目录 → N 次跨进程 IPC），
     * 这里把整个枚举压缩成「一次 find + 分批 stat」，是数量级的提速，
     * 也是消除扫描「卡在启动中」的关键。
     */
    fun enumerateFiles(roots: List<String>, minDepth: Int, maxDepth: Int): Flow<FileInfo>

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

    /** 移动文件，返回是否成功 */
    suspend fun moveFile(src: String, dst: String): Boolean
}

class AccessException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
