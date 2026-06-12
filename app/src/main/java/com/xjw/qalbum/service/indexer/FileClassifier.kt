package com.xjw.qalbum.service.indexer

import com.xjw.qalbum.data.db.dao.ImageDao.ScanRow
import com.xjw.qalbum.domain.model.FileInfo

/**
 * 单目录内的文件级 diff（纯函数，可单测）：
 * 磁盘文件列表 vs 该目录的 DB 行 → 新增 / 变化 / 复活 / 消失 / 未变。
 */
object FileClassifier {

    data class Result(
        /** 库里没有的文件 → 识别 + insert */
        val newFiles: List<FileInfo>,
        /** size/mtime 变了 → 重新识别 + update（updateFromScanByPath 会重置分析状态） */
        val changedFiles: List<FileInfo>,
        /** 文件以相同 size/mtime 重新出现且行是 missing → 仅恢复 active */
        val reviveIds: List<Long>,
        /** 行是 active 但磁盘上已不存在 → 标记 missing */
        val missingIds: List<Long>,
        /** 未变化的 (文件, 行) 配对 —— 深度校验时用来复核 mime */
        val unchangedPairs: List<Pair<FileInfo, ScanRow>>,
        val unchangedCount: Int,
    )

    /**
     * @param collectUnchanged 是否收集未变化配对（仅深度校验需要，避免常规扫描白攒列表）
     */
    fun classify(
        fsFiles: List<FileInfo>,
        dbRows: List<ScanRow>,
        collectUnchanged: Boolean = false,
    ): Result {
        val rowByPath = dbRows.associateBy { it.path }
        val newFiles = ArrayList<FileInfo>()
        val changedFiles = ArrayList<FileInfo>()
        val reviveIds = ArrayList<Long>()
        val unchangedPairs = if (collectUnchanged) ArrayList<Pair<FileInfo, ScanRow>>() else null
        var unchangedCount = 0

        val seenPaths = HashSet<String>(fsFiles.size * 2)
        for (f in fsFiles) {
            if (!seenPaths.add(f.path)) continue // 翻页竞态可能产生重复项，去重
            val row = rowByPath[f.path]
            when {
                row == null -> newFiles.add(f)
                row.size != f.size || row.mtime != f.lastModified -> changedFiles.add(f)
                else -> {
                    unchangedCount++
                    // 同内容回归的 missing 行只需恢复，不重置分析状态
                    if (row.status == STATUS_MISSING) reviveIds.add(row.id)
                    unchangedPairs?.add(f to row)
                }
            }
        }

        // 行存在但文件没了 → missing。只动 active：
        // deleted/quarantined 是用户操作结果；thumbnail_trash 由回收站流程自管。
        val missingIds = dbRows.asSequence()
            .filter { it.status == STATUS_ACTIVE && it.path !in seenPaths }
            .map { it.id }
            .toList()

        return Result(
            newFiles = newFiles,
            changedFiles = changedFiles,
            reviveIds = reviveIds,
            missingIds = missingIds,
            unchangedPairs = unchangedPairs.orEmpty(),
            unchangedCount = unchangedCount,
        )
    }

    private const val STATUS_ACTIVE = "active"
    private const val STATUS_MISSING = "missing"
}
