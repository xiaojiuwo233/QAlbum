package com.xjw.qalbum.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 标签字典表
 *
 * - `type`：system（系统预置） / ai（AI 推理） / user（用户自定义）
 * - `auto_protect` / `auto_delete`：作为自动收藏/自动清理规则参与评分引擎计算
 */
@Entity(
    tableName = "tags",
    indices = [Index("name", unique = true)]
)
data class TagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "type") val type: String,
    @ColumnInfo(name = "color") val color: String? = null,
    @ColumnInfo(name = "auto_protect") val autoFavorite: Boolean = false,
    @ColumnInfo(name = "auto_delete") val autoDelete: Boolean = false,
)

@Entity(
    tableName = "image_tags",
    primaryKeys = ["image_id", "tag_id"],
    indices = [Index("tag_id"), Index("image_id")],
    foreignKeys = [
        androidx.room.ForeignKey(
            entity = ImageEntity::class,
            parentColumns = ["id"],
            childColumns = ["image_id"],
            onDelete = androidx.room.ForeignKey.CASCADE,
        ),
        androidx.room.ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tag_id"],
            onDelete = androidx.room.ForeignKey.CASCADE,
        ),
    ]
)
data class ImageTagEntity(
    @ColumnInfo(name = "image_id") val imageId: Long,
    @ColumnInfo(name = "tag_id") val tagId: Long,
    @ColumnInfo(name = "confidence") val confidence: Float = 1.0f,
    @ColumnInfo(name = "source") val source: String,
)

/** 用户专属「保留」标签名：仅用户在审核页手动「保留」时添加（type=user），AI 不得自动添加。 */
const val TAG_USER_KEEP = "用户保留"
