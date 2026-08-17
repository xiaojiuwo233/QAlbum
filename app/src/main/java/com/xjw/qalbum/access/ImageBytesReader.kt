package com.xjw.qalbum.access

/**
 * 图片字节读取抽象：把「读取一张图的原始字节」与「具体怎么跨进程读」解耦。
 *
 * 分析阶段（哈希/质量、OCR、AI 编码）共用同一个读取器实例，由实现决定走原生
 * AIDL fd 直读还是降级 shell 读，调用方无需关心。
 *
 * 失败语义（调用方必须区分两类）：
 * - 返回 null：仅指**该文件本身**不可读 / 为空（可安全按「此图无法处理」推进）；
 * - 抛 [AccessException]：读取**基础设施**失败（如 Shizuku/Root 整体不可用，文件可读性未知），
 *   调用方不得据此把图片落库为 corrupt / 写空 OCR 记录——应跳过本图并按连续失败策略中止。
 */
fun interface ImageBytesReader {

    /** 读取 [path] 的字节，最多 [cap] 字节；不可读 / 空返回 null；基础设施失败抛 [AccessException]。 */
    suspend fun read(path: String, cap: Int): ByteArray?
}
