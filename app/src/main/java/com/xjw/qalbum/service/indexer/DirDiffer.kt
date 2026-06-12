package com.xjw.qalbum.service.indexer

import com.xjw.qalbum.data.db.entity.DirSnapshotEntity
import com.xjw.qalbum.service.indexer.transport.DirStat

/**
 * 目录级 diff —— 增量扫描剪枝的核心判定（纯函数，可单测）。
 *
 * 判定规则：
 * 1. 无快照（新目录）→ 需要扫描
 * 2. mtime 与快照不一致 → 需要扫描（POSIX：目录内增删/重命名必更新目录 mtime）
 * 3. 快照 mtime 落在上次扫描时刻的 slack 窗口内 → 需要扫描。
 *    这是 mtime 整秒粒度的竞态保护：上次扫描 stat 完目录后、同一秒内又有写入，
 *    mtime 可能保持不变。对"上次扫描时仍活跃"的目录多扫一次，目录静止后
 *    自然收敛，不会反复重扫。
 *
 * 已知盲区：文件**原地改写**不更新目录 mtime。chatpic 的 Cache_{hash} 文件
 * 写后不变，常态不存在该场景；全量/深度校验是兜底修正手段。
 */
object DirDiffer {

    data class Result(
        /** 需要进入文件级扫描的目录（含新目录） */
        val toScan: List<DirStat>,
        /** 有快照但磁盘上已消失的目录 → 行转 missing、快照删除 */
        val deletedDirPaths: List<String>,
    )

    fun diff(
        fsDirs: List<DirStat>,
        snapshots: Collection<DirSnapshotEntity>,
        slackMs: Long = DEFAULT_SLACK_MS,
    ): Result {
        val snapByPath = snapshots.associateBy { it.dirPath }
        val toScan = fsDirs.filter { dir ->
            val snap = snapByPath[dir.path] ?: return@filter true
            dir.mtimeMs != snap.mtime || snap.mtime >= snap.scannedAt - slackMs
        }
        val fsPaths = fsDirs.mapTo(HashSet(fsDirs.size)) { it.path }
        val deleted = snapshots.asSequence()
            .filter { it.dirPath !in fsPaths }
            .map { it.dirPath }
            .toList()
        return Result(toScan = toScan, deletedDirPaths = deleted)
    }

    /** mtime 整秒粒度 + 墙钟误差的保护窗口 */
    const val DEFAULT_SLACK_MS = 2_000L
}
