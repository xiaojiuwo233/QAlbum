package com.xjw.qalbum.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * QQNT 数据库消息匹配结果（Phase 5 用）：将一张图关联到具体的聊天来源。
 */
@Entity(
    tableName = "matched_messages",
    indices = [Index("image_id"), Index("peer_uid"), Index("msg_time")]
)
data class MatchedMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "image_id") val imageId: Long,
    @ColumnInfo(name = "msg_id") val msgId: String,
    @ColumnInfo(name = "peer_uid") val peerUid: String,
    @ColumnInfo(name = "chat_type") val chatType: Int,
    @ColumnInfo(name = "sender_uid") val senderUid: String?,
    @ColumnInfo(name = "sender_name") val senderName: String?,
    @ColumnInfo(name = "group_name") val groupName: String?,
    @ColumnInfo(name = "msg_time") val msgTime: Long,
    @ColumnInfo(name = "confidence") val confidence: String,
    @ColumnInfo(name = "match_reason") val matchReason: String,
)
