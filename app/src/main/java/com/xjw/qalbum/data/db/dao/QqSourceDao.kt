package com.xjw.qalbum.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.xjw.qalbum.data.db.SizeBucketSql
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.data.db.entity.QqAutoArchiveEntity
import com.xjw.qalbum.data.db.entity.QqImageContextEntity
import com.xjw.qalbum.data.db.entity.QqOriginEntity
import com.xjw.qalbum.data.db.entity.QqOriginPathEntity
import com.xjw.qalbum.data.db.entity.QqPreviewCheckEntity
import com.xjw.qalbum.data.db.entity.QqReadMarkEntity
import com.xjw.qalbum.data.db.entity.QqSourceAccountEntity
import com.xjw.qalbum.domain.model.TimelineSection
import kotlinx.coroutines.flow.Flow

/**
 * 来源分类（会话或发送人）及其统计。[count] / [bytes] 是图片，[thumbCount] / [thumbBytes] 是 QQ 缩略图，
 * 都按图片去重；[alias] 为补充名称（群名片之外的昵称）；[chatType] 会话为 1 私聊 / 2 群聊 / 3 转发记录，发送人为 0。
 */
data class QqSourceFacet(
    val id: Long,
    val key: String,
    val name: String,
    val alias: String,
    val count: Int,
    val bytes: Long,
    val thumbCount: Int,
    val thumbBytes: Long,
    val chatType: Int,
    val latestTime: Long,
    val accountName: String,
) {
    val totalBytes: Long get() = bytes + thumbBytes
    val totalCount: Int get() = count + thumbCount
}

data class QqSourceCounts(
    val total: Int,
    val totalBytes: Long,
    val shared: Int,
    val protectedCount: Int,
    val removable: Int,
    val removableBytes: Long,
)

/** 按范围归档前的统计：还没归档的图片与 QQ 缩略图，其中也出现在范围之外（其他聊天也在用）的张数 */
data class QqArchiveCounts(
    val images: Int,
    val imageBytes: Long,
    val thumbs: Int,
    val thumbBytes: Long,
    val shared: Int,
)

data class QqScopeStats(val count: Int, val bytes: Long)

/** 全部已关联图片的汇总；[dirty] 为真表示正在等待 / 进行整体重建 */
data class QqSourceSummary(
    val imageCount: Int,
    val imageBytes: Long,
    val thumbCount: Int,
    val thumbBytes: Long,
    val dirty: Boolean,
)

data class QqFacetKindCount(val kind: Int, val chatType: Int, val count: Int)
data class QqSeenSummary(val total: Int, val stale: Int)
data class QqImageMd5(val imageId: Long, val md5: String)
data class QqPreviewFamilyMove(val id: Long, val fromGroup: Long?, val toGroup: Long)

/** 还没核对过的 chatimg 与同一时刻、由自己发出的 chatraw（见 QqSentPreviewLinker） */
data class QqPreviewCandidate(
    val previewId: Long,
    val previewPath: String,
    val previewSize: Long = 0,
    val previewWidth: Int,
    val previewHeight: Int,
    val previewTime: Long,
    val rawPath: String,
    val rawWidth: Int,
    val rawHeight: Int,
    val rawTime: Long,
)
data class QqPicturePathEdge(val originId: String, val path: String)
data class QqPictureContext(val conversationKey: String, val senderKey: String)
data class QqPathContext(val path: String, val conversationKey: String, val senderKey: String)
data class QqOriginDetails(
    val conversationName: String, val senderName: String, val memberName: String,
    val chatType: Int, val messageTime: Long, val messageCount: Int, val accountName: String,
    /** 有消息是以「原图」发送的 */
    val sentOriginal: Boolean = false,
)

/** 浏览页内容：图片（chatimg / chatraw 等）或 QQ 缩略图（chatthumb） */
enum class QqContent { IMAGES, THUMBNAILS }

/** 浏览范围（分类 id）：两者都为空表示全部已关联图片 */
data class QqScopeIds(val conversationId: Long? = null, val senderId: Long? = null)

