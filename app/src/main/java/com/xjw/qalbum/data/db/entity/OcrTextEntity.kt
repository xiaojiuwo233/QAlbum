package com.xjw.qalbum.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * OCR 识别结果，每张图最多一条记录。
 *
 * `has_*` 字段在 Phase 3 通过正则匹配预先抽取出来，避免每次搜索都全表扫文本。
 */
@Entity(
    tableName = "ocr_text",
    foreignKeys = [
        ForeignKey(
            entity = ImageEntity::class,
            parentColumns = ["id"],
            childColumns = ["image_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ]
)
data class OcrTextEntity(
    @PrimaryKey
    @ColumnInfo(name = "image_id") val imageId: Long,
    @ColumnInfo(name = "text") val text: String,
    @ColumnInfo(name = "confidence") val confidence: Float,
    @ColumnInfo(name = "has_phone") val hasPhone: Boolean = false,
    @ColumnInfo(name = "has_amount") val hasAmount: Boolean = false,
    @ColumnInfo(name = "has_address") val hasAddress: Boolean = false,
    @ColumnInfo(name = "has_id_number") val hasIdNumber: Boolean = false,
    @ColumnInfo(name = "has_order_no") val hasOrderNo: Boolean = false,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
