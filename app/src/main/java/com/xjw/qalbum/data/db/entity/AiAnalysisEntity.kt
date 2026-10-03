package com.xjw.qalbum.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(
    tableName = "ai_analysis",
    foreignKeys = [ForeignKey(
        entity = ImageEntity::class,
        parentColumns = ["id"],
        childColumns = ["image_id"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class AiAnalysisEntity(
    @PrimaryKey @ColumnInfo(name = "image_id") val imageId: Long,
    val description: String,
    @ColumnInfo(name = "info_type") val infoType: String,
    val model: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
