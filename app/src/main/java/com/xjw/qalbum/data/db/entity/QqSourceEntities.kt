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
    /** 最近一次读取来源时见到这条消息的时间；早于最近一次完整读取（qq_source_cache.complete_read_at）说明消息已删 */
    @ColumnInfo(name = "seen_at", defaultValue = "0") val seenAt: Long = 0,
    /** 消息里记录的图片类型（1000 JPG、1001 PNG、2000 GIF 等），0 = 没读到 */
    @ColumnInfo(name = "pic_type", defaultValue = "0") val picType: Int = 0,
    /** 图片子类型：0 普通图片，1–5、7–15 为表情（QQ 按表情显示的各类）；没读到为 -1 */
    @ColumnInfo(name = "pic_sub_type", defaultValue = "-1") val picSubType: Int = -1,
    /** 以「原图」发送 */
    @ColumnInfo(name = "sent_original", defaultValue = "0") val sentOriginal: Boolean = false,
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

/**
 * 来源分类：会话（群聊 / 私聊 / 转发记录，见 [chatType]）或发送人。
 *
 * [id] 供 [QqImageContextEntity] 引用；名称、成员名片在导入后整体重建，图片数 / 占用空间由
 * images 上的触发器随状态变化增量维护（缓存未标脏时），删图不再触发全量重建。
 */
@Entity(
    tableName = "qq_source_facets",
    foreignKeys = [ForeignKey(
        entity = QqSourceAccountEntity::class,
        parentColumns = ["account_key"], childColumns = ["account_key"], onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["kind", "key"], unique = true), Index("account_key")],
)
data class QqSourceFacetCacheEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    // 0 = 会话，1 = 发送人
    val kind: Int,
    val key: String,
    val name: String,
    @ColumnInfo(name = "search_text") val searchText: String,
    @ColumnInfo(name = "account_key") val accountKey: String,
    // 会话：1 私聊 / 2 群聊 / 3 转发的聊天记录；发送人恒为 0
    @ColumnInfo(name = "chat_type") val chatType: Int = 0,
    // 图片（chatimg / chatraw 等，status = active）与 QQ 缩略图（chatthumb）分开统计，各自按图片去重
    @ColumnInfo(name = "image_count") val imageCount: Int = 0,
    @ColumnInfo(name = "image_bytes") val imageBytes: Long = 0,
    @ColumnInfo(name = "thumb_count") val thumbCount: Int = 0,
    @ColumnInfo(name = "thumb_bytes") val thumbBytes: Long = 0,
    @ColumnInfo(name = "latest_time") val latestTime: Long = 0,
)

/**
 * 图片与（会话, 发送人）的去重关联：image_qq_origins × qq_origins 的紧凑投影。
 * 一张热门图片可能被上千条消息引用，这里每个（会话, 发送人）只留一行；浏览、筛选与统计都走它。
 */
@Entity(
    tableName = "qq_image_contexts",
    primaryKeys = ["image_id", "conversation_id", "sender_id"],
    indices = [
        Index(value = ["conversation_id", "sender_id", "image_id"]),
        Index(value = ["sender_id", "conversation_id", "image_id"]),
    ],
)
data class QqImageContextEntity(
    @ColumnInfo(name = "image_id") val imageId: Long,
    @ColumnInfo(name = "conversation_id") val conversationId: Long,
    @ColumnInfo(name = "sender_id") val senderId: Long,
)

/** 成员在某个会话里的群名片（取自消息记录），成员筛选用 */
@Entity(tableName = "qq_source_members", primaryKeys = ["conversation_id", "sender_id"])
data class QqSourceMemberEntity(
    @ColumnInfo(name = "conversation_id") val conversationId: Long,
    @ColumnInfo(name = "sender_id") val senderId: Long,
    val card: String,
    @ColumnInfo(name = "latest_time") val latestTime: Long,
)

/**
 * 全部已关联图片的汇总；[dirty] 为真时触发器暂停增量维护，等待整体重建。
 * [completeReadAt] 是最近一次完整读取（所有账号、没有读不了的记录）的开始时间，0 = 还没有过。
 */
@Entity(tableName = "qq_source_cache")
data class QqSourceCacheEntity(
    @PrimaryKey val id: Int = 1,
    val dirty: Boolean = true,
    @ColumnInfo(name = "image_count") val imageCount: Int = 0,
    @ColumnInfo(name = "image_bytes") val imageBytes: Long = 0,
    @ColumnInfo(name = "thumb_count") val thumbCount: Int = 0,
    @ColumnInfo(name = "thumb_bytes") val thumbBytes: Long = 0,
    @ColumnInfo(name = "complete_read_at", defaultValue = "0") val completeReadAt: Long = 0,
)

/**
 * 自动归档名单：这个会话（可限定其中一个成员）或这个发送人以后的新图也自动归档。
 * 键为 '' 表示不限；清除来源索引不影响名单（重新读取后键不变）。[label] 是加入时的名称，列表里显示。
 */
@Entity(tableName = "qq_auto_archive", primaryKeys = ["conversation_key", "sender_key"])
data class QqAutoArchiveEntity(
    @ColumnInfo(name = "conversation_key") val conversationKey: String,
    @ColumnInfo(name = "sender_key") val senderKey: String,
    val label: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/**
 * 读取水位：每个账号的每张消息表读到的最大 rowid。下次读取只解析之后的新消息（增量），
 * [fullReadAt] 是这张表最近一次从头读完的时间，超过一周（或手动「完整读取」）再从头读一次，
 * 用来发现 QQ 里删掉的消息。清除来源索引时随账号级联删除。
 */
@Entity(
    tableName = "qq_read_marks",
    primaryKeys = ["account_key", "table_name"],
    foreignKeys = [ForeignKey(
        entity = QqSourceAccountEntity::class,
        parentColumns = ["account_key"], childColumns = ["account_key"], onDelete = ForeignKey.CASCADE,
    )],
)
data class QqReadMarkEntity(
    @ColumnInfo(name = "account_key") val accountKey: String,
    @ColumnInfo(name = "table_name") val tableName: String,
    @ColumnInfo(name = "max_rowid") val maxRowid: Long,
    @ColumnInfo(name = "full_read_at") val fullReadAt: Long,
)
