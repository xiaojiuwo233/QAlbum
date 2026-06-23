package com.xjw.qalbum.data.db.dao

import androidx.paging.PagingSource
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

    /** 批量插入回收站记录（移入回收站时一批写入，避免逐条事务各自 fsync）。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertQuarantineAll(items: List<QuarantineEntity>)

    /**
     * 回收站列表分页（UI 用 Paging3）。早期 observeQuarantine() 全量 `SELECT *` 把整张 quarantine
     * （每行含多个 path 文本，10 列 ~1.2KB/行）一次性物化，积累上千项就撞 CursorWindow 2MB 崩
     * `Couldn't read row N, col 0`。
     */
    @Query("SELECT * FROM quarantine ORDER BY quarantined_at DESC")
    fun pagingQuarantine(): PagingSource<Int, QuarantineEntity>

    @Query("SELECT COUNT(*) FROM quarantine")
    suspend fun countQuarantine(): Int

    /** keyset 分页遍历（一键清理逐页 purge，避免全量装载）；purge 删行后旧 id ≤ afterId 自然出后续页。 */
    @Query("SELECT * FROM quarantine WHERE id > :afterId ORDER BY id ASC LIMIT :limit")
    suspend fun listQuarantinePaged(afterId: Long, limit: Int): List<QuarantineEntity>

    @Query("SELECT * FROM quarantine WHERE id = :id LIMIT 1")
    suspend fun getQuarantineById(id: Long): QuarantineEntity?

    @Query("DELETE FROM quarantine WHERE id = :id")
    suspend fun deleteQuarantine(id: Long)

    /** 批量删除回收站记录（一键清理逐页批删，避免逐条事务）；调用方分块控制在 SQLite 变量上限内。 */
    @Query("DELETE FROM quarantine WHERE id IN (:ids)")
    suspend fun deleteQuarantineByIds(ids: List<Long>)

    @Query("SELECT COUNT(*) AS count, IFNULL(SUM(size), 0) AS bytes FROM quarantine")
    fun observeQuarantineStats(): Flow<QuarantineStatsRow>

    data class QuarantineStatsRow(val count: Int, val bytes: Long)
}
