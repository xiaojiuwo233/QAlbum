package com.xjw.qalbum.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.data.db.entity.QqOriginEntity
import com.xjw.qalbum.data.db.entity.QqOriginPathEntity
import com.xjw.qalbum.data.db.entity.QqSourceAccountEntity
import kotlinx.coroutines.flow.Flow

// :groupKey 实为会话键（群聊与私聊共用 conversation_key），:senderKey 为发送人键；两者都为空表示全部已关联图片。
private const val MATCH_SCOPE = """i.id IN (
    SELECT link.image_id FROM qq_origins origin JOIN image_qq_origins link ON origin.id = link.origin_id
    WHERE :groupKey IS NOT NULL AND :senderKey IS NULL AND origin.conversation_key = :groupKey
    UNION
    SELECT link.image_id FROM qq_origins origin JOIN image_qq_origins link ON origin.id = link.origin_id
    WHERE :senderKey IS NOT NULL AND :groupKey IS NULL AND origin.sender_key = :senderKey
    UNION
    SELECT link.image_id FROM qq_origins origin JOIN image_qq_origins link ON origin.id = link.origin_id
    WHERE :senderKey IS NOT NULL AND :groupKey IS NOT NULL
      AND origin.conversation_key = :groupKey AND origin.sender_key = :senderKey
    UNION
    SELECT image_id FROM image_qq_origins WHERE :groupKey IS NULL AND :senderKey IS NULL
)"""
private const val SHARED_SCOPE = """EXISTS (
    SELECT 1 FROM image_qq_origins other_link JOIN qq_origins other ON other.id = other_link.origin_id
    WHERE other_link.image_id = i.id
    AND ((:groupKey IS NOT NULL AND other.conversation_key <> :groupKey)
      OR (:senderKey IS NOT NULL AND other.sender_key <> :senderKey))
)"""
private const val ACTIVE_IMAGE = "i.status = 'active' AND i.source_type <> 'chatthumb'"
private const val PROTECTED_IMAGE = "(i.is_protected = 1 OR COALESCE(i.user_keep, 0) = 1)"
private const val IN_SCOPE = ACTIVE_IMAGE + " AND " + MATCH_SCOPE
private const val REMOVABLE = "NOT " + PROTECTED_IMAGE + " AND (:includeShared OR NOT " + SHARED_SCOPE + ")"

/**
 * 来源分类（会话或发送人）。[bytes] 是去重后图片的字节总和；[alias] 为补充名称
 * （群名片之外的昵称）；[chatType] 会话为 1 私聊 / 2 群聊 / 3 转发记录，发送人为 0。
 */
data class QqSourceFacet(
    val key: String,
    val name: String,
    val alias: String,
    val count: Int,
    val bytes: Long,
    val chatType: Int,
    val latestTime: Long,
    val accountName: String,
)

data class QqSourceCounts(
    val total: Int,
    val totalBytes: Long,
    val shared: Int,
    val protectedCount: Int,
    val removable: Int,
    val removableBytes: Long,
)

data class QqScopeStats(val count: Int, val bytes: Long)
data class QqSourceSummary(val linkedCount: Int, val linkedBytes: Long, val dirty: Boolean)
data class QqFacetKindCount(val kind: Int, val chatType: Int, val count: Int)
data class QqSourceName(val name: String, val chatType: Int)
data class QqPicturePathEdge(val originId: String, val path: String)
data class QqPictureContext(val conversationKey: String, val senderKey: String)
data class QqOriginDetails(
    val conversationName: String, val senderName: String, val memberName: String,
    val chatType: Int, val messageTime: Long, val messageCount: Int, val accountName: String,
)

