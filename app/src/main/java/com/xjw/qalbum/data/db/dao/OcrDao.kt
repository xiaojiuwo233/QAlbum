package com.xjw.qalbum.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.data.db.entity.OcrFtsEntity
import com.xjw.qalbum.data.db.entity.OcrTextEntity

@Dao
interface OcrDao {

    // ---- OCR 文本表 ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: OcrTextEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFts(entity: OcrFtsEntity)

    @Query("SELECT * FROM ocr_text WHERE image_id = :imageId LIMIT 1")
    suspend fun getByImageId(imageId: Long): OcrTextEntity?

    @Query("DELETE FROM ocr_text WHERE image_id = :imageId")
    suspend fun deleteByImageId(imageId: Long)

    @Query("DELETE FROM ocr_fts WHERE rowid = :imageId")
    suspend fun deleteFtsByImageId(imageId: Long)

    /**
     * 同时写 ocr_text + ocr_fts（rowid = imageId），保持两表同步
     */
    @Transaction
    suspend fun saveOcrResult(text: OcrTextEntity) {
        upsert(text)
        // FTS 表只索引文本字段
        upsertFts(OcrFtsEntity(rowid = text.imageId, text = text.text))
    }

    @Transaction
    suspend fun clearOcrFor(imageId: Long) {
        deleteByImageId(imageId)
        deleteFtsByImageId(imageId)
    }

    @Query("DELETE FROM ocr_text")
    suspend fun deleteAllText()

    @Query("DELETE FROM ocr_fts")
    suspend fun deleteAllFts()

    /** 重置全部分析时清空 OCR 文本与 FTS 索引 */
    @Transaction
    suspend fun clearAllOcr() {
        deleteAllText()
        deleteAllFts()
    }

    // ---- FTS5 / FTS4 搜索 ----

    /**
     * FTS 搜索：返回 imageId 与高亮片段
     * 关键字使用 SQLite FTS MATCH 语法（如 "发票"、"手机 OR 电话"、"\"完整短语\""）
     *
     * 注意：FTS4 没有 `rank` 列（那是 FTS5 才有），用 mtime 倒序近似"相关性"
     */
    @Query("""
        SELECT i.* FROM images i
        INNER JOIN ocr_fts ON ocr_fts.rowid = i.id
        WHERE ocr_fts MATCH :query
          AND i.status = 'active'
          AND i.source_type <> 'chatthumb'
        ORDER BY i.mtime DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun searchImages(query: String, limit: Int = 50, offset: Int = 0): List<ImageEntity>

    /** 带 snippet 的搜索结果（前端展示高亮上下文） */
    @Query("""
        SELECT
            i.id AS image_id,
            snippet(ocr_fts, '<b>', '</b>', '…', -1, 32) AS snippet,
            i.path AS path,
            i.filename AS filename,
            i.mtime AS mtime
        FROM ocr_fts
        INNER JOIN images i ON i.id = ocr_fts.rowid
        WHERE ocr_fts MATCH :query
          AND i.status = 'active'
          AND i.source_type <> 'chatthumb'
        ORDER BY i.mtime DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun searchWithSnippet(query: String, limit: Int = 50, offset: Int = 0): List<SearchHit>

    data class SearchHit(
        val image_id: Long,
        val snippet: String,
        val path: String,
        val filename: String,
        val mtime: Long,
    )

    @Query("""
        SELECT COUNT(*)
        FROM ocr_fts
        INNER JOIN images i ON i.id = ocr_fts.rowid
        WHERE ocr_fts MATCH :query
          AND i.status = 'active'
          AND i.source_type <> 'chatthumb'
    """)
    suspend fun countMatches(query: String): Int
}
