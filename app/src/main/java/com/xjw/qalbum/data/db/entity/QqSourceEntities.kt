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
    val kind: Int,
    val key: String,
    val name: String,
    @ColumnInfo(name = "search_text") val searchText: String,
    @ColumnInfo(name = "account_key") val accountKey: String,
    @ColumnInfo(name = "image_count") val imageCount: Int,
)

@Entity(tableName = "qq_source_cache")
data class QqSourceCacheEntity(
    @PrimaryKey val id: Int = 1,
    val dirty: Boolean = true,
    @ColumnInfo(name = "linked_count") val linkedCount: Int = 0,
)
