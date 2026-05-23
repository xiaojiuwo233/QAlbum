package com.xjw.qalbum.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.xjw.qalbum.data.db.entity.ImageTagEntity
import com.xjw.qalbum.data.db.entity.TagEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TagDao {

    // ---- 标签字典 ----

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTag(tag: TagEntity): Long

    @Query("SELECT * FROM tags ORDER BY name")
    fun observeAll(): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): TagEntity?

    @Query("SELECT * FROM tags WHERE name = :name LIMIT 1")
    suspend fun findByName(name: String): TagEntity?

    // ---- 图-标签关联 ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun linkTag(rel: ImageTagEntity)

    @Query("DELETE FROM image_tags WHERE image_id = :imageId AND tag_id = :tagId")
    suspend fun unlinkTag(imageId: Long, tagId: Long)

    @Query("""
        SELECT t.* FROM tags t
        INNER JOIN image_tags it ON it.tag_id = t.id
        WHERE it.image_id = :imageId
    """)
    suspend fun getTagsForImage(imageId: Long): List<TagEntity>
}