@Dao
interface QqSourceDao {
    /** 合并同一图片的重复消息引用，避免热门图片的历史引用把查询放大成数万行。 */
    @Query("""SELECT DISTINCT CASE WHEN length(o.md5)=32 THEN lower(o.md5) ELSE o.id END AS originId,p.path
        FROM qq_origin_paths p JOIN qq_origins o ON o.id=p.origin_id
        WHERE p.origin_id IN (SELECT origin_id FROM qq_origin_paths WHERE path IN (:paths))""")
    suspend fun picturePathEdges(paths: List<String>): List<QqPicturePathEdge>

    @Query("""SELECT DISTINCT conversation_key AS conversationKey,sender_key AS senderKey FROM qq_origins
        WHERE id IN (SELECT origin_id FROM qq_origin_paths WHERE path IN (:paths))""")
    suspend fun pictureContexts(paths: List<String>): List<QqPictureContext>
    // ---- 分类缓存（聚合较重，标脏后由仓库在空闲时重建） ----

    @Query("SELECT dirty FROM qq_source_cache WHERE id=1")
    fun observeCacheDirty(): Flow<Boolean?>

    @Query("INSERT OR IGNORE INTO qq_source_cache(id,dirty,linked_count,linked_bytes) VALUES(1,1,0,0)")
    suspend fun ensureCache()

    @Query("SELECT dirty FROM qq_source_cache WHERE id=1")
    suspend fun cacheDirty(): Boolean?

    @Query("DELETE FROM qq_source_facets")
    suspend fun clearFacets()

    /** 会话分类：同一张图在同一会话被多条消息引用只计一次（先按 会话×图片 去重再汇总） */
    @Query("""INSERT INTO qq_source_facets(kind,`key`,name,search_text,account_key,image_count,chat_type,total_bytes,latest_time)
        SELECT 0, x.conversation_key, MAX(x.name), MAX(x.name), MAX(x.account_key), COUNT(*), MAX(x.chat_type), SUM(x.size), MAX(x.latest)
        FROM (
            SELECT o.conversation_key, i.id, MAX(i.size) AS size, MAX(o.conversation_name) AS name,
                MAX(o.account_key) AS account_key, MAX(o.chat_type) AS chat_type, MAX(o.message_time) AS latest
            FROM qq_origins o JOIN image_qq_origins l ON l.origin_id = o.id JOIN images i ON i.id = l.image_id
            WHERE i.status = 'active' AND i.source_type <> 'chatthumb'
            GROUP BY o.conversation_key, i.id
        ) x GROUP BY x.conversation_key""")
    suspend fun cacheConversations()

    /** 发送人分类：搜索文本包含历史昵称与群名片 */
    @Query("""INSERT INTO qq_source_facets(kind,`key`,name,search_text,account_key,image_count,chat_type,total_bytes,latest_time)
        SELECT 1, n.sender_key, n.name, n.search_text, n.account_key, s.image_count, 0, s.total_bytes, n.latest
        FROM (
            SELECT o.sender_key, MAX(o.sender_name) AS name,
                COALESCE(GROUP_CONCAT(DISTINCT o.sender_name), '') || ' ' || COALESCE(GROUP_CONCAT(DISTINCT o.member_name), '') AS search_text,
                MAX(o.account_key) AS account_key, MAX(o.message_time) AS latest
            FROM qq_origins o JOIN image_qq_origins l ON l.origin_id = o.id JOIN images i ON i.id = l.image_id
            WHERE i.status = 'active' AND i.source_type <> 'chatthumb'
            GROUP BY o.sender_key
        ) n JOIN (
            SELECT y.sender_key, COUNT(*) AS image_count, SUM(y.size) AS total_bytes
            FROM (
                SELECT DISTINCT o.sender_key, i.id, i.size
                FROM qq_origins o JOIN image_qq_origins l ON l.origin_id = o.id JOIN images i ON i.id = l.image_id
                WHERE i.status = 'active' AND i.source_type <> 'chatthumb'
            ) y GROUP BY y.sender_key
        ) s ON s.sender_key = n.sender_key""")
    suspend fun cacheSenders()

