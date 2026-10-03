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
     * 是否已有向量（主键 EXISTS 单查）：管线判「是否需要嵌入」用此方法，
     * 不要为判存在触发 SemanticIndex 全量装载（那是必然超堆的路径，见 [ImageEmbeddingEntity]）。
     */
    @Query("SELECT EXISTS(SELECT 1 FROM image_embeddings WHERE image_id = :imageId)")
    suspend fun exists(imageId: Long): Boolean

    /**
     * 分页装载（keyset，image_id 升序）：[com.xjw.qalbum.service.ai.SemanticIndex] 首次构建索引时逐页拉。
     * 早期 listAll() 全表 `SELECT *` 把所有 embedding（每行含向量 BLOB）一次性物化，行多时会撞
     * CursorWindow 2MB 上限崩 `Couldn't read row N, col 0`。
     */
    @Query("""
        SELECT e.* FROM image_embeddings e
        INNER JOIN images i ON i.id = e.image_id
        WHERE e.image_id > :afterId AND e.model = :model
          AND i.status = 'active' AND i.source_type <> 'chatthumb' AND i.embedding_done = 1
        ORDER BY e.image_id ASC LIMIT :limit
    """)
    suspend fun listPaged(afterId: Long, limit: Int, model: String): List<ImageEmbeddingEntity>

    @Query("SELECT image_id FROM image_embeddings")
    suspend fun listIds(): List<Long>

    @Query("DELETE FROM image_embeddings WHERE image_id = :imageId")
    suspend fun deleteByImageId(imageId: Long)

    /**
     * 批量删向量（永久删除路径的级联清理用，调用方分块 ≤500/批）：
     * 死向量残留会永久占据语义搜索 TopK 名额（searchTopK 流式扫全表打分）。
     */
    @Query("DELETE FROM image_embeddings WHERE image_id IN (:ids)")
    suspend fun deleteByImageIds(ids: List<Long>)

    @Query("DELETE FROM image_embeddings")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM image_embeddings")
    suspend fun count(): Int
}
