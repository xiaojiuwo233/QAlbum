package com.xjw.qalbum.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.xjw.qalbum.data.db.entity.AiAnalysisEntity

@Dao
interface AiAnalysisDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(analysis: AiAnalysisEntity)

    @Query("SELECT * FROM ai_analysis WHERE image_id = :imageId")
    suspend fun getByImageId(imageId: Long): AiAnalysisEntity?

    @Query("DELETE FROM ai_analysis WHERE image_id IN (:ids)")
    suspend fun deleteByImageIds(ids: List<Long>)

    @Query("DELETE FROM ai_analysis")
    suspend fun deleteAll()
}