private const val FACET_COLUMNS = """f.id AS id, f.`key` AS `key`, f.name AS name, '' AS alias,
    f.image_count AS count, f.image_bytes AS bytes, f.thumb_count AS thumbCount, f.thumb_bytes AS thumbBytes,
    f.chat_type AS chatType, f.latest_time AS latestTime,
    CASE WHEN f.chat_type = 3 THEN '全部账号' ELSE COALESCE(a.display_name, '') END AS accountName"""

// 关联表在前、images 按主键查（CROSS JOIN 固定连接顺序）。否则规划器可能拿 status 索引做外层循环，
// 让每条关联都扫一遍全部活跃图片——这正是旧版「按成员筛选」加载十几分钟的原因。
private const val CONTEXT_SUMS = """
    SUM(+i.status = 'active' AND +i.source_type <> 'chatthumb') AS count,
    SUM(CASE WHEN +i.status = 'active' AND +i.source_type <> 'chatthumb' THEN i.size ELSE 0 END) AS bytes,
    SUM(+i.source_type = 'chatthumb' AND +i.status = 'thumbnail_trash') AS thumbCount,
    SUM(CASE WHEN +i.source_type = 'chatthumb' AND +i.status = 'thumbnail_trash' THEN i.size ELSE 0 END) AS thumbBytes"""

@Dao
interface QqSourceDao {
    /** 合并同一图片的重复消息引用，避免热门图片的历史引用把查询放大成数万行。 */
    @Query("""SELECT DISTINCT CASE WHEN length(o.md5)=32 THEN lower(o.md5) ELSE o.id END AS originId,p.path
        FROM qq_origin_paths p JOIN qq_origins o ON o.id=p.origin_id
        WHERE p.origin_id IN (SELECT origin_id FROM qq_origin_paths WHERE path IN (:paths))""")
    suspend fun picturePathEdges(paths: List<String>): List<QqPicturePathEdge>

    /** 每个路径出现过的会话 / 发送人（删除规划一次查整批，按组合并） */
    @Query("""SELECT DISTINCT p.path AS path,o.conversation_key AS conversationKey,o.sender_key AS senderKey
        FROM qq_origin_paths p JOIN qq_origins o ON o.id = p.origin_id WHERE p.path IN (:paths)""")
    suspend fun pathContexts(paths: List<String>): List<QqPathContext>

    /** [paths] 中出现在不止一个会话 / 发送人组合里的（与删除时「共用」的判断一致） */
    @Query("""SELECT p.path FROM qq_origin_paths p JOIN qq_origins o ON o.id = p.origin_id
        WHERE p.path IN (:paths)
        GROUP BY p.path HAVING COUNT(DISTINCT o.conversation_key || char(31) || o.sender_key) > 1""")
    suspend fun pathsInSeveralContexts(paths: List<String>): List<String>

    /** [paths] 中也出现在范围之外的：会话不是 [groupKey]，或发送人不是 [senderKey]（为 null 的不限） */
    @Query("""SELECT DISTINCT p.path FROM qq_origin_paths p JOIN qq_origins o ON o.id = p.origin_id
        WHERE p.path IN (:paths)
          AND ((:groupKey IS NOT NULL AND o.conversation_key <> :groupKey) OR (:senderKey IS NOT NULL AND o.sender_key <> :senderKey))""")
    suspend fun pathsOutsideScope(paths: List<String>, groupKey: String?, senderKey: String?): List<String>

    // ---- 统计缓存（增量维护；标脏后由仓库分段重建，见 QqSourceStatsSql） ----

    @Query("SELECT dirty FROM qq_source_cache WHERE id = 1")
    fun observeCacheDirty(): Flow<Boolean?>

    @Query("UPDATE qq_source_cache SET dirty = 1 WHERE id = 1")
    suspend fun markDirty()

    @Query("""SELECT image_count AS imageCount, image_bytes AS imageBytes, thumb_count AS thumbCount,
        thumb_bytes AS thumbBytes, dirty FROM qq_source_cache WHERE id = 1""")
    fun observeSummary(): Flow<QqSourceSummary?>

