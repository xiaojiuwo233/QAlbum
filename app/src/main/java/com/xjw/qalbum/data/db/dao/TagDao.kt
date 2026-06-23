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

    /**
     * 标签管理页列表：AI 标签（type ai/risk）+ 用户手动「用户保留」标签（type user），
     * 及其当前关联的活跃图片数，按数量降序。
     * 连 image_tags + images 过滤掉缩略图与非活跃图，只统计真正可见可删的图。
     */
    @Query("""
        SELECT t.id AS id, t.name AS name, t.type AS type, COUNT(it.image_id) AS count
        FROM tags t
        INNER JOIN image_tags it ON it.tag_id = t.id
        INNER JOIN images i ON i.id = it.image_id
        WHERE t.type IN ('ai', 'risk', 'user')
          AND i.status = 'active' AND i.source_type <> 'chatthumb'
        GROUP BY t.id
        HAVING count > 0
        ORDER BY count DESC, t.name
    """)
    fun observeAiTagsWithCounts(): Flow<List<TagWithCount>>

    data class TagWithCount(
        val id: Long,
        val name: String,
        val type: String,
        val count: Int,
    )

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
