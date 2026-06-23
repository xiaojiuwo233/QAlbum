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

    /**
     * 分页装载（keyset，image_id 升序）：[com.xjw.qalbum.service.ai.SemanticIndex] 首次构建索引时逐页拉。
     * 早期 listAll() 全表 `SELECT *` 把所有 embedding（每行含向量 BLOB）一次性物化，行多时会撞
     * CursorWindow 2MB 上限崩 `Couldn't read row N, col 0`。
     */
    @Query("SELECT * FROM image_embeddings WHERE image_id > :afterId ORDER BY image_id ASC LIMIT :limit")
    suspend fun listPaged(afterId: Long, limit: Int): List<ImageEmbeddingEntity>

    @Query("SELECT image_id FROM image_embeddings")
    suspend fun listIds(): List<Long>

    @Query("DELETE FROM image_embeddings WHERE image_id = :imageId")
    suspend fun deleteByImageId(imageId: Long)

    @Query("DELETE FROM image_embeddings")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM image_embeddings")
    suspend fun count(): Int
}
