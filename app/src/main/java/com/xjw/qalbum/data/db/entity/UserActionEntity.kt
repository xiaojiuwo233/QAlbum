package com.xjw.qalbum.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 用户操作日志：记录所有"删除 / 保留 / 收藏 / 加标签 / 恢复"等行为，用于撤销与统计 */
@Entity(
    tableName = "user_actions",
    indices = [Index("image_id"), Index("created_at")]
)
data class UserActionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "image_id") val imageId: Long,
    @ColumnInfo(name = "action") val action: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "extra") val extra: String? = null,
)