    @Query("""UPDATE qq_source_cache SET dirty=0,
        linked_count=(SELECT COUNT(*) FROM images i WHERE i.status='active' AND i.source_type<>'chatthumb'
            AND i.id IN (SELECT image_id FROM image_qq_origins)),
        linked_bytes=(SELECT COALESCE(SUM(i.size),0) FROM images i WHERE i.status='active' AND i.source_type<>'chatthumb'
            AND i.id IN (SELECT image_id FROM image_qq_origins))
        WHERE id=1""")
    suspend fun finishCache()

    @Query("SELECT linked_count AS linkedCount, linked_bytes AS linkedBytes, dirty FROM qq_source_cache WHERE id=1")
    fun observeSummary(): Flow<QqSourceSummary?>

    /**
     * 缓存的分类列表。[chatType] < 0 不限会话类型；[order]：0 占用空间、1 图片数、2 最近、3 名称。
     */
    @Query("""SELECT f.`key` AS `key`, f.name AS name, '' AS alias, f.image_count AS count, f.total_bytes AS bytes,
        f.chat_type AS chatType, f.latest_time AS latestTime,
        CASE WHEN f.chat_type=3 THEN '全部账号' ELSE a.display_name END AS accountName
        FROM qq_source_facets f JOIN qq_source_accounts a ON a.account_key = f.account_key
        WHERE f.kind = :kind AND (:chatType < 0 OR f.chat_type = :chatType OR (:chatType=2 AND f.chat_type=3))
          AND instr(lower(f.search_text), lower(:search)) > 0
        ORDER BY CASE WHEN f.chat_type=3 THEN 0 ELSE 1 END,
            CASE WHEN :order = 3 THEN f.name END ASC,
            CASE :order WHEN 0 THEN f.total_bytes WHEN 1 THEN f.image_count ELSE f.latest_time END DESC,
            f.name""")
    fun observeFacets(kind: Int, chatType: Int, search: String, order: Int): Flow<List<QqSourceFacet>>

    @Query("SELECT kind, chat_type AS chatType, COUNT(*) AS count FROM qq_source_facets GROUP BY kind, chat_type")
    fun observeFacetKindCounts(): Flow<List<QqFacetKindCount>>

    // ---- 浏览页的二级筛选（实时聚合，规模限于单个会话 / 发送人） ----

    /** 会话内的成员：群里优先显示群名片，昵称作为补充名称 */
    @Query("""SELECT x.sender_key AS `key`,
            CASE WHEN MAX(x.card) <> '' THEN MAX(x.card) ELSE MAX(x.nick) END AS name,
            CASE WHEN MAX(x.card) <> '' AND MAX(x.nick) <> '' AND MAX(x.card) <> MAX(x.nick) THEN MAX(x.nick) ELSE '' END AS alias,
            COUNT(*) AS count, SUM(x.size) AS bytes, 0 AS chatType, MAX(x.latest) AS latestTime, MAX(x.account) AS accountName
        FROM (
            SELECT o.sender_key, i.id, MAX(i.size) AS size, MAX(o.member_name) AS card, MAX(o.sender_name) AS nick,
                MAX(o.message_time) AS latest, MAX(a.display_name) AS account
            FROM qq_origins o JOIN image_qq_origins l ON l.origin_id = o.id JOIN images i ON i.id = l.image_id
            JOIN qq_source_accounts a ON a.account_key = o.account_key
            WHERE o.conversation_key = :conversationKey AND i.status = 'active' AND i.source_type <> 'chatthumb'
            GROUP BY o.sender_key, i.id
        ) x
        GROUP BY x.sender_key
        HAVING instr(lower(MAX(x.card)), lower(:search)) > 0 OR instr(lower(MAX(x.nick)), lower(:search)) > 0
        ORDER BY bytes DESC, count DESC""")
    fun observeConversationMembers(conversationKey: String, search: String): Flow<List<QqSourceFacet>>