    /**
     * 分类列表。[chatType] < 0 不限会话类型；[order]：0 占用空间、1 数量、2 最近、3 名称。
     * 图片与缩略图都已清空的分类不再列出。
     */
    @Query("""SELECT $FACET_COLUMNS
        FROM qq_source_facets f LEFT JOIN qq_source_accounts a ON a.account_key = f.account_key
        WHERE f.kind = :kind AND (:chatType < 0 OR f.chat_type = :chatType OR (:chatType = 2 AND f.chat_type = 3))
          AND f.image_count + f.thumb_count > 0
          AND instr(lower(f.search_text), lower(:search)) > 0
        ORDER BY CASE WHEN f.chat_type = 3 THEN 0 ELSE 1 END,
            CASE WHEN :order = 3 THEN f.name END ASC,
            CASE :order WHEN 0 THEN f.image_bytes + f.thumb_bytes WHEN 1 THEN f.image_count + f.thumb_count ELSE f.latest_time END DESC,
            f.name""")
    fun observeFacets(kind: Int, chatType: Int, search: String, order: Int): Flow<List<QqSourceFacet>>

    @Query("""SELECT kind, chat_type AS chatType, COUNT(*) AS count FROM qq_source_facets
        WHERE image_count + thumb_count > 0 GROUP BY kind, chat_type""")
    fun observeFacetKindCounts(): Flow<List<QqFacetKindCount>>

    /** 浏览页标题与总量：按键查分类（统计直接取缓存值） */
    @Query("""SELECT $FACET_COLUMNS
        FROM qq_source_facets f LEFT JOIN qq_source_accounts a ON a.account_key = f.account_key
        WHERE f.kind = :kind AND f.`key` = :key""")
    fun observeFacet(kind: Int, key: String): Flow<QqSourceFacet?>

    @Query("SELECT id FROM qq_source_facets WHERE kind = :kind AND `key` = :key")
    suspend fun facetId(kind: Int, key: String): Long?

    // ---- 浏览页的二级筛选（按会话 / 发送人在关联表上的索引范围聚合） ----

    /** 会话里的成员：群里优先显示群名片，昵称作为补充名称 */
    @Query("""SELECT * FROM (
        SELECT f.id AS id, f.`key` AS `key`,
            CASE WHEN COALESCE(m.card, '') <> '' THEN m.card ELSE f.name END AS name,
            CASE WHEN COALESCE(m.card, '') <> '' AND f.name <> '' AND m.card <> f.name THEN f.name ELSE '' END AS alias,
            $CONTEXT_SUMS,
            0 AS chatType, COALESCE(m.latest_time, f.latest_time) AS latestTime, '' AS accountName
        FROM qq_image_contexts c
        CROSS JOIN images i ON i.id = c.image_id
        CROSS JOIN qq_source_facets f ON f.id = c.sender_id
        LEFT JOIN qq_source_members m ON m.conversation_id = c.conversation_id AND m.sender_id = c.sender_id
        WHERE c.conversation_id = :conversationId
        GROUP BY c.sender_id
    ) WHERE count + thumbCount > 0 ORDER BY bytes + thumbBytes DESC, count + thumbCount DESC""")
    fun observeConversationMembers(conversationId: Long): Flow<List<QqSourceFacet>>

    /** 发送人出现过的会话 */
    @Query("""SELECT * FROM (
        SELECT f.id AS id, f.`key` AS `key`, f.name AS name, '' AS alias,
            $CONTEXT_SUMS,
            f.chat_type AS chatType, f.latest_time AS latestTime,
            CASE WHEN f.chat_type = 3 THEN '全部账号' ELSE COALESCE(a.display_name, '') END AS accountName
        FROM qq_image_contexts c
        CROSS JOIN images i ON i.id = c.image_id
        CROSS JOIN qq_source_facets f ON f.id = c.conversation_id
        LEFT JOIN qq_source_accounts a ON a.account_key = f.account_key
        WHERE c.sender_id = :senderId
        GROUP BY c.conversation_id
    ) WHERE count + thumbCount > 0 ORDER BY bytes + thumbBytes DESC, count + thumbCount DESC""")
    fun observeSenderConversations(senderId: Long): Flow<List<QqSourceFacet>>

    // ---- 范围内的图片（SQL 由 QqScopeSql 按范围 / 内容 / 排序拼出） ----

    @RawQuery(observedEntities = [ImageEntity::class, QqImageContextEntity::class])
    fun pagingScope(query: SupportSQLiteQuery): PagingSource<Int, ImageEntity>

