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
        ORDER BY mtime DESC
    """)
    fun pagingActiveTimeline(): PagingSource<Int, ImageEntity>

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
}
