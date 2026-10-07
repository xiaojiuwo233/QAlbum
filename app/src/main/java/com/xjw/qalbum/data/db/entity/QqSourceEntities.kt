package com.xjw.qalbum.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "qq_source_accounts")
data class QqSourceAccountEntity(
    @PrimaryKey @ColumnInfo(name = "account_key") val accountKey: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "imported_at") val importedAt: Long,
    @ColumnInfo(name = "picture_count") val pictureCount: Int,
)

// 一张缓存图片可来自多个消息、群聊、发送人或 QQ 账号。
@Entity(
    tableName = "qq_origins",
    foreignKeys = [ForeignKey(
        entity = QqSourceAccountEntity::class,
        parentColumns = ["account_key"], childColumns = ["account_key"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("account_key"), Index("conversation_key"), Index("sender_key")],
)
data class QqOriginEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "account_key") val accountKey: String,
    @ColumnInfo(name = "conversation_key") val conversationKey: String,
    @ColumnInfo(name = "conversation_name") val conversationName: String,
    @ColumnInfo(name = "chat_type") val chatType: Int,
    @ColumnInfo(name = "sender_key") val senderKey: String,
    @ColumnInfo(name = "sender_name") val senderName: String,
    @ColumnInfo(name = "member_name") val memberName: String,
    @ColumnInfo(name = "message_time") val messageTime: Long,
    val md5: String,
)

@Entity(
    tableName = "qq_origin_paths",
    primaryKeys = ["path", "origin_id"],
    foreignKeys = [ForeignKey(
        entity = QqOriginEntity::class,
        parentColumns = ["id"], childColumns = ["origin_id"], onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("origin_id")],
)
data class QqOriginPathEntity(
    val path: String,
    @ColumnInfo(name = "origin_id") val originId: String,
)

@Entity(
    tableName = "image_qq_origins",
    primaryKeys = ["image_id", "origin_id"],
    foreignKeys = [
        ForeignKey(entity = ImageEntity::class, parentColumns = ["id"], childColumns = ["image_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = QqOriginEntity::class, parentColumns = ["id"], childColumns = ["origin_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("origin_id")],
)
data class ImageQqOriginEntity(
    @ColumnInfo(name = "image_id") val imageId: Long,
    @ColumnInfo(name = "origin_id") val originId: String,
)

@Entity(tableName = "qq_source_facets", primaryKeys = ["kind", "key"],
    foreignKeys = [ForeignKey(entity = QqSourceAccountEntity::class,
        parentColumns = ["account_key"], childColumns = ["account_key"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("account_key")])
data class QqSourceFacetCacheEntity(
    // 0 = 会话（群聊 / 私聊，见 chat_type），1 = 发送人
    val kind: Int,
    val key: String,
    val name: String,
    @ColumnInfo(name = "search_text") val searchText: String,
    @ColumnInfo(name = "account_key") val accountKey: String,
    @ColumnInfo(name = "image_count") val imageCount: Int,
    // 会话：1 私聊 / 2 群聊 / 3 转发的聊天记录；发送人恒为 0
    @ColumnInfo(name = "chat_type", defaultValue = "0") val chatType: Int = 0,
    // 去重后图片的字节总和（一张图被同一分类多条消息引用只算一次）
    @ColumnInfo(name = "total_bytes", defaultValue = "0") val totalBytes: Long = 0,
    @ColumnInfo(name = "latest_time", defaultValue = "0") val latestTime: Long = 0,
)

@Entity(tableName = "qq_source_cache")
data class QqSourceCacheEntity(
    @PrimaryKey val id: Int = 1,
    val dirty: Boolean = true,
    @ColumnInfo(name = "linked_count") val linkedCount: Int = 0,
    @ColumnInfo(name = "linked_bytes", defaultValue = "0") val linkedBytes: Long = 0,
)
