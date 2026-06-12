package com.xjw.qalbum.service.indexer.transport

import com.xjw.qalbum.domain.model.FileInfo
import kotlinx.coroutines.flow.Flow

/** 目录的轻量元信息（目录剪枝只需要 mtime） */
data class DirStat(
    val path: String,
    /** 整秒 ×1000 的毫秒值（与 stat %Y 精度一致，见 FileOpsImpl 的精度说明） */
    val mtimeMs: Long,
)

/**
 * 扫描专用传输层：把「列目录 / 列文件 / 批量读文件头」从具体通道
 * （AIDL 特权服务 or shell 命令）中抽象出来。
 *
 * 与 [com.xjw.qalbum.access.FileAccessProvider] 的关系：后者服务于图片加载、
 * 删除等通用文件操作；ScanTransport 只为扫描管线服务，按扫描的批量访问
 * 模式设计接口，互不影响。
 */
interface ScanTransport {

    /** 传输通道名（日志/诊断用），如 aidl-root / shell-shizuku */
    val name: String

    /** 列出 [root] 的直接子目录（三字符索引目录层） */
    suspend fun listSubdirs(root: String): List<DirStat>

    /** 列出若干目录下深度 1 的普通文件，流式发射（调用方控制每批目录数量） */
    fun listFiles(dirs: List<String>): Flow<FileInfo>

    /**
     * 批量读取每个文件的前 [n] 字节做 magic bytes 识别。
     * 返回与 [paths] 等长的列表，不可读的文件对应 null。
     */
    suspend fun readHeaders(paths: List<String>, n: Int): List<ByteArray?>

    /** 释放底层资源（解绑 AIDL 服务等），幂等 */
    fun close()
}
