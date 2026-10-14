package com.xjw.qalbum.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.data.db.entity.OriginalBackupEntity
import kotlinx.coroutines.flow.Flow

/** 原图压缩的日志与原图备份（见 [OriginalBackupEntity]） */
@Dao
interface OriginalBackupDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(item: OriginalBackupEntity): Long

    @Query("SELECT * FROM original_backups WHERE id = :id")
    suspend fun getById(id: Long): OriginalBackupEntity?

    @Query("SELECT * FROM original_backups WHERE image_id = :imageId")
    suspend fun getByImageId(imageId: Long): OriginalBackupEntity?

    /** 详情里的压缩记录 */
    @Query("SELECT * FROM original_backups WHERE image_id = :imageId AND state = 'ready'")
    fun observeByImageId(imageId: Long): Flow<OriginalBackupEntity?>

    @Query("UPDATE original_backups SET state = 'ready' WHERE id = :id")
    suspend fun markReady(id: Long)

    @Query("UPDATE original_backups SET state = 'restoring' WHERE id = :id AND state = 'ready'")
    suspend fun markRestoring(id: Long): Int

    @Query("UPDATE original_backups SET state = 'ready' WHERE id = :id AND state = 'restoring'")
    suspend fun rollbackRestoring(id: Long): Int

    @Query("DELETE FROM original_backups WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM original_backups WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    /** 备份删掉后只清空路径：压缩记录留着，详情里仍能看到压缩前后的大小 */
    @Query("UPDATE original_backups SET backup_path = NULL WHERE id IN (:ids)")
    suspend fun clearBackupPaths(ids: List<Long>)

    /** 启动对账：未完成的替换 / 还原日志 */
    @Query("SELECT * FROM original_backups WHERE state <> 'ready' AND id > :afterId ORDER BY id LIMIT :limit")
    suspend fun listUnfinished(afterId: Long, limit: Int): List<OriginalBackupEntity>

    /** 回收站「压缩前的原图」：列出压缩后的图片本身（原图备份不一定能被 App 直接读取） */
    @Query("""SELECT i.* FROM images i JOIN original_backups b ON b.image_id = i.id
        WHERE b.state = 'ready' AND b.backup_path IS NOT NULL ORDER BY b.created_at DESC, b.id DESC""")
    fun pagingBackedUpImages(): PagingSource<Int, ImageEntity>

    @Query("SELECT * FROM original_backups WHERE state = 'ready' AND backup_path IS NOT NULL AND id > :afterId ORDER BY id LIMIT :limit")
    suspend fun listReady(afterId: Long, limit: Int): List<OriginalBackupEntity>

    @Query("""SELECT * FROM original_backups WHERE state = 'ready' AND backup_path IS NOT NULL
        AND expires_at <= :now AND id > :afterId ORDER BY id LIMIT :limit""")
    suspend fun listExpired(now: Long, afterId: Long, limit: Int): List<OriginalBackupEntity>

    /** 备份张数与占用（原图大小） */
    @Query("""SELECT COUNT(*) AS count, COALESCE(SUM(original_size), 0) AS bytes FROM original_backups
        WHERE state = 'ready' AND backup_path IS NOT NULL""")
    fun observeStats(): Flow<CleanupDao.QuarantineStatsRow>
}
