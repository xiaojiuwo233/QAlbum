package com.xjw.qalbum.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 回收站：被"删除"的图片实际上是被移动到这里，可恢复 */
@Entity(
    tableName = "quarantine",
    indices = [
        Index(value = ["image_id"], unique = true),
        Index("state"),
        Index("group_id"),
    ],
)
data class QuarantineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "image_id") val imageId: Long,
    @ColumnInfo(name = "original_path") val originalPath: String,
    @ColumnInfo(name = "quarantine_path") val quarantinePath: String,
    @ColumnInfo(name = "thumbnail_path") val thumbnailPath: String? = null,
    @ColumnInfo(name = "reason") val reason: String,
    @ColumnInfo(name = "ocr_summary") val ocrSummary: String? = null,
    @ColumnInfo(name = "size") val size: Long,
    @ColumnInfo(name = "quarantined_at") val quarantinedAt: Long,
    // 到期时间：UI 展示「自动删除 N 天后」的依据；到期清理走
    // CleanupDao.listExpiredQuarantinePaged 逐页取出后 purgeByRecords 批删，
    // 该链路必须真实接线，否则回收站文件在私有目录无限累积。
    @ColumnInfo(name = "expires_at") val expiresAt: Long,
    /** pending=移入前日志，ready=可恢复，restoring=恢复前日志。 */
    @ColumnInfo(name = "state") val state: String = "ready",
    /** 关联缓存一次操作的同组标识；旧记录为 null，仍按单文件恢复。 */
    @ColumnInfo(name = "group_id") val groupId: String? = null,
    @ColumnInfo(name = "file_count", defaultValue = "1") val fileCount: Int = 1,
    /** 移动前的 inode/大小/纳秒时间戳，用于中断后的副本安全核查。 */
    @ColumnInfo(name = "source_stamp") val sourceStamp: String? = null,
)
