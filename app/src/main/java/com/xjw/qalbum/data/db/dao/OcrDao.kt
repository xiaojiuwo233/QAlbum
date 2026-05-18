package com.xjw.qalbum.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.xjw.qalbum.data.db.entity.OcrTextEntity

@Dao
interface OcrDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: OcrTextEntity)

    @Query("SELECT * FROM ocr_text WHERE image_id = :imageId LIMIT 1")
    suspend fun getByImageId(imageId: Long): OcrTextEntity?

    @Query("DELETE FROM ocr_text WHERE image_id = :imageId")
    suspend fun deleteByImageId(imageId: Long)
}
