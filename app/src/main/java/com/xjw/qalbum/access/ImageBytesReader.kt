package com.xjw.qalbum.access

/**
 * 图片字节读取抽象：把「读取一张图的原始字节」与「具体怎么跨进程读」解耦。
 *
 * 分析阶段（哈希/质量、OCR、AI 编码）共用同一个读取器实例，由实现决定走原生
 * AIDL fd 直读还是降级 shell 读，调用方无需关心。失败 / 空文件返回 null。
 */
fun interface ImageBytesReader {

    /** 读取 [path] 的字节，最多 [cap] 字节；不可读 / 空返回 null。 */
    suspend fun read(path: String, cap: Int): ByteArray?
}
