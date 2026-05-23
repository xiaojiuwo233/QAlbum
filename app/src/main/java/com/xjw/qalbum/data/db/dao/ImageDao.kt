package com.xjw.qalbum.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.xjw.qalbum.data.db.entity.ImageEntity
import kotlinx.coroutines.flow.Flow

/**
 * 图片表 DAO
 *
 * Phase 1 重点：扫描 / 变体组 / 时间线分页 / 仪表盘统计
 * Phase 2 起会补充：xxhash/phash 查询、重复组聚类、模糊度统计等
 */
@Dao
interface ImageDao {

    // ---- 写入 ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(image: ImageEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(images: List<ImageEntity>): List<Long>

    @Update
    suspend fun update(image: ImageEntity)

    @Query("UPDATE images SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)

    @Query("UPDATE images SET last_seen = :ts WHERE id = :id")
    suspend fun touchLastSeen(id: Long, ts: Long)

    @Transaction
    suspend fun insertBatch(images: List<ImageEntity>) {
        if (images.isEmpty()) return
        upsertAll(images)
    }

    // ---- 单条 / 路径查询 ----

    @Query("SELECT * FROM images WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): ImageEntity?

    @Query("SELECT * FROM images WHERE path = :path LIMIT 1")
    suspend fun getByPath(path: String): ImageEntity?

    /** 仅取必要字段做增量扫描 diff，避免装载整行 */
    @Query("SELECT id, path, size, mtime, last_seen FROM images")
    suspend fun listPathSizeMtime(): List<PathSizeMtime>

    data class PathSizeMtime(
        val id: Long,
        val path: String,
        val size: Long,
        val mtime: Long,
        val last_seen: Long,
    )

    // ---- 变体组建立 ----

    /** 找出 cache_key 在多条记录间共享的（跨 chatimg/chatraw/chatthumb） */
    @Query("""
        SELECT cache_key FROM images
        WHERE cache_key <> ''
        GROUP BY cache_key
        HAVING COUNT(*) > 1
    """)
    suspend fun cacheKeysWithMultipleEntries(): List<String>

    @Query("SELECT * FROM images WHERE cache_key = :cacheKey")
    suspend fun getByCacheKey(cacheKey: String): List<ImageEntity>

    @Query("SELECT * FROM images WHERE variant_group = :groupId")
    suspend fun getByVariantGroup(groupId: Long): List<ImageEntity>

    @Query("SELECT IFNULL(MAX(variant_group), 0) FROM images")
    suspend fun maxVariantGroupId(): Long

    @Query("""
        UPDATE images
        SET variant_group = :groupId, is_variant_primary = :isPrimary
        WHERE id = :id
    """)
    suspend fun assignVariant(id: Long, groupId: Long, isPrimary: Boolean)

    // ---- 时间线分页 ----

    /** 时间线视图：默认按 mtime 倒序，变体组合并（同组仅主图出现） */
    @Query("""
        SELECT * FROM images
        WHERE status = 'active' AND (variant_group IS NULL OR is_variant_primary = 1)
        ORDER BY mtime DESC, id DESC
    """)
    fun pagingActiveTimeline(): PagingSource<Int, ImageEntity>

    /** 详情页左右滑动使用同一套时间线排序。 */
    @Query("""
        SELECT * FROM images
        WHERE status = 'active' AND (variant_group IS NULL OR is_variant_primary = 1)
        ORDER BY mtime DESC, id DESC
    """)
    suspend fun listActiveTimeline(): List<ImageEntity>

    @Query("SELECT * FROM images ORDER BY mtime DESC LIMIT :limit")
    fun observeRecent(limit: Int = 50): Flow<List<ImageEntity>>

    // ---- 仪表盘统计 ----

    /** 物理文件总数（含变体所有副本） */
    @Query("SELECT COUNT(*) FROM images WHERE status = 'active'")
    fun observeActiveCount(): Flow<Int>

    /** 逻辑图片数（变体合并后） */
    @Query("""
        SELECT COUNT(*) FROM images
        WHERE status = 'active' AND (variant_group IS NULL OR is_variant_primary = 1)
    """)
    fun observeLogicalCount(): Flow<Int>

    @Query("SELECT IFNULL(SUM(size), 0) FROM images WHERE status = 'active'")
    fun observeTotalSize(): Flow<Long>

    @Query("""
        SELECT source_type AS source, COUNT(*) AS count, IFNULL(SUM(size), 0) AS bytes
        FROM images WHERE status = 'active'
        GROUP BY source_type
    """)
    fun observeSourceBreakdown(): Flow<List<SourceBreakdownRow>>

    data class SourceBreakdownRow(
        val source: String,
        val count: Int,
        val bytes: Long,
    )

    /** 已存在的 path 集合，用于增量扫描快速判重（一次性拉取） */
    @Query("SELECT path FROM images")
    suspend fun listAllPaths(): List<String>

    // ---- Phase 2：Hash / 质量 / 重复组 ----

    /** 找出待计算 hash 的活跃图片（按 mtime 倒序，最近的优先） */
    @Query("""
        SELECT * FROM images
        WHERE status = 'active' AND hash_done = 0
        ORDER BY mtime DESC
        LIMIT :limit
    """)
    suspend fun pendingHash(limit: Int = 200): List<ImageEntity>

    /** 待做质量分析的主图（仅对主图执行，避免变体重复算） */
    @Query("""
        SELECT * FROM images
        WHERE status = 'active' AND hash_done = 1
          AND blur_score IS NULL AND width = 0
          AND (variant_group IS NULL OR is_variant_primary = 1)
        ORDER BY mtime DESC
        LIMIT :limit
    """)
    suspend fun pendingQuality(limit: Int = 200): List<ImageEntity>

    @Query("""
        UPDATE images
        SET xxhash = :xxhash, phash = :phash, hash_done = 1
        WHERE id = :id
    """)
    suspend fun saveHash(id: Long, xxhash: String?, phash: Long?)

    @Query("""
        UPDATE images
        SET width = :w, height = :h,
            blur_score = :blur, is_blank = :blank, is_corrupt = :corrupt
        WHERE id = :id
    """)
    suspend fun saveQuality(id: Long, w: Int, h: Int, blur: Float?, blank: Boolean, corrupt: Boolean)

    /** 重复组聚类辅助：按 xxhash 分组的 ID 列表（排除 hash 为 null） */
    @Query("""
        SELECT xxhash AS hash, GROUP_CONCAT(id) AS ids
        FROM images
        WHERE status = 'active' AND xxhash IS NOT NULL
        GROUP BY xxhash
        HAVING COUNT(*) > 1
    """)
    suspend fun xxhashGroups(): List<XxhashGroupRow>

    data class XxhashGroupRow(
        val hash: String,
        val ids: String,
    )

    @Query("SELECT IFNULL(MAX(duplicate_group_id), 0) FROM images")
    suspend fun maxDuplicateGroupId(): Long

    @Query("""
        UPDATE images
        SET duplicate_group_id = :groupId, is_duplicate_best = :isBest
        WHERE id = :id
    """)
    suspend fun assignDuplicate(id: Long, groupId: Long, isBest: Boolean)

    /** 已有 pHash 的主图（每变体组只取主图，避免视觉相似检测重复处理） */
    @Query("""
        SELECT id, phash, variant_group FROM images
        WHERE status = 'active' AND phash IS NOT NULL
          AND (variant_group IS NULL OR is_variant_primary = 1)
    """)
    suspend fun primaryPhashes(): List<PhashRow>

    data class PhashRow(
        val id: Long,
        val phash: Long?,
        val variant_group: Long?,
    )

    @Query("UPDATE images SET delete_score = :score WHERE id = :id")
    suspend fun updateDeleteScore(id: Long, score: Float)

    @Query("UPDATE images SET info_score = :score WHERE id = :id")
    suspend fun updateInfoScore(id: Long, score: Float)

    /** 删除分数高于阈值的候选（按变体组主图） */
    @Query("""
        SELECT * FROM images
        WHERE status = 'active' AND delete_score >= :threshold
          AND (variant_group IS NULL OR is_variant_primary = 1)
          AND is_protected = 0 AND (user_keep IS NULL OR user_keep = 0)
        ORDER BY delete_score DESC
        LIMIT :limit
    """)
    suspend fun getByDeleteScoreAbove(threshold: Float, limit: Int = 500): List<ImageEntity>

    /** 重复组列表（仅返回组 id 与图片数量） */
    @Query("""
        SELECT duplicate_group_id AS groupId, COUNT(*) AS count, IFNULL(SUM(size), 0) AS bytes
        FROM images
        WHERE status = 'active' AND duplicate_group_id IS NOT NULL
        GROUP BY duplicate_group_id
        ORDER BY count DESC
    """)
    suspend fun duplicateGroupSummary(): List<DuplicateGroupSummary>

    data class DuplicateGroupSummary(
        val groupId: Long,
        val count: Int,
        val bytes: Long,
    )

    @Query("SELECT * FROM images WHERE duplicate_group_id = :groupId ORDER BY is_duplicate_best DESC, size DESC")
    suspend fun getByDuplicateGroup(groupId: Long): List<ImageEntity>

    @Query("UPDATE images SET user_keep = :keep WHERE id = :id")
    suspend fun setUserKeep(id: Long, keep: Boolean?)

    @Query("UPDATE images SET is_protected = :isProtected WHERE id = :id")
    suspend fun setProtected(id: Long, isProtected: Boolean)

    /** 已完成 hash 的主图，分页拉出来用于评分 / 全库再聚合 */
    @Query("""
        SELECT * FROM images
        WHERE status = 'active' AND hash_done = 1
          AND (variant_group IS NULL OR is_variant_primary = 1)
          AND id > :afterId
        ORDER BY id
        LIMIT :limit
    """)
    suspend fun pagePrimaryHashed(afterId: Long, limit: Int = 500): List<ImageEntity>

    // ---- Phase 3：OCR ----

    /** 待 OCR 的主图（仅主图执行，质量分析完成的优先） */
    @Query("""
        SELECT * FROM images
        WHERE status = 'active' AND ocr_done = 0
          AND (variant_group IS NULL OR is_variant_primary = 1)
          AND is_corrupt = 0
          AND width >= 64 AND height >= 64
        ORDER BY mtime DESC
        LIMIT :limit
    """)
    suspend fun pendingOcr(limit: Int = 50): List<ImageEntity>

    @Query("UPDATE images SET ocr_done = 1 WHERE id = :id")
    suspend fun markOcrDone(id: Long)

    @Query("""
        SELECT * FROM images
        WHERE status = 'active' AND id IN (:ids)
    """)
    suspend fun getByIds(ids: List<Long>): List<ImageEntity>
}
