package com.xjw.qalbum.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.xjw.qalbum.data.db.entity.DedupExclusionEntity

/**
 * 重复组「禁止再聚」约束的读写。表只存用户手动拆分过的图，规模很小，可全表读入内存。
 */
@Dao
interface DedupExclusionDao {

    /** 拆分时整组写入（同一 cohort）；主键冲突时覆盖，幂等。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<DedupExclusionEntity>)

    /** 全表读出，供聚类构建 imageId → cohort 集合。 */
    @Query("SELECT * FROM dedup_exclusions")
    suspend fun all(): List<DedupExclusionEntity>

    /** 下一个 cohort id 的来源（每次拆分用一个新 cohort）。 */
    @Query("SELECT IFNULL(MAX(cohort_id), 0) FROM dedup_exclusions")
    suspend fun maxCohortId(): Long
}
