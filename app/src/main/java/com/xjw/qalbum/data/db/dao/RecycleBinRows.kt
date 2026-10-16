package com.xjw.qalbum.data.db.dao

import androidx.room.ColumnInfo
import androidx.room.Embedded
import com.xjw.qalbum.data.db.entity.ImageEntity

/**
 * 回收站里的一张图：一次删除的同一张图的各个版本算一组，[image] 是组里最大的文件（status = quarantined，
 * 路径换成了内部占位，原位置是 [originalPath]），[file] 是它在回收站里的文件，[thumbnail] 是移入时 App 生成的缩略图。
 */
data class TrashUnit(
    @Embedded val image: ImageEntity,
    @ColumnInfo(name = "q_id") val quarantineId: Long,
    @ColumnInfo(name = "q_origin") val originalPath: String,
    @ColumnInfo(name = "q_group") val groupId: String?,
    @ColumnInfo(name = "q_reason") val reason: String,
    @ColumnInfo(name = "q_at") val quarantinedAt: Long,
    @ColumnInfo(name = "q_expires") val expiresAt: Long,
    @ColumnInfo(name = "q_file") val file: String,
    @ColumnInfo(name = "q_thumb") val thumbnail: String?,
    @ColumnInfo(name = "q_files") val files: Int,
    @ColumnInfo(name = "q_bytes") val bytes: Long,
)

/** 压缩前保留的原图：[image] 是压缩后的图片（还在 QQ 缓存里），[originalSize] 是备份的大小 */
data class BackupUnit(
    @Embedded val image: ImageEntity,
    @ColumnInfo(name = "b_created") val createdAt: Long,
    @ColumnInfo(name = "b_expires") val expiresAt: Long,
    @ColumnInfo(name = "b_size") val originalSize: Long,
)

/** 回收站里按删除原因的张数与大小 */
data class TrashReasonRow(val reason: String, val count: Int, val bytes: Long)
