package com.xjw.qalbum.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.xjw.qalbum.data.db.FtsTokenize
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

    /** 批量取一页图片的 OCR 文本（评分阶段按页一次查，避免逐图 N+1 单查）；调用方分块控制在变量上限内。 */
    @Query("SELECT * FROM ocr_text WHERE image_id IN (:imageIds)")
    suspend fun getByImageIds(imageIds: List<Long>): List<OcrTextEntity>

    @Query("DELETE FROM ocr_text WHERE image_id = :imageId")
    suspend fun deleteByImageId(imageId: Long)

    @Query("DELETE FROM ocr_fts WHERE rowid = :imageId")
    suspend fun deleteFtsByImageId(imageId: Long)

    /** 批量删 OCR 文本（永久删除路径的派生数据级联清理用）；调用方分块控制变量上限。 */
    @Query("DELETE FROM ocr_text WHERE image_id IN (:ids)")
    suspend fun deleteTextByImageIds(ids: List<Long>)

    /** 批量删 FTS 行（rowid = imageId；FTS4 独立表无 CASCADE，必须与 ocr_text 一起删）。 */
    @Query("DELETE FROM ocr_fts WHERE rowid IN (:ids)")
    suspend fun deleteFtsByImageIds(ids: List<Long>)

    /**
     * 同时写 ocr_text + ocr_fts（rowid = imageId），保持两表同步。
     * ocr_text 存原文；ocr_fts 存 CJK 逐字切分后的文本（见 [FtsTokenize]），
     * 否则 simple 分词器把整行中文当单个 token，前缀查询永远命中不了行中位置的词。
     */
    @Transaction
    suspend fun saveOcrResult(text: OcrTextEntity) {
        upsert(text)
        // FTS 表只索引文本字段
        upsertFts(OcrFtsEntity(rowid = text.imageId, text = FtsTokenize.tokenizeCjkForIndex(text.text)))
    }

    /**
     * 带乐观校验的 ocr_done 标记：仅当行 size/mtime 仍与 OCR 读取时一致才生效。
     * 文件在 OCR 期间被重写（扫描 updateFromScanByPath 已重置分析字段）时返回 0，
     * 调用方应丢弃陈旧结果、留待下轮重跑。返回受影响行数。
     */
    @Query("UPDATE images SET ocr_done = 1 WHERE id = :imageId AND size = :expectedSize AND mtime = :expectedMtime")
    suspend fun markOcrDoneGuarded(imageId: Long, expectedSize: Long, expectedMtime: Long): Int

    /**
     * 整块 OCR 结果一个事务写回（含 ocr_done 标记）：把「每图 2-3 个独立事务」收敛为按块提交，
     * 使 images 表失效频率降 1-2 个数量级（仪表盘/清理页的聚合 Flow 不再背靠背重查）。
     * 逐条先过 [markOcrDoneGuarded]，文件已变化的条目连同文本一起丢弃（不落陈旧 OCR）。
     */
    @Transaction
    suspend fun saveOcrResultsBatch(items: List<OcrWrite>) {
        for (item in items) {
            if (markOcrDoneGuarded(item.imageId, item.expectedSize, item.expectedMtime) > 0) {
                item.text?.let { saveOcrResult(it) }
            }
        }
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

    // ---- FTS4 搜索 ----
    //
    // 对外方法接受**原始用户输入**（default 方法内经 [FtsTokenize.buildFtsMatchQuery] 构造 MATCH
    // 表达式：中文逐字短语、ASCII 前缀匹配），与索引期切分规则配对；`*Match` 后缀方法才是
    // 直接下发 MATCH 语法的原始查询。

    suspend fun searchImages(query: String, limit: Int = 50, offset: Int = 0): List<ImageEntity> {
        val match = FtsTokenize.buildFtsMatchQuery(query)
        if (match.isEmpty()) return emptyList()
        return searchImagesMatch(match, limit, offset)
    }

    suspend fun searchWithSnippet(query: String, limit: Int = 50, offset: Int = 0): List<SearchHit> {
        val match = FtsTokenize.buildFtsMatchQuery(query)
        if (match.isEmpty()) return emptyList()
        return searchWithSnippetMatch(match, limit, offset).map {
            // 还原索引期逐字切分引入的空格，避免 snippet 显示成「发 票 报 销」
            it.copy(snippet = FtsTokenize.collapseCjkSpacesInSnippet(it.snippet))
        }
    }

    suspend fun countMatches(query: String): Int {
        val match = FtsTokenize.buildFtsMatchQuery(query)
        if (match.isEmpty()) return 0
        return countMatchesMatch(match)
    }

    /**
     * FTS 搜索：MATCH 语法层查询（如 `"发 票"`、`hello*`、`a* OR b*`）。
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
    suspend fun searchImagesMatch(query: String, limit: Int = 50, offset: Int = 0): List<ImageEntity>

    /**
     * 带 snippet 的搜索结果（前端展示高亮上下文），MATCH 语法层查询。
     *
     * 两段式：内层子查询只按 rowid 定位当前页（排序 + LIMIT/OFFSET），外层仅对这 ≤limit 行
     * 计算 snippet()——snippet 需要逐文档重扫文本，常见词命中数万行时若对全部命中行先算
     * snippet 再排序截断，单次查询即秒级卡顿。
     */
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
          AND ocr_fts.rowid IN (
            SELECT f.rowid
            FROM ocr_fts f
            INNER JOIN images i2 ON i2.id = f.rowid
            WHERE f.text MATCH :query
              AND i2.status = 'active'
              AND i2.source_type <> 'chatthumb'
            ORDER BY i2.mtime DESC
            LIMIT :limit OFFSET :offset
          )
        ORDER BY i.mtime DESC
    """)
    suspend fun searchWithSnippetMatch(query: String, limit: Int = 50, offset: Int = 0): List<SearchHit>

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
    suspend fun countMatchesMatch(query: String): Int
}

/**
 * 一张图的 OCR 阶段写回，供 [OcrDao.saveOcrResultsBatch] 整块事务提交。
 * [text] 为 null 表示该图无需落文本（识别失败等），仅做守卫标记；
 * [expectedSize]/[expectedMtime] 取自 OCR 读取文件时装载的实体，作乐观校验。
 */
data class OcrWrite(
    val imageId: Long,
    val expectedSize: Long,
    val expectedMtime: Long,
    val text: OcrTextEntity?,
)
