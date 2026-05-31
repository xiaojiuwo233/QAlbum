package com.xjw.qalbum.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.xjw.qalbum.data.db.entity.ImageEntity
import kotlinx.coroutines.flow.Flow

/**
 * 图片表 DAO
 *
 * Phase 1 重点：扫描 / 时间线分页 / 仪表盘统计
 * Phase 2 起会补充：xxhash/phash 查询、重复组聚类、模糊度统计等
 */
@Dao
interface ImageDao {

    // ---- 写入 ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(image: ImageEntity): Long

    @Update
    suspend fun update(image: ImageEntity)

    @Query("UPDATE images SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)

    @Query("UPDATE images SET status = 'deleted' WHERE id IN (:ids)")
    suspend fun markDeleted(ids: List<Long>)

    @Query("UPDATE images SET last_seen = :ts WHERE id = :id")
    suspend fun touchLastSeen(id: Long, ts: Long)

    @Query("""
        UPDATE images
        SET filename = :filename,
            parent_dir = :parentDir,
            source_type = :sourceType,
            cache_key = :cacheKey,
            size = :size,
            mtime = :mtime,
            last_seen = :lastSeen,
            mime = :mime,
            is_animated = :isAnimated,
            status = CASE
                WHEN :sourceType = 'chatthumb' AND status != 'deleted' THEN 'thumbnail_trash'
                WHEN status = 'missing' THEN 'active'
                ELSE status
            END,
            hash_done = CASE WHEN size != :size OR mtime != :mtime THEN 0 ELSE hash_done END,
            xxhash = CASE WHEN size != :size OR mtime != :mtime THEN NULL ELSE xxhash END,
            phash = CASE WHEN size != :size OR mtime != :mtime THEN NULL ELSE phash END,
            blur_score = CASE WHEN size != :size OR mtime != :mtime THEN NULL ELSE blur_score END,
            width = CASE WHEN size != :size OR mtime != :mtime THEN 0 ELSE width END,
            height = CASE WHEN size != :size OR mtime != :mtime THEN 0 ELSE height END,
            is_blank = CASE WHEN size != :size OR mtime != :mtime THEN 0 ELSE is_blank END,
            is_corrupt = CASE WHEN size != :size OR mtime != :mtime THEN 0 ELSE is_corrupt END,
            ocr_done = CASE WHEN size != :size OR mtime != :mtime THEN 0 ELSE ocr_done END,
            ai_done = CASE WHEN size != :size OR mtime != :mtime THEN 0 ELSE ai_done END,
            embedding_done = CASE WHEN size != :size OR mtime != :mtime THEN 0 ELSE embedding_done END,
            duplicate_group_id = CASE WHEN size != :size OR mtime != :mtime THEN NULL ELSE duplicate_group_id END,
            is_duplicate_best = CASE WHEN size != :size OR mtime != :mtime THEN 0 ELSE is_duplicate_best END
        WHERE path = :path
    """)
    suspend fun updateFromScanByPath(
        path: String,
        filename: String,
        parentDir: String,
        sourceType: String,
        cacheKey: String,
        size: Long,
        mtime: Long,
        lastSeen: Long,
        mime: String,
        isAnimated: Boolean,
    )

    @Transaction
    suspend fun insertBatch(images: List<ImageEntity>) {
        if (images.isEmpty()) return
        images.forEach { image ->
            val existing = getByPath(image.path)
            if (existing == null) {
                insert(
                    if (image.sourceType == "chatthumb") {
                        image.copy(status = "thumbnail_trash")
                    } else {
                        image
                    }
                )
            } else {
                updateFromScanByPath(
                    path = image.path,
                    filename = image.filename,
                    parentDir = image.parentDir,
                    sourceType = image.sourceType,
                    cacheKey = image.cacheKey,
                    size = image.size,
                    mtime = image.mtime,
                    lastSeen = image.lastSeen,
                    mime = image.mime,
                    isAnimated = image.isAnimated,
                )
            }
        }
    }

    // ---- 单条 / 路径查询 ----

    @Query("SELECT * FROM images WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): ImageEntity?

    @Query("SELECT * FROM images WHERE path = :path LIMIT 1")
    suspend fun getByPath(path: String): ImageEntity?

    /** 仅取必要字段做增量扫描 diff，避免装载整行 */
    @Query("SELECT id, path, size, mtime, last_seen FROM images")
    suspend fun listPathSizeMtime(): List<PathSizeMtime>

    data class PathSizeMtime(
        val id: Long,
        val path: String,
        val size: Long,
        val mtime: Long,
        val last_seen: Long,
    )

    @Query("""
        UPDATE images
        SET status = 'thumbnail_trash',
            hash_done = 0,
            ocr_done = 0,
            ai_done = 0,
            embedding_done = 0,
            variant_group = NULL,
            is_variant_primary = 0,
            duplicate_group_id = NULL,
            is_duplicate_best = 0
        WHERE source_type = 'chatthumb' AND status NOT IN ('deleted', 'quarantined')
    """)
    suspend fun markThumbnailsAsTrash()

    @Query("""
        SELECT COUNT(*) AS count, IFNULL(SUM(size), 0) AS bytes
        FROM images
        WHERE source_type = 'chatthumb' AND status = 'thumbnail_trash'
    """)
    fun observeThumbnailTrashSummary(): Flow<ThumbnailTrashSummary>

    @Query("""
        SELECT * FROM images
        WHERE source_type = 'chatthumb' AND status = 'thumbnail_trash'
        ORDER BY mtime DESC, id DESC
    """)
    fun observeThumbnailTrash(): Flow<List<ImageEntity>>

    @Query("""
        SELECT * FROM images
        WHERE source_type = 'chatthumb' AND status = 'thumbnail_trash'
        ORDER BY mtime DESC, id DESC
    """)
    suspend fun listThumbnailTrash(): List<ImageEntity>

    data class ThumbnailTrashSummary(
        val count: Int,
        val bytes: Long,
    )

    // ---- 时间线分页 ----

    /** 时间线视图：缩略图不进入图库。 */
    @Query("""
        SELECT * FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb'
        ORDER BY mtime DESC, id DESC
    """)
    fun pagingActiveTimeline(): PagingSource<Int, ImageEntity>

    /** 详情页左右滑动使用同一套时间线排序。 */
    @Query("""
        SELECT * FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb'
        ORDER BY mtime DESC, id DESC
    """)
    suspend fun listActiveTimeline(): List<ImageEntity>

    /** 收藏详情页左右滑动只在收藏时间线内切换。 */
    @Query("""
        SELECT * FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb' AND is_protected = 1
        ORDER BY mtime DESC, id DESC
    """)
    suspend fun listFavoriteTimeline(): List<ImageEntity>

    @Query("""
        SELECT * FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb'
        ORDER BY mtime DESC
        LIMIT :limit
    """)
    fun observeRecent(limit: Int = 50): Flow<List<ImageEntity>>

    // ---- 仪表盘统计 ----

    /** 主图库图片数（不含 chatthumb 缩略图） */
    @Query("SELECT COUNT(*) FROM images WHERE status = 'active' AND source_type <> 'chatthumb'")
    fun observeImageCount(): Flow<Int>

    @Query("SELECT IFNULL(SUM(size), 0) FROM images WHERE status = 'active' AND source_type <> 'chatthumb'")
    fun observeTotalSize(): Flow<Long>

    @Query("""
        SELECT source_type AS source, COUNT(*) AS count, IFNULL(SUM(size), 0) AS bytes
        FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb'
        GROUP BY source_type
    """)
    fun observeSourceBreakdown(): Flow<List<SourceBreakdownRow>>

    data class SourceBreakdownRow(
        val source: String,
        val count: Int,
        val bytes: Long,
    )

    @Query("""
        SELECT
            COUNT(*) AS totalImages,
            IFNULL(SUM(CASE WHEN hash_done = 1 THEN 1 ELSE 0 END), 0) AS hashDone,
            IFNULL(SUM(CASE WHEN ocr_done = 1 THEN 1 ELSE 0 END), 0) AS ocrDone,
            IFNULL(SUM(CASE WHEN ai_done = 1 THEN 1 ELSE 0 END), 0) AS aiDone,
            IFNULL(SUM(CASE WHEN embedding_done = 1 THEN 1 ELSE 0 END), 0) AS embeddingDone
        FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb'
    """)
    fun observeAnalysisProgress(): Flow<AnalysisProgressRow>

    data class AnalysisProgressRow(
        val totalImages: Int,
        val hashDone: Int,
        val ocrDone: Int,
        val aiDone: Int,
        val embeddingDone: Int,
    )

    @Query("""
        SELECT
            IFNULL(SUM(CASE WHEN is_corrupt = 1 THEN 1 ELSE 0 END), 0) AS corrupt,
            IFNULL(SUM(CASE WHEN is_blank = 1 THEN 1 ELSE 0 END), 0) AS blank,
            IFNULL(SUM(CASE WHEN is_animated = 1 THEN 1 ELSE 0 END), 0) AS animated,
            IFNULL(SUM(CASE WHEN is_protected = 1 THEN 1 ELSE 0 END), 0) AS favoriteCount,
            COUNT(DISTINCT duplicate_group_id) AS duplicateGroups,
            IFNULL(SUM(CASE WHEN duplicate_group_id IS NOT NULL AND is_duplicate_best = 0 THEN size ELSE 0 END), 0) AS reclaimableBytes
        FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb'
    """)
    fun observeImageHealth(): Flow<ImageHealthRow>

    data class ImageHealthRow(
        val corrupt: Int,
        val blank: Int,
        val animated: Int,
        val favoriteCount: Int,
        val duplicateGroups: Int,
        val reclaimableBytes: Long,
    )

    /** 已存在的 path 集合，用于增量扫描快速判重（一次性拉取） */
    @Query("SELECT path FROM images")
    suspend fun listAllPaths(): List<String>

    // ---- Phase 2：Hash / 质量 / 重复组 ----

    /** 找出待计算 hash 的活跃图片（按 mtime 倒序，最近的优先） */
    @Query("""
        SELECT * FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb' AND hash_done = 0
        ORDER BY mtime DESC
        LIMIT :limit
    """)
    suspend fun pendingHash(limit: Int = 200): List<ImageEntity>

    /** 待做质量分析的图片 */
    @Query("""
        SELECT * FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb' AND hash_done = 1
          AND blur_score IS NULL AND width = 0
        ORDER BY mtime DESC
        LIMIT :limit
    """)
    suspend fun pendingQuality(limit: Int = 200): List<ImageEntity>

    @Query("""
        UPDATE images
        SET xxhash = :xxhash, phash = :phash, hash_done = 1
        WHERE id = :id
    """)
    suspend fun saveHash(id: Long, xxhash: String?, phash: Long?)

    @Query("""
        UPDATE images
        SET width = :w, height = :h,
            blur_score = :blur, is_blank = :blank, is_corrupt = :corrupt
        WHERE id = :id
    """)
    suspend fun saveQuality(id: Long, w: Int, h: Int, blur: Float?, blank: Boolean, corrupt: Boolean)

    /** 重复组聚类辅助：按 xxhash 分组的 ID 列表（排除 hash 为 null） */
    @Query("""
        SELECT xxhash AS hash, GROUP_CONCAT(id) AS ids
        FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb' AND xxhash IS NOT NULL
        GROUP BY xxhash
        HAVING COUNT(*) > 1
    """)
    suspend fun xxhashGroups(): List<XxhashGroupRow>

    data class XxhashGroupRow(
        val hash: String,
        val ids: String,
    )

    @Query("SELECT IFNULL(MAX(duplicate_group_id), 0) FROM images")
    suspend fun maxDuplicateGroupId(): Long

    @Query("""
        UPDATE images
        SET duplicate_group_id = :groupId, is_duplicate_best = :isBest
        WHERE id = :id
    """)
    suspend fun assignDuplicate(id: Long, groupId: Long, isBest: Boolean)

    @Query("""
        UPDATE images
        SET duplicate_group_id = NULL, is_duplicate_best = 0
        WHERE duplicate_group_id IS NOT NULL OR is_duplicate_best = 1
    """)
    suspend fun clearDuplicateAssignments()

    /** 已有 pHash 的图片 */
    @Query("""
        SELECT id, phash FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb' AND phash IS NOT NULL
    """)
    suspend fun primaryPhashes(): List<PhashRow>

    data class PhashRow(
        val id: Long,
        val phash: Long?,
    )

    @Query("UPDATE images SET delete_score = :score WHERE id = :id")
    suspend fun updateDeleteScore(id: Long, score: Float)

    @Query("UPDATE images SET info_score = :score WHERE id = :id")
    suspend fun updateInfoScore(id: Long, score: Float)

    /** 删除分数高于阈值的候选 */
    @Query("""
        SELECT * FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb' AND delete_score >= :threshold
          AND is_protected = 0 AND (user_keep IS NULL OR user_keep = 0)
        ORDER BY delete_score DESC
        LIMIT :limit
    """)
    suspend fun getByDeleteScoreAbove(threshold: Float, limit: Int = 500): List<ImageEntity>

    /** 重复组列表（仅返回组 id 与图片数量） */
    @Query("""
        SELECT duplicate_group_id AS groupId, COUNT(*) AS count, IFNULL(SUM(size), 0) AS bytes
        FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb' AND duplicate_group_id IS NOT NULL
        GROUP BY duplicate_group_id
        ORDER BY count DESC
    """)
    suspend fun duplicateGroupSummary(): List<DuplicateGroupSummary>

    data class DuplicateGroupSummary(
        val groupId: Long,
        val count: Int,
        val bytes: Long,
    )

    @Query("""
        SELECT * FROM images
        WHERE duplicate_group_id = :groupId AND source_type <> 'chatthumb'
        ORDER BY is_duplicate_best DESC, size DESC
    """)
    suspend fun getByDuplicateGroup(groupId: Long): List<ImageEntity>

    @Query("UPDATE images SET user_keep = :keep WHERE id = :id")
    suspend fun setUserKeep(id: Long, keep: Boolean?)

    @Query("UPDATE images SET is_protected = :isFavorite WHERE id = :id")
    suspend fun setFavorite(id: Long, isFavorite: Boolean)

    @Query("""
        SELECT * FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb' AND is_protected = 1
        ORDER BY mtime DESC, id DESC
    """)
    fun pagingFavoriteTimeline(): PagingSource<Int, ImageEntity>

    @Query("""
        SELECT COUNT(*) FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb' AND is_protected = 1
    """)
    fun observeFavoriteCount(): Flow<Int>

    /** 已完成 hash 的图片，分页拉出来用于评分 / 全库再聚合 */
    @Query("""
        SELECT * FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb' AND hash_done = 1
          AND id > :afterId
        ORDER BY id
        LIMIT :limit
    """)
    suspend fun pagePrimaryHashed(afterId: Long, limit: Int = 500): List<ImageEntity>

    // ---- Phase 3：OCR ----

    /** 待 OCR 的图片（质量分析完成的优先） */
    @Query("""
        SELECT * FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb' AND ocr_done = 0
          AND is_corrupt = 0
          AND width >= 64 AND height >= 64
        ORDER BY mtime DESC
        LIMIT :limit
    """)
    suspend fun pendingOcr(limit: Int = 50): List<ImageEntity>

    @Query("UPDATE images SET ocr_done = 1 WHERE id = :id")
    suspend fun markOcrDone(id: Long)

    @Query("""
        SELECT * FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb' AND id IN (:ids)
    """)
    suspend fun getByIds(ids: List<Long>): List<ImageEntity>
}
