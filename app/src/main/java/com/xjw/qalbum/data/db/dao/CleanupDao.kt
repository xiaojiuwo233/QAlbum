package com.xjw.qalbum.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.xjw.qalbum.data.db.entity.QuarantineEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CleanupDao {

    // ---- 回收站 ----

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertQuarantine(item: QuarantineEntity): Long

    /** 批量插入回收站记录（移入回收站时一批写入，避免逐条事务各自 fsync）。 */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertQuarantineAll(items: List<QuarantineEntity>): List<Long>

    /**
     * 回收站列表分页（UI 用 Paging3）。早期 observeQuarantine() 全量 `SELECT *` 把整张 quarantine
     * （每行含多个 path 文本，10 列 ~1.2KB/行）一次性物化，积累上千项就撞 CursorWindow 2MB 崩
     * `Couldn't read row N, col 0`。
     */
    @Query("""SELECT q.* FROM quarantine q WHERE q.state='ready'
        AND (q.group_id IS NULL OR q.id=(SELECT MIN(s.id) FROM quarantine s WHERE s.group_id=q.group_id AND s.state='ready'))
        ORDER BY q.quarantined_at DESC""")
    fun pagingQuarantine(): PagingSource<Int, QuarantineEntity>

    @Query("SELECT COUNT(DISTINCT COALESCE(group_id,'legacy:'||id)) FROM quarantine WHERE state='ready'")
    suspend fun countQuarantine(): Int

    /** keyset 分页遍历（一键清理逐页 purge，避免全量装载）；purge 删行后旧 id ≤ afterId 自然出后续页。 */
    @Query("""SELECT q.* FROM quarantine q WHERE q.state='ready' AND q.id>:afterId
        AND (q.group_id IS NULL OR q.id=(SELECT MIN(s.id) FROM quarantine s WHERE s.group_id=q.group_id AND s.state='ready'))
        ORDER BY q.id LIMIT :limit""")
    suspend fun listQuarantinePaged(afterId: Long, limit: Int): List<QuarantineEntity>

    /**
     * 已过期回收站记录（expires_at ≤ now），keyset 分页：过期自动清理逐页取出后走
     * purgeByRecords 批删（复用 IFileOps 批删范式），afterId 防止个别 purge 失败时原地打转。
     * quarantine 行数最多数万，expires_at 无索引的逐行过滤可接受，不为此加索引。
     */
    @Query("""SELECT q.* FROM quarantine q WHERE q.state='ready' AND q.expires_at<=:now AND q.id>:afterId
        AND (q.group_id IS NULL OR q.id=(SELECT MIN(s.id) FROM quarantine s WHERE s.group_id=q.group_id AND s.state='ready'))
        ORDER BY q.id LIMIT :limit""")
    suspend fun listExpiredQuarantinePaged(now: Long, afterId: Long, limit: Int): List<QuarantineEntity>

    /** 未完成文件操作的持久化日志；启动时按 id keyset 对账。 */
    @Query("SELECT * FROM quarantine WHERE state <> 'ready' AND id > :afterId ORDER BY id ASC LIMIT :limit")
    suspend fun listPendingQuarantinePaged(afterId: Long, limit: Int): List<QuarantineEntity>

    @Query("SELECT * FROM quarantine WHERE id = :id LIMIT 1")
    suspend fun getQuarantineById(id: Long): QuarantineEntity?

    @Query("SELECT * FROM quarantine WHERE group_id = :groupId ORDER BY id")
    suspend fun getQuarantineGroup(groupId: String): List<QuarantineEntity>

    /** 多组的全部记录（批量清理用）；调用方分块控制变量上限 */
    @Query("SELECT * FROM quarantine WHERE group_id IN (:groupIds) ORDER BY id")
    suspend fun getQuarantineGroups(groupIds: List<String>): List<QuarantineEntity>

    @Query("""SELECT COUNT(*) AS count,COALESCE(SUM(size),0) AS bytes FROM quarantine
        WHERE state='ready' AND (id=:id OR (:groupId IS NOT NULL AND group_id=:groupId))""")
    fun observeGroupStats(id: Long, groupId: String?): Flow<QuarantineStatsRow>

    @Query("UPDATE quarantine SET state = 'ready', thumbnail_path = :thumbnailPath WHERE id = :id")
    suspend fun markQuarantineReady(id: Long, thumbnailPath: String?)

    @Query("UPDATE quarantine SET state = 'restoring' WHERE id = :id AND state = 'ready'")
    suspend fun markQuarantineRestoring(id: Long): Int

    @Query("UPDATE quarantine SET state = 'ready' WHERE id = :id AND state = 'restoring'")
    suspend fun rollbackQuarantineRestore(id: Long): Int

    @Query("DELETE FROM quarantine WHERE id = :id")
    suspend fun deleteQuarantine(id: Long)

    /** 批量删除回收站记录（一键清理逐页批删，避免逐条事务）；调用方分块控制在 SQLite 变量上限内。 */
    @Query("DELETE FROM quarantine WHERE id IN (:ids)")
    suspend fun deleteQuarantineByIds(ids: List<Long>)

    @Query("SELECT COUNT(DISTINCT COALESCE(group_id,'legacy:'||id)) AS count, IFNULL(SUM(size), 0) AS bytes FROM quarantine WHERE state='ready'")
    fun observeQuarantineStats(): Flow<QuarantineStatsRow>

    data class QuarantineStatsRow(val count: Int, val bytes: Long)
}