    /** 发送人出现过的会话 */
    @Query("""SELECT x.conversation_key AS `key`, MAX(x.name) AS name, '' AS alias, COUNT(*) AS count, SUM(x.size) AS bytes,
            MAX(x.chat_type) AS chatType, MAX(x.latest) AS latestTime, MAX(x.account) AS accountName
        FROM (
            SELECT o.conversation_key, i.id, MAX(i.size) AS size, MAX(o.conversation_name) AS name, MAX(o.chat_type) AS chat_type,
                MAX(o.message_time) AS latest, MAX(a.display_name) AS account
            FROM qq_origins o JOIN image_qq_origins l ON l.origin_id = o.id JOIN images i ON i.id = l.image_id
            JOIN qq_source_accounts a ON a.account_key = o.account_key
            WHERE o.sender_key = :senderKey AND i.status = 'active' AND i.source_type <> 'chatthumb'
            GROUP BY o.conversation_key, i.id
        ) x
        GROUP BY x.conversation_key
        HAVING instr(lower(MAX(x.name)), lower(:search)) > 0
        ORDER BY bytes DESC, count DESC""")
    fun observeSenderConversations(senderKey: String, search: String): Flow<List<QqSourceFacet>>

    @Query("SELECT COALESCE(MAX(conversation_name), '') AS name, COALESCE(MAX(chat_type), 0) AS chatType FROM qq_origins WHERE conversation_key = :key")
    fun observeConversationName(key: String): Flow<QqSourceName?>

    @Query("SELECT COALESCE(MAX(sender_name), '') AS name, 0 AS chatType FROM qq_origins WHERE sender_key = :key")
    fun observeSenderName(key: String): Flow<QqSourceName?>

    // ---- 范围内的图片 ----

    @Query("SELECT i.* FROM images i WHERE " + IN_SCOPE + " ORDER BY i.mtime DESC, i.id DESC")
    fun imagesNewest(groupKey: String?, senderKey: String?): PagingSource<Int, ImageEntity>

    @Query("SELECT i.* FROM images i WHERE " + IN_SCOPE + " ORDER BY i.mtime ASC, i.id ASC")
    fun imagesOldest(groupKey: String?, senderKey: String?): PagingSource<Int, ImageEntity>

    @Query("SELECT i.* FROM images i WHERE " + IN_SCOPE + " ORDER BY i.size DESC, i.id DESC")
    fun imagesLargest(groupKey: String?, senderKey: String?): PagingSource<Int, ImageEntity>

    @Query("SELECT i.* FROM images i WHERE " + IN_SCOPE + " ORDER BY i.size ASC, i.id ASC")
    fun imagesSmallest(groupKey: String?, senderKey: String?): PagingSource<Int, ImageEntity>

    /** 日期分组表头计数（与图库同口径的 date_bucket） */
    @Query("SELECT i.date_bucket AS bucketKey, COUNT(*) AS count FROM images i WHERE " + IN_SCOPE + " GROUP BY i.date_bucket")
    fun observeDateBuckets(groupKey: String?, senderKey: String?): Flow<List<ImageDao.BucketCountRow>>

    /** 大小分组表头计数（分桶与图库 sizeBucketId 一致） */
    @Query("""SELECT CASE
            WHEN i.size >= 10485760 THEN 100000
            WHEN i.size >= 1048576 THEN 10000 + (i.size / 1048576)
            WHEN i.size >= 102400 THEN 1000 + (i.size / 102400)
            WHEN i.size >= 10240 THEN 100 + (i.size / 10240)
            ELSE 0 END AS bucketKey, COUNT(*) AS count
        FROM images i WHERE """ + IN_SCOPE + " GROUP BY bucketKey")
    fun observeSizeBuckets(groupKey: String?, senderKey: String?): Flow<List<ImageDao.BucketCountRow>>

    @Query("SELECT COUNT(*) AS count, COALESCE(SUM(i.size), 0) AS bytes FROM images i WHERE " + IN_SCOPE)
    fun observeScopeStats(groupKey: String?, senderKey: String?): Flow<QqScopeStats>

