package com.xjw.qalbum.service.indexer

import com.xjw.qalbum.domain.model.ScanProgress
import kotlinx.coroutines.flow.Flow

/**
 * 增量扫描器。
 *
 * 经性能重构后，扫描核心（单命令递归枚举 + (path,size,mtime) 快照 diff + 并发 magic bytes 检测 +
 * 批量入库 / 批量 touch）统一收敛到 [ImageIndexer.fullScan]。它本身就只处理「新增 / 变化」的文件、
 * 跳过未变化的，因此「增量」就是 `force = false` 的那一档：
 *
 * - 增量（这里）：跳过 size/mtime 未变化的文件，最快，用于日常/定时刷新。
 * - 全量（[ScanController.startFullScan]）：`force = true`，对已存在文件也重新识别一遍。
 *
 * 这样两条入口共享同一套高效实现，避免重复逻辑与各自演化。
 */
class IncrementalScanner(
    private val imageIndexer: ImageIndexer,
) {
    suspend fun scan(chatpicRoot: String): Flow<ScanProgress> =
        imageIndexer.fullScan(chatpicRoot, force = false)
}
