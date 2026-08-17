package com.xjw.qalbum.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 清理批次：每次"生成清理建议"会创建一个 batch，包含若干 candidate */
@Entity(tableName = "cleanup_batches")
data class CleanupBatchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "strategy") val strategy: String,
    @ColumnInfo(name = "total_count") val totalCount: Int,
    @ColumnInfo(name = "total_size") val totalSize: Long,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "executed_at") val executedAt: Long? = null,
)

/** 清理候选：每个 candidate 指向一张待审核的图片 */
@Entity(
    tableName = "cleanup_candidates",
    indices = [Index("batch_id"), Index("image_id")]
)
data class CleanupCandidateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "batch_id") val batchId: Long,
    @ColumnInfo(name = "image_id") val imageId: Long,
    @ColumnInfo(name = "reason") val reason: String,
    @ColumnInfo(name = "risk_level") val riskLevel: String,
    @ColumnInfo(name = "score") val score: Float,
    @ColumnInfo(name = "user_decision") val userDecision: String? = null,
)

/** 回收站：被"删除"的图片实际上是被移动到这里，可恢复 */
@Entity(
    tableName = "quarantine",
    indices = [
        Index(value = ["image_id"], unique = true),
        Index("state"),
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
)
