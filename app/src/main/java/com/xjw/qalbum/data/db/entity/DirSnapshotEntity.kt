package com.xjw.qalbum.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 目录快照 —— 增量扫描目录剪枝的依据。
 *
 * chatpic 的结构是固定两层（`chatimg/{三字符目录}/Cache_*`），POSIX 保证目录内
 * 增删/重命名文件必然更新目录 mtime。每次扫描记下每个三字符目录的 mtime，
 * 下次增量只需 stat 目录层（~1.6w 个）即可跳过所有未变化目录 —— 增量成本
 * 从 O(全库 200w 文件) 降为 O(变化目录)。
 *
 * 一致性：快照行与该目录的图片行变更在**同一个事务**里提交。扫描中断时
 * 未提交目录的快照不会更新，下次扫描自然重扫，天然幂等。
 */
@Entity(tableName = "dir_snapshots")
data class DirSnapshotEntity(
    /** 三字符目录的绝对路径，如 .../chatpic/chatimg/abc */
    @PrimaryKey @ColumnInfo(name = "dir_path") val dirPath: String,

    /** 上次扫描时读到的目录 mtime（整秒 ×1000 的毫秒值，与 stat %Y 精度一致） */
    @ColumnInfo(name = "mtime") val mtime: Long,

    /** 该目录上次完成扫描入库的墙钟时间（用于 mtime 同秒竞态的 slack 判定） */
    @ColumnInfo(name = "scanned_at") val scannedAt: Long,
)
