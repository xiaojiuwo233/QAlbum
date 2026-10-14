package com.xjw.qalbum.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 原图压缩的记录：压缩前先写一条 pending 日志，替换成功后转 ready，之后作为这张图的压缩记录留着（详情里显示）。
 *
 * - [path] 是 QQ 缓存里的原图路径，压缩后的文件仍在这里，QQ 照常读取；
 * - [backupPath] 是原图备份（QAlbum 私有目录），为 null 表示没保留原图或备份已删除；
 * - 还原：把备份写回 [path]（替换压缩后的文件）并删除记录；到期或手动删除只删备份、清空 [backupPath]；
 * - [quality] 压缩用的 JPEG 质量（v21 起记录；之前的压缩固定为 85）；[originalMime] 压缩前的格式（v22 起）。
 */
@Entity(
    tableName = "original_backups",
    indices = [Index(value = ["image_id"], unique = true), Index("state")],
)
data class OriginalBackupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "image_id") val imageId: Long,
    @ColumnInfo(name = "path") val path: String,
    @ColumnInfo(name = "backup_path") val backupPath: String?,
    @ColumnInfo(name = "original_size") val originalSize: Long,
    @ColumnInfo(name = "original_mtime") val originalMtime: Long,
    @ColumnInfo(name = "original_width") val originalWidth: Int,
    @ColumnInfo(name = "original_height") val originalHeight: Int,
    @ColumnInfo(name = "compressed_size") val compressedSize: Long,
    @ColumnInfo(name = "compressed_width") val compressedWidth: Int,
    @ColumnInfo(name = "compressed_height") val compressedHeight: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "expires_at") val expiresAt: Long,
    /** pending = 替换前日志；ready = 已替换（有备份时可还原）；restoring = 还原前日志 */
    @ColumnInfo(name = "state") val state: String,
    @ColumnInfo(name = "quality", defaultValue = "0") val quality: Int = 0,
    /** 压缩前的格式（PNG 压缩后是 JPEG，还原时改回来）；v22 以前的记录为空，都是 JPEG */
    @ColumnInfo(name = "original_mime") val originalMime: String? = null,
)
