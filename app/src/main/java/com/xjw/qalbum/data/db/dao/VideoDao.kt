package com.xjw.qalbum.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteQuery
import com.xjw.qalbum.data.db.entity.VideoEntity
import kotlinx.coroutines.flow.Flow

/** 视频页（查询由 VideoSql 拼出） */
@Dao
interface VideoDao {

    @RawQuery(observedEntities = [VideoEntity::class])
    fun paging(query: SupportSQLiteQuery): PagingSource<Int, VideoEntity>

    @RawQuery(observedEntities = [VideoEntity::class])
    fun observeBuckets(query: SupportSQLiteQuery): Flow<List<ImageDao.BucketCountRow>>

    /** 分组计数的一次性查询：每次网格刷新时现查，标题上的个数与刚做完的操作一致 */
    @RawQuery
    suspend fun buckets(query: SupportSQLiteQuery): List<ImageDao.BucketCountRow>

    @RawQuery
    suspend fun ids(query: SupportSQLiteQuery): List<Long>

    @Query("SELECT source, COUNT(*) AS count, COALESCE(SUM(size), 0) AS bytes FROM videos GROUP BY source")
    fun observeSources(): Flow<List<SourceStats>>

    @Query("SELECT * FROM videos WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<VideoEntity>

    // ---- 扫描 ----

    @Query("SELECT * FROM videos WHERE source = :source")
    suspend fun bySource(source: String): List<VideoEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<VideoEntity>)

    @Update
    suspend fun updateAll(rows: List<VideoEntity>)

    @Query("UPDATE videos SET seen_at = :seenAt WHERE id IN (:ids)")
    suspend fun touch(ids: List<Long>, seenAt: Long)

    /** 这次扫描没看到的（文件已经不在了） */
    @Query("DELETE FROM videos WHERE source = :source AND seen_at < :since")
    suspend fun deleteUnseen(source: String, since: Long): Int

    // ---- 时长与尺寸 ----

    @Query("SELECT * FROM videos WHERE duration_ms < 0 AND source IN (:sources) ORDER BY mtime DESC LIMIT :limit")
    suspend fun pendingMetadata(sources: List<String>, limit: Int): List<VideoEntity>

    @Query("UPDATE videos SET duration_ms = :durationMs, width = :width, height = :height WHERE id = :id AND size = :size AND mtime = :mtime")
    suspend fun setMetadata(id: Long, size: Long, mtime: Long, durationMs: Long, width: Int, height: Int)

    @Query("DELETE FROM videos WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    data class SourceStats(val source: String, val count: Int, val bytes: Long)
}
