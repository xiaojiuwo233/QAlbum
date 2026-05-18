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
 * 图片表 DAO —— Phase 0 提供基础读写接口；
 * Phase 1 起会补充变体组、扫描状态等更复杂的查询。
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

    @Transaction
    suspend fun insertBatch(images: List<ImageEntity>) {
        if (images.isEmpty()) return
        upsertAll(images)
    }

    // ---- 单条查询 ----

    @Query("SELECT * FROM images WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): ImageEntity?

    @Query("SELECT * FROM images WHERE path = :path LIMIT 1")
    suspend fun getByPath(path: String): ImageEntity?

    // ---- 列表 / 分页 ----

    @Query("""
        SELECT * FROM images
        WHERE status = 'active' AND (variant_group IS NULL OR is_variant_primary = 1)
        ORDER BY mtime DESC
    """)
    fun pagingActiveTimeline(): PagingSource<Int, ImageEntity>

    @Query("SELECT * FROM images ORDER BY mtime DESC LIMIT :limit")
    fun observeRecent(limit: Int = 50): Flow<List<ImageEntity>>

    // ---- 仪表盘统计 ----

    @Query("SELECT COUNT(*) FROM images WHERE status = 'active'")
    fun observeActiveCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM images WHERE status = 'active' AND (variant_group IS NULL OR is_variant_primary = 1)")
    fun observeLogicalCount(): Flow<Int>

    @Query("SELECT IFNULL(SUM(size), 0) FROM images WHERE status = 'active'")
    fun observeTotalSize(): Flow<Long>

    @Query("SELECT source_type, COUNT(*) AS cnt, IFNULL(SUM(size), 0) AS bytes FROM images WHERE status = 'active' GROUP BY source_type")
    fun observeSourceBreakdown(): Flow<List<SourceBreakdownRow>>

    data class SourceBreakdownRow(
        val source_type: String,
        val cnt: Int,
        val bytes: Long,
    )
}