    /** 「全选」：当前范围内全部图片 id */
    @Query("SELECT i.id FROM images i WHERE " + IN_SCOPE)
    suspend fun scopeIds(groupKey: String?, senderKey: String?): List<Long>

    /** 按范围清理前的统计：共用（也出现在范围外）与受保护（收藏 / 保留）的图片分别计数 */
    @Query("""SELECT COUNT(*) AS total, COALESCE(SUM(i.size), 0) AS totalBytes,
        COALESCE(SUM(CASE WHEN """ + SHARED_SCOPE + """ THEN 1 ELSE 0 END), 0) AS shared,
        COALESCE(SUM(CASE WHEN """ + PROTECTED_IMAGE + """ THEN 1 ELSE 0 END), 0) AS protectedCount,
        COALESCE(SUM(CASE WHEN """ + REMOVABLE + """ THEN 1 ELSE 0 END), 0) AS removable,
        COALESCE(SUM(CASE WHEN """ + REMOVABLE + """ THEN i.size ELSE 0 END), 0) AS removableBytes
        FROM images i WHERE """ + IN_SCOPE)
    suspend fun counts(groupKey: String?, senderKey: String?, includeShared: Boolean): QqSourceCounts

    @Query("SELECT MAX(id) FROM images")
    suspend fun maxImageId(): Long?

    @Query("SELECT i.id FROM images i WHERE " + IN_SCOPE + " AND " + REMOVABLE +
        " AND i.id > :afterId AND i.id <= :maxId ORDER BY i.id LIMIT :limit")
    suspend fun removableIds(groupKey: String?, senderKey: String?, includeShared: Boolean, afterId: Long, maxId: Long, limit: Int): List<Long>

    // ---- 导入 ----

    @Upsert
    suspend fun putAccount(account: QqSourceAccountEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrigins(origins: List<QqOriginEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPaths(paths: List<QqOriginPathEntity>)

    @Query("""UPDATE qq_origins SET conversation_name = :name
        WHERE account_key = :accountKey AND conversation_key = :key AND :name <> ''""")
    suspend fun updateConversationName(accountKey: String, key: String, name: String)

    @Query("""UPDATE qq_origins SET sender_name = :name
        WHERE account_key = :accountKey AND sender_key = :key AND :name <> ''""")
    suspend fun updateSenderName(accountKey: String, key: String, name: String)

    @Query("""INSERT OR IGNORE INTO image_qq_origins(image_id, origin_id)
        SELECT i.id, p.origin_id FROM images i JOIN qq_origin_paths p ON p.path = i.path
        WHERE i.status NOT IN ('deleted', 'quarantined')""")
    suspend fun reconcileLinks()

    @Query("""INSERT OR IGNORE INTO qq_origin_paths(path, origin_id)
        SELECT :path, origin_id FROM qq_origin_paths WHERE path = :referencePath""")
    suspend fun registerDerivedPath(path: String, referencePath: String)

    @Query("SELECT * FROM qq_source_accounts ORDER BY display_name, account_key")
    fun observeAccounts(): Flow<List<QqSourceAccountEntity>>

    @Query("""SELECT MAX(o.conversation_name) AS conversationName, MAX(o.sender_name) AS senderName,
        MAX(o.member_name) AS memberName, o.chat_type AS chatType,
        MAX(o.message_time) AS messageTime, COUNT(*) AS messageCount, MAX(a.display_name) AS accountName
        FROM image_qq_origins link JOIN qq_origins o ON o.id = link.origin_id
        JOIN qq_source_accounts a ON a.account_key = o.account_key WHERE link.image_id = :imageId
        GROUP BY o.conversation_key, o.sender_key ORDER BY messageTime DESC""")
    fun observeImageOrigins(imageId: Long): Flow<List<QqOriginDetails>>

    @Query("DELETE FROM qq_source_accounts")
    suspend fun clear()
}
