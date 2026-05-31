package com.xjw.qalbum.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.xjw.qalbum.data.db.entity.CleanupBatchEntity
import com.xjw.qalbum.data.db.entity.CleanupCandidateEntity
import com.xjw.qalbum.data.db.entity.QuarantineEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CleanupDao {

    // ---- 批次 ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun createBatch(batch: CleanupBatchEntity): Long

    @Query("SELECT * FROM cleanup_batches ORDER BY created_at DESC LIMIT :limit")
    fun observeRecentBatches(limit: Int = 20): Flow<List<CleanupBatchEntity>>

    @Query("SELECT * FROM cleanup_batches WHERE id = :id LIMIT 1")
    suspend fun getBatch(id: Long): CleanupBatchEntity?

    // ---- 候选 ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCandidate(candidate: CleanupCandidateEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCandidates(items: List<CleanupCandidateEntity>): List<Long>

    @Query("SELECT * FROM cleanup_candidates WHERE batch_id = :batchId")
    suspend fun getCandidates(batchId: Long): List<CleanupCandidateEntity>

    // ---- 回收站 ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertQuarantine(item: QuarantineEntity): Long

    @Query("SELECT * FROM quarantine ORDER BY quarantined_at DESC")
    fun observeQuarantine(): Flow<List<QuarantineEntity>>

    @Query("SELECT * FROM quarantine WHERE id = :id LIMIT 1")
    suspend fun getQuarantineById(id: Long): QuarantineEntity?

    @Query("DELETE FROM quarantine WHERE id = :id")
    suspend fun deleteQuarantine(id: Long)

    @Query("SELECT COUNT(*) AS count, IFNULL(SUM(size), 0) AS bytes FROM quarantine")
    fun observeQuarantineStats(): Flow<QuarantineStatsRow>

    data class QuarantineStatsRow(val count: Int, val bytes: Long)
}