    @RawQuery(observedEntities = [ImageEntity::class, QqImageContextEntity::class])
    fun observeScopeStats(query: SupportSQLiteQuery): Flow<QqScopeStats>

    @RawQuery(observedEntities = [ImageEntity::class, QqImageContextEntity::class])
    fun observeBuckets(query: SupportSQLiteQuery): Flow<List<ImageDao.BucketCountRow>>

    @RawQuery
    suspend fun queryIds(query: SupportSQLiteQuery): List<Long>

    @RawQuery
    suspend fun queryCounts(query: SupportSQLiteQuery): QqSourceCounts

    @RawQuery
    suspend fun queryArchiveCounts(query: SupportSQLiteQuery): QqArchiveCounts

    @Query("SELECT MAX(id) FROM images")
    suspend fun maxImageId(): Long?

    // ---- 导入 ----

    @Upsert
    suspend fun putAccount(account: QqSourceAccountEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrigins(origins: List<QqOriginEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPaths(paths: List<QqOriginPathEntity>)

    /** 这次读取又见到了这些消息：记下读取时间，顺带补上 v23 以前没有的图片属性 */
    @Query("""UPDATE qq_origins SET seen_at = :seenAt, pic_type = :picType, pic_sub_type = :picSubType,
        sent_original = :original WHERE id IN (:ids)""")
    suspend fun markSeen(ids: List<String>, seenAt: Long, picType: Int, picSubType: Int, original: Boolean)

    /** 完整读取（所有账号、没有读不了的记录）成功后记下这次读取的开始时间 */
    @Query("UPDATE qq_source_cache SET complete_read_at = :at WHERE id = 1")
    suspend fun setCompleteReadAt(at: Long)

    @Query("SELECT COALESCE((SELECT complete_read_at FROM qq_source_cache WHERE id = 1), 0)")
    fun observeCompleteReadAt(): Flow<Long>

    /** 诊断：来源总数与这次读取没再见到的（完整读取后即消息已删） */
    @Query("SELECT COUNT(*) AS total, COALESCE(SUM(seen_at < :readStart), 0) AS stale FROM qq_origins")
    suspend fun seenSummary(readStart: Long): QqSeenSummary

    @Query("""UPDATE qq_origins SET conversation_name = :name
        WHERE account_key = :accountKey AND conversation_key = :key AND :name <> ''""")
    suspend fun updateConversationName(accountKey: String, key: String, name: String)

    @Query("""UPDATE qq_origins SET sender_name = :name
        WHERE account_key = :accountKey AND sender_key = :key AND :name <> ''""")
    suspend fun updateSenderName(accountKey: String, key: String, name: String)

    /**
     * 仅给实际已入库、且符合 QQ 家族命名的别名版本（`Cache_xxx.jpg` / `.png` / `.gif` 等，见 QqCachePaths.ALIAS_EXTENSIONS）
     * 补来源：沿用不带后缀的同名文件的来源，避免为每条消息预建额外路径。
     */
    @Query("""INSERT OR IGNORE INTO qq_origin_paths(path, origin_id)
        SELECT a.path, p.origin_id FROM (
            SELECT COALESCE(q.original_path, i.path) AS path FROM images i
            LEFT JOIN quarantine q ON q.image_id = i.id
            WHERE i.variant_group IS NOT NULL AND i.status <> 'deleted'
              AND (lower(substr(COALESCE(q.original_path, i.path), -4)) IN ('.jpg', '.png', '.gif', '.bmp')
                OR lower(substr(COALESCE(q.original_path, i.path), -5)) IN ('.jpeg', '.webp', '.heic'))
        ) a
        JOIN qq_origin_paths p ON p.path = substr(a.path, 1, length(a.path) - CASE WHEN substr(a.path, -5, 1) = '.' THEN 5 ELSE 4 END)""")
    suspend fun registerIndexedAliasPaths()

    @Query("""INSERT OR IGNORE INTO image_qq_origins(image_id, origin_id)
        SELECT i.id, p.origin_id FROM images i JOIN qq_origin_paths p ON p.path = i.path
        WHERE i.status NOT IN ('deleted', 'quarantined')""")
    suspend fun linkOriginPaths()

    @Query("""INSERT OR IGNORE INTO image_qq_origins(image_id, origin_id)
        SELECT i.id, p.origin_id FROM images i JOIN qq_origin_paths p ON p.path = i.path WHERE i.id IN (:ids)""")
    suspend fun linkImages(ids: List<Long>)

    @Transaction
    suspend fun reconcileLinks() {
        registerIndexedAliasPaths()
        linkOriginPaths()
    }

    @Query("""INSERT OR IGNORE INTO qq_origin_paths(path, origin_id)
        SELECT :path, origin_id FROM qq_origin_paths WHERE path = :referencePath""")
    suspend fun registerDerivedPath(path: String, referencePath: String)

    /**
     * 发送图片缓存的候选：还没核对过（或文件变了）的 chatimg，配上修改时间在它之前 [before] 毫秒到之后 [after] 毫秒内、
     * 关联消息里有 [senders]（各账号自己）发出的 chatraw。
     */
    @Query("""SELECT c.id AS previewId, c.path AS previewPath, c.size AS previewSize, c.width AS previewWidth,
        c.height AS previewHeight, c.mtime AS previewTime, r.path AS rawPath, r.width AS rawWidth, r.height AS rawHeight, r.mtime AS rawTime
        FROM images c JOIN images r ON r.mtime BETWEEN c.mtime - :before AND c.mtime + :after
        WHERE c.source_type = 'chatimg' AND c.status IN ('active', 'archived')
          AND NOT EXISTS (SELECT 1 FROM qq_preview_checks k WHERE k.path = c.path AND k.size = c.size)
          AND r.source_type = 'chatraw' AND r.status IN ('active', 'archived')
          AND EXISTS (SELECT 1 FROM image_qq_origins l CROSS JOIN qq_origins o ON o.id = l.origin_id
              WHERE l.image_id = r.id AND o.sender_key IN (:senders))""")
    suspend fun sentPreviewCandidates(senders: List<String>, before: Long, after: Long): List<QqPreviewCandidate>

    /** 这些图关联的消息记下的 MD5（按消息 MD5 命名的 chatimg 是 QQ 显示用的，不算发送图片缓存） */
    @Query("""SELECT l.image_id AS imageId, o.md5 AS md5 FROM image_qq_origins l CROSS JOIN qq_origins o ON o.id = l.origin_id
        WHERE l.image_id IN (:ids)""")
    suspend fun linkedMd5s(ids: List<Long>): List<QqImageMd5>

    @Upsert
    suspend fun putPreviewChecks(checks: List<QqPreviewCheckEntity>)

    /** 发送图片缓存还不在原图那一家里的（图库合并显示用）：预览、它现在的家族键、原图的家族键 */
    @Query("""SELECT i.id AS id, i.variant_group AS fromGroup, r.variant_group AS toGroup
        FROM qq_preview_checks k CROSS JOIN images i ON i.path = k.path CROSS JOIN images r ON r.path = k.raw_path
        WHERE k.raw_path IS NOT NULL AND i.size = k.size AND r.variant_group IS NOT NULL
          AND i.variant_group IS NOT r.variant_group""")
    suspend fun previewFamilyMoves(): List<QqPreviewFamilyMove>

    @Query("SELECT * FROM qq_source_accounts ORDER BY display_name, account_key")
    fun observeAccounts(): Flow<List<QqSourceAccountEntity>>

    @Query("""SELECT MAX(o.conversation_name) AS conversationName, MAX(o.sender_name) AS senderName,
        MAX(o.member_name) AS memberName, o.chat_type AS chatType,
        MAX(o.message_time) AS messageTime, COUNT(*) AS messageCount, MAX(a.display_name) AS accountName,
        MAX(o.sent_original) AS sentOriginal
        FROM image_qq_origins link JOIN qq_origins o ON o.id = link.origin_id
        JOIN qq_source_accounts a ON a.account_key = o.account_key WHERE link.image_id = :imageId
        GROUP BY o.conversation_key, o.sender_key ORDER BY messageTime DESC""")
    fun observeImageOrigins(imageId: Long): Flow<List<QqOriginDetails>>

    @Query("DELETE FROM qq_source_accounts")
    suspend fun clear()

    /** 账号的图片来源总数（增量读取后也是全部，不只是这次新读到的） */
    @Query("SELECT COUNT(*) FROM qq_origins WHERE account_key = :accountKey")
    suspend fun originCount(accountKey: String): Int

    // ---- 读取水位（增量读取） ----

    @Query("SELECT * FROM qq_read_marks WHERE account_key = :accountKey")
    suspend fun readMarks(accountKey: String): List<QqReadMarkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putReadMarks(marks: List<QqReadMarkEntity>)

    /** 这次没读完整的表去掉水位，下次从头读 */
    @Query("DELETE FROM qq_read_marks WHERE account_key = :accountKey AND table_name IN (:tables)")
    suspend fun deleteReadMarks(accountKey: String, tables: Collection<String>)

    /** 读取过来源（有水位）的账号数；后台自动读取只在读过一次之后进行 */
    @Query("SELECT COUNT(DISTINCT account_key) FROM qq_read_marks")
    suspend fun markedAccounts(): Int

    // ---- 自动归档名单（键为 '' 表示不限） ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addAutoArchive(entry: QqAutoArchiveEntity)

    @Query("DELETE FROM qq_auto_archive WHERE conversation_key = :conversationKey AND sender_key = :senderKey")
    suspend fun removeAutoArchive(conversationKey: String, senderKey: String)

    @Query("SELECT * FROM qq_auto_archive ORDER BY created_at DESC")
    fun observeAutoArchive(): Flow<List<QqAutoArchiveEntity>>

    @Query("SELECT * FROM qq_auto_archive")
    suspend fun autoArchiveEntries(): List<QqAutoArchiveEntity>
}

/** 排序：日期 / 大小 × 降序 / 升序（与 SourceSort 的顺序一致）；合并显示时大小按整组合计 */
enum class QqScopeOrder(private val column: String?, private val direction: String) {
    DATE_DESC(null, "DESC"),
    DATE_ASC(null, "ASC"),
    SIZE_DESC("size", "DESC"),
    SIZE_ASC("size", "ASC"),
    ;

    fun sql(stacked: Boolean): String {
        val key = when {
            column == null -> "i.mtime"
            stacked -> "i.variant_bytes"
            else -> "i.size"
        }
        return "$key $direction, i.id $direction"
    }
}

/**
 * 浏览范围的 SQL。分类范围先从关联表按索引取出图片 id，再按主键回表（状态条件加 `+` 禁用索引，
 * 避免规划器改走 status 索引扫全库）；「全部」范围则沿用 images 上的状态 / 时间索引，逐行核对是否有关联。
 */
object QqScopeSql {
    private fun content(content: QqContent, indexed: Boolean): String {
        val p = if (indexed) "" else "+"
        return when (content) {
            QqContent.IMAGES -> "(${p}i.status = 'active' AND ${p}i.source_type <> 'chatthumb')"
            QqContent.THUMBNAILS -> "(${p}i.status = 'thumbnail_trash' AND ${p}i.source_type = 'chatthumb')"
        }
    }

    private fun contents(set: Set<QqContent>, indexed: Boolean): String =
        set.joinToString(" OR ", prefix = "(", postfix = ")") { content(it, indexed) }

    /** 范围条件与参数 */
    private fun scope(ids: QqScopeIds): Pair<String, List<Any>> = when {
        ids.conversationId != null && ids.senderId != null ->
            "i.id IN (SELECT image_id FROM qq_image_contexts WHERE conversation_id = ? AND sender_id = ?)" to
                listOf(ids.conversationId, ids.senderId)
        ids.conversationId != null ->
            "i.id IN (SELECT image_id FROM qq_image_contexts WHERE conversation_id = ?)" to listOf(ids.conversationId)
        ids.senderId != null ->
            "i.id IN (SELECT image_id FROM qq_image_contexts WHERE sender_id = ?)" to listOf(ids.senderId)
        else -> "EXISTS (SELECT 1 FROM qq_image_contexts c WHERE c.image_id = i.id)" to emptyList()
    }

    private fun where(ids: QqScopeIds, set: Set<QqContent>): Pair<String, List<Any>> {
        val (scopeSql, args) = scope(ids)
        val all = ids.conversationId == null && ids.senderId == null
        return "${contents(set, indexed = all)} AND $scopeSql" to args
    }

    /** 浏览网格的条件：合并显示时同一张图只留代表图（只对图片生效，缩略图本就各自独立） */
    private fun browse(ids: QqScopeIds, content: QqContent, stacked: Boolean): Pair<String, List<Any>> {
        val (w, args) = where(ids, setOf(content))
        return (if (stacked(content, stacked)) "$w AND +i.variant_hidden = 0" else w) to args
    }

    private fun stacked(content: QqContent, stacked: Boolean) = stacked && content == QqContent.IMAGES

    /** 合并显示时按整组合计的大小 */
    private fun sizeColumn(content: QqContent, stacked: Boolean) = if (stacked(content, stacked)) "i.variant_bytes" else "i.size"

    private fun query(sql: String, args: List<Any>) = SimpleSQLiteQuery(sql, args.toTypedArray())

    fun page(ids: QqScopeIds, content: QqContent, order: QqScopeOrder, stacked: Boolean): SupportSQLiteQuery {
        val (w, args) = browse(ids, content, stacked)
        return query("SELECT i.* FROM images i WHERE $w ORDER BY ${order.sql(stacked(content, stacked))}", args)
    }

    fun stats(ids: QqScopeIds, content: QqContent): SupportSQLiteQuery {
        val (w, args) = where(ids, setOf(content))
        return query("SELECT COUNT(*) AS count, COALESCE(SUM(i.size), 0) AS bytes FROM images i WHERE $w", args)
    }

    fun dateBuckets(ids: QqScopeIds, content: QqContent, stacked: Boolean): SupportSQLiteQuery {
        val (w, args) = browse(ids, content, stacked)
        return query("SELECT i.date_bucket AS bucketKey, COUNT(*) AS count FROM images i WHERE $w GROUP BY i.date_bucket", args)
    }

    /** 大小分桶与图库 sizeBucketId 一致 */
    fun sizeBuckets(ids: QqScopeIds, content: QqContent, stacked: Boolean): SupportSQLiteQuery {
        val (w, args) = browse(ids, content, stacked)
        val size = sizeColumn(content, stacked)
        return query("SELECT ${SizeBucketSql.key(size)} AS bucketKey, COUNT(*) AS count FROM images i WHERE $w GROUP BY bucketKey", args)
    }

    /** 网格里的全部图片（全选） */
    fun ids(ids: QqScopeIds, content: QqContent, stacked: Boolean): SupportSQLiteQuery {
        val (w, args) = browse(ids, content, stacked)
        return query("SELECT i.id FROM images i WHERE $w", args)
    }

    /** 网格里某个分组的全部图片（点分组标题全选） */
    fun sectionIds(ids: QqScopeIds, content: QqContent, stacked: Boolean, section: TimelineSection): SupportSQLiteQuery {
        val (w, args) = browse(ids, content, stacked)
        return when (section) {
            is TimelineSection.Day -> query("SELECT i.id FROM images i WHERE $w AND +i.mtime >= ? AND +i.mtime < ?", args + listOf(section.from, section.until))
            is TimelineSection.Size -> {
                val size = sizeColumn(content, stacked)
                query("SELECT i.id FROM images i WHERE $w AND +$size >= ? AND +$size < ?", args + listOf(section.low, section.high))
            }
        }
    }

    /** 也出现在范围之外（其他会话或其他发送人）的图片 */
    private fun shared(ids: QqScopeIds): Pair<String, List<Any>> {
        val terms = ArrayList<String>()
        val args = ArrayList<Any>()
        ids.conversationId?.let { terms += "o.conversation_id <> ?"; args += it }
        ids.senderId?.let { terms += "o.sender_id <> ?"; args += it }
        if (terms.isEmpty()) return "0" to emptyList()
        return "EXISTS (SELECT 1 FROM qq_image_contexts o WHERE o.image_id = i.id AND (${terms.joinToString(" OR ")}))" to args
    }

    private const val PROTECTED = "i.is_protected = 1"

    /** 只清理文件时间早于 [before] 的（null 不限） */
    private fun olderThan(where: Pair<String, List<Any>>, before: Long?): Pair<String, List<Any>> =
        if (before == null) where else "${where.first} AND +i.mtime < ?" to where.second + before

    /** 按范围清理前的统计：共用与归档（受保护）的图片分别计数；[before] 见 [olderThan] */
    fun counts(ids: QqScopeIds, set: Set<QqContent>, includeShared: Boolean, before: Long? = null): SupportSQLiteQuery {
        val (w, whereArgs) = olderThan(where(ids, set), before)
        val (sharedSql, sharedArgs) = shared(ids)
        val removable = "(NOT $PROTECTED AND (${if (includeShared) 1 else 0} OR NOT $sharedSql))"
        val sql = """SELECT COUNT(*) AS total, COALESCE(SUM(i.size), 0) AS totalBytes,
            COALESCE(SUM(CASE WHEN $sharedSql THEN 1 ELSE 0 END), 0) AS shared,
            COALESCE(SUM(CASE WHEN $PROTECTED THEN 1 ELSE 0 END), 0) AS protectedCount,
            COALESCE(SUM(CASE WHEN $removable THEN 1 ELSE 0 END), 0) AS removable,
            COALESCE(SUM(CASE WHEN $removable THEN i.size ELSE 0 END), 0) AS removableBytes
            FROM images i WHERE $w"""
        // 参数按出现顺序：shared、removable 两次（各含 shared）、where
        return query(sql, sharedArgs + sharedArgs + sharedArgs + whereArgs)
    }

    /** 按范围归档前的统计（见 [QqArchiveCounts]）；共用的照样归档，只是告诉用户有多少 */
    fun archiveCounts(ids: QqScopeIds): SupportSQLiteQuery {
        val (w, whereArgs) = where(ids, setOf(QqContent.IMAGES, QqContent.THUMBNAILS))
        val (sharedSql, sharedArgs) = shared(ids)
        val sql = """SELECT
            COALESCE(SUM(CASE WHEN i.source_type <> 'chatthumb' THEN 1 ELSE 0 END), 0) AS images,
            COALESCE(SUM(CASE WHEN i.source_type <> 'chatthumb' THEN i.size ELSE 0 END), 0) AS imageBytes,
            COALESCE(SUM(CASE WHEN i.source_type = 'chatthumb' THEN 1 ELSE 0 END), 0) AS thumbs,
            COALESCE(SUM(CASE WHEN i.source_type = 'chatthumb' THEN i.size ELSE 0 END), 0) AS thumbBytes,
            COALESCE(SUM(CASE WHEN $sharedSql THEN 1 ELSE 0 END), 0) AS shared
            FROM images i WHERE $w AND +i.is_protected = 0"""
        return query(sql, sharedArgs + whereArgs)
    }

    /** 按范围归档的图片 id：范围里还没归档的全部图片与缩略图（含共用的、文件暂时不在的），keyset 分页 */
    fun archiveIds(ids: QqScopeIds, afterId: Long, limit: Int): SupportSQLiteQuery {
        val (scopeSql, args) = scope(ids)
        return query(
            """SELECT i.id FROM images i WHERE i.status IN ('active', 'thumbnail_trash', 'missing') AND +i.is_protected = 0
                AND $scopeSql AND i.id > ? ORDER BY i.id LIMIT ?""",
            args + listOf(afterId, limit),
        )
    }

    /** 可清理的图片 id（keyset 分页） */
    fun removableIds(
        ids: QqScopeIds,
        set: Set<QqContent>,
        includeShared: Boolean,
        afterId: Long,
        maxId: Long,
        limit: Int,
        before: Long? = null,
    ): SupportSQLiteQuery {
        val (w, whereArgs) = olderThan(where(ids, set), before)
        val (sharedSql, sharedArgs) = shared(ids)
        val sharedFilter = if (includeShared) "" else " AND NOT $sharedSql"
        return query(
            "SELECT i.id FROM images i WHERE $w AND NOT $PROTECTED$sharedFilter AND i.id > ? AND i.id <= ? ORDER BY i.id LIMIT ?",
            whereArgs + (if (includeShared) emptyList() else sharedArgs) + listOf(afterId, maxId, limit),
        )
    }
}
