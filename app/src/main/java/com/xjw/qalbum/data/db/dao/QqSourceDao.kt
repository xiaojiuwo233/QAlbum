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

data class QqSourceFacet(val key: String, val name: String, val count: Int, val accountName: String)
data class QqSourceCounts(val total: Int, val shared: Int, val protectedCount: Int, val removable: Int)
data class QqOriginDetails(
    val conversationName: String, val senderName: String, val memberName: String,
    val chatType: Int, val messageTime: Long, val messageCount: Int, val accountName: String,
)

@Dao
interface QqSourceDao {
    @Query("SELECT dirty FROM qq_source_cache WHERE id=1")
    fun observeCacheDirty(): Flow<Boolean?>

    @Query("INSERT OR IGNORE INTO qq_source_cache(id,dirty,linked_count) VALUES(1,1,0)")
    suspend fun ensureCache()

    @Query("SELECT dirty FROM qq_source_cache WHERE id=1")
    suspend fun cacheDirty(): Boolean?

    @Query("DELETE FROM qq_source_facets")
    suspend fun clearFacets()

    @Query("""INSERT INTO qq_source_facets(kind,`key`,name,search_text,account_key,image_count)
        SELECT 0,o.conversation_key,MAX(o.conversation_name),MAX(o.conversation_name),MAX(o.account_key),COUNT(DISTINCT i.id)
        FROM qq_origins o JOIN image_qq_origins l ON l.origin_id=o.id JOIN images i ON i.id=l.image_id
        WHERE o.chat_type=2 AND i.status='active' AND i.source_type<>'chatthumb' GROUP BY o.conversation_key""")
    suspend fun cacheGroups()

    @Query("""INSERT INTO qq_source_facets(kind,`key`,name,search_text,account_key,image_count)
        SELECT 1,o.sender_key,MAX(o.sender_name),
        COALESCE(GROUP_CONCAT(DISTINCT o.sender_name),'') || ' ' || COALESCE(GROUP_CONCAT(DISTINCT o.member_name),''),
        MAX(o.account_key),COUNT(DISTINCT i.id)
        FROM qq_origins o JOIN image_qq_origins l ON l.origin_id=o.id JOIN images i ON i.id=l.image_id
        WHERE i.status='active' AND i.source_type<>'chatthumb' GROUP BY o.sender_key""")
    suspend fun cacheSenders()

    @Query("""UPDATE qq_source_cache SET dirty=0,linked_count=(SELECT COUNT(DISTINCT l.image_id)
        FROM image_qq_origins l JOIN images i ON i.id=l.image_id WHERE i.status='active' AND i.source_type<>'chatthumb') WHERE id=1""")
    suspend fun finishCache()

    @Query("""SELECT f.`key`,f.name,f.image_count AS count,a.display_name AS accountName
        FROM qq_source_facets f JOIN qq_source_accounts a ON a.account_key=f.account_key
        WHERE f.kind=:kind AND instr(lower(f.search_text),lower(:search))>0 ORDER BY f.image_count DESC,f.name""")
    fun observeCachedFacets(kind: Int, search: String): Flow<List<QqSourceFacet>>
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

    @Query("""SELECT o.conversation_key AS `key`, MAX(o.conversation_name) AS name,
        COUNT(DISTINCT i.id) AS count, MAX(a.display_name) AS accountName
        FROM qq_origins o JOIN image_qq_origins link ON link.origin_id = o.id
        JOIN images i ON i.id = link.image_id JOIN qq_source_accounts a ON a.account_key = o.account_key
        WHERE o.chat_type = 2 AND i.status = 'active' AND i.source_type <> 'chatthumb'
        AND o.sender_key = :senderKey
        AND instr(lower(o.conversation_name), lower(:search)) > 0
        GROUP BY o.conversation_key ORDER BY count DESC, name""")
    fun observeGroups(senderKey: String, search: String): Flow<List<QqSourceFacet>>

    @Query("""SELECT o.sender_key AS `key`, MAX(o.sender_name) AS name,
        COUNT(DISTINCT i.id) AS count, MAX(a.display_name) AS accountName
        FROM qq_origins o JOIN image_qq_origins link ON link.origin_id = o.id
        JOIN images i ON i.id = link.image_id JOIN qq_source_accounts a ON a.account_key = o.account_key
        WHERE i.status = 'active' AND i.source_type <> 'chatthumb'
        AND o.conversation_key = :groupKey
        AND (instr(lower(o.sender_name), lower(:search)) > 0 OR instr(lower(o.member_name), lower(:search)) > 0)
        GROUP BY o.sender_key ORDER BY count DESC, name""")
    fun observeSenders(groupKey: String, search: String): Flow<List<QqSourceFacet>>

    @Query("SELECT i.* FROM images i WHERE " + ACTIVE_IMAGE + " AND " + MATCH_SCOPE + " ORDER BY i.mtime DESC, i.id DESC")
    fun images(groupKey: String?, senderKey: String?): PagingSource<Int, ImageEntity>

    @Query("""SELECT COUNT(*) AS total,
        COALESCE(SUM(CASE WHEN """ + SHARED_SCOPE + """ THEN 1 ELSE 0 END), 0) AS shared,
        COALESCE(SUM(CASE WHEN """ + PROTECTED_IMAGE + """ THEN 1 ELSE 0 END), 0) AS protectedCount,
        COALESCE(SUM(CASE WHEN NOT """ + PROTECTED_IMAGE + " AND (:includeShared OR NOT " + SHARED_SCOPE + """ ) THEN 1 ELSE 0 END), 0) AS removable
        FROM images i WHERE """ + ACTIVE_IMAGE + " AND " + MATCH_SCOPE)
    suspend fun counts(groupKey: String?, senderKey: String?, includeShared: Boolean): QqSourceCounts

    @Query("SELECT MAX(id) FROM images")
    suspend fun maxImageId(): Long?

    @Query("SELECT i.id FROM images i WHERE " + ACTIVE_IMAGE + " AND " + MATCH_SCOPE +
        " AND NOT " + PROTECTED_IMAGE + " AND (:includeShared OR NOT " + SHARED_SCOPE + ")" +
        " AND i.id > :afterId AND i.id <= :maxId ORDER BY i.id LIMIT :limit")
    suspend fun removableIds(groupKey: String?, senderKey: String?, includeShared: Boolean, afterId: Long, maxId: Long, limit: Int): List<Long>

    @Query("""SELECT MAX(o.conversation_name) AS conversationName, MAX(o.sender_name) AS senderName,
        MAX(o.member_name) AS memberName, o.chat_type AS chatType,
        MAX(o.message_time) AS messageTime, COUNT(*) AS messageCount, MAX(a.display_name) AS accountName
        FROM image_qq_origins link JOIN qq_origins o ON o.id = link.origin_id
        JOIN qq_source_accounts a ON a.account_key = o.account_key WHERE link.image_id = :imageId
        GROUP BY o.conversation_key, o.sender_key ORDER BY messageTime DESC""")
    fun observeImageOrigins(imageId: Long): Flow<List<QqOriginDetails>>

    @Query("SELECT linked_count FROM qq_source_cache WHERE id=1")
    fun observeLinkedCount(): Flow<Int?>

    @Query("DELETE FROM qq_source_accounts")
    suspend fun clear()
}
