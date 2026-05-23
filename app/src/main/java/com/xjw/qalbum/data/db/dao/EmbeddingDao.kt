package com.xjw.qalbum.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.xjw.qalbum.data.db.entity.ImageEmbeddingEntity

@Dao
interface EmbeddingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ImageEmbeddingEntity)

    @Query("SELECT * FROM image_embeddings WHERE image_id = :imageId LIMIT 1")
    suspend fun getByImageId(imageId: Long): ImageEmbeddingEntity?

    @Query("SELECT * FROM image_embeddings")
    suspend fun listAll(): List<ImageEmbeddingEntity>

    @Query("SELECT image_id FROM image_embeddings")
    suspend fun listIds(): List<Long>

    @Query("DELETE FROM image_embeddings WHERE image_id = :imageId")
    suspend fun deleteByImageId(imageId: Long)

    @Query("SELECT COUNT(*) FROM image_embeddings")
    suspend fun count(): Int
}
