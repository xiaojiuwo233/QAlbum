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

    // ---- 扫描（目录剪枝管线）----

    /**
     * 取某个三字符目录下所有行的扫描判定字段（增量 diff 用，避免全表装载）。
     * [low] = "dir/"、[high] = "dir0"（'0' 是 '/' 的下一个 ASCII 字符），
     * 构成覆盖 "dir/..." 前缀的右开区间，走 path 索引做范围扫描。
     */
    @Query("""
        SELECT id, path, size, mtime, status, mime, is_animated AS isAnimated
        FROM images
        WHERE path >= :low AND path < :high
    """)
    suspend fun listScanRowsInPathRange(low: String, high: String): List<ScanRow>

    data class ScanRow(
        val id: Long,
        val path: String,
        val size: Long,
        val mtime: Long,
        val status: String,
        val mime: String,
        val isAnimated: Boolean,
    )

    /** 磁盘上已消失的活跃图片 → missing（图库查询只认 active，幽灵记录自动从图库消失） */
    @Query("UPDATE images SET status = 'missing' WHERE id IN (:ids) AND status = 'active'")
    suspend fun markMissingByIds(ids: List<Long>)

    /** 整个三字符目录消失时按 path 范围批量标记 missing */
    @Query("UPDATE images SET status = 'missing' WHERE path >= :low AND path < :high AND status = 'active'")
    suspend fun markMissingInPathRange(low: String, high: String)

    /** 文件以相同 size/mtime 重新出现：仅恢复 missing → active，不重置分析状态 */
    @Query("UPDATE images SET status = 'active' WHERE id IN (:ids) AND status = 'missing'")
    suspend fun reviveByIds(ids: List<Long>)

    /** 深度校验：仅修正 mime / 动图标记，不动分析状态 */
    @Query("UPDATE images SET mime = :mime, is_animated = :isAnimated WHERE id = :id")
    suspend fun fixMimeType(id: Long, mime: String, isAnimated: Boolean)

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

    /**
     * 批量插入新增记录（调用方已通过内存 path 快照确保这些 path 不在库中，故不会重复）。
     * 用 IGNORE 兜底极端并发下的冲突。chatthumb 的 status 由调用方在构造 entity 时设好。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(images: List<ImageEntity>)

    /**
     * 批量更新已存在记录的扫描字段（size/mtime 变化时由 [updateFromScanByPath] 自动重置分析状态）。
     * 整批包在一个事务里，避免逐条 update 各自 fsync。
     */
    @Transaction
    suspend fun updateBatchFromScan(images: List<ImageEntity>) {
        images.forEach { image ->
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

    // ---- 单条 / 路径查询 ----

    @Query("SELECT * FROM images WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): ImageEntity?

    @Query("SELECT * FROM images WHERE path = :path LIMIT 1")
    suspend fun getByPath(path: String): ImageEntity?

    /** 仅重置本地分析（hash/质量/重复/评分）标记与结果；OCR/AI/向量 不动 */
    @Query("""
        UPDATE images SET
            hash_done = 0, xxhash = NULL, phash = NULL,
            blur_score = NULL, width = 0, height = 0, is_blank = 0, is_corrupt = 0,
            duplicate_group_id = NULL, is_duplicate_best = 0,
            delete_score = 0, info_score = 0
        WHERE source_type <> 'chatthumb' AND status NOT IN ('deleted', 'quarantined')
    """)
    suspend fun resetLocalAnalysis()

    /** 重置全部分析标记（含 OCR/AI/向量）；调用方需同时清空 ocr_text / ocr_fts / image_embeddings 表 */
    @Query("""
        UPDATE images SET
            hash_done = 0, xxhash = NULL, phash = NULL,
            blur_score = NULL, width = 0, height = 0, is_blank = 0, is_corrupt = 0,
            duplicate_group_id = NULL, is_duplicate_best = 0,
            delete_score = 0, info_score = 0,
            ocr_done = 0, ai_done = 0, embedding_done = 0
        WHERE source_type <> 'chatthumb' AND status NOT IN ('deleted', 'quarantined')
    """)
    suspend fun resetAllAnalysis()

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

    /** 在给定 id 集合里筛出仍处于缩略图回收站的行（多选删除用）；调用方需分块传入 */
    @Query("""
        SELECT * FROM images
        WHERE source_type = 'chatthumb' AND status = 'thumbnail_trash' AND id IN (:ids)
    """)
    suspend fun getThumbnailTrashByIds(ids: List<Long>): List<ImageEntity>

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

    /** 时间线视图（旧到新）：mtime 升序。 */
    @Query("""
        SELECT * FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb'
        ORDER BY mtime ASC, id ASC
    """)
    fun pagingActiveTimelineByDateAsc(): PagingSource<Int, ImageEntity>

    /** 时间线视图（大到小）：size 降序，走 index_images_size 避免 25w 图 filesort。 */
    @Query("""
        SELECT * FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb'
        ORDER BY size DESC, id DESC
    """)
    fun pagingActiveTimelineBySize(): PagingSource<Int, ImageEntity>

    @Query("""
        SELECT
            CAST(strftime('%Y', mtime / 1000, 'unixepoch', 'localtime') AS INTEGER) * 1000
                + CAST(strftime('%j', mtime / 1000, 'unixepoch', 'localtime') AS INTEGER) AS bucketKey,
            COUNT(*) AS count
        FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb'
        GROUP BY bucketKey
    """)
    fun observeTimelineDateBucketCounts(): Flow<List<BucketCountRow>>

    @Query("""
        SELECT
            CASE
                WHEN size >= 10485760 THEN 100000
                WHEN size >= 1048576 THEN 10000 + (size / 1048576)
                WHEN size >= 102400 THEN 1000 + (size / 102400)
                WHEN size >= 10240 THEN 100 + (size / 10240)
                ELSE 0
            END AS bucketKey,
            COUNT(*) AS count
        FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb'
        GROUP BY bucketKey
    """)
    fun observeTimelineSizeBucketCounts(): Flow<List<BucketCountRow>>

    @Query("""
        SELECT
            CAST(strftime('%Y', mtime / 1000, 'unixepoch', 'localtime') AS INTEGER) * 1000
                + CAST(strftime('%j', mtime / 1000, 'unixepoch', 'localtime') AS INTEGER) AS bucketKey,
            COUNT(*) AS count
        FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb' AND is_protected = 1
        GROUP BY bucketKey
    """)
    fun observeFavoriteDateBucketCounts(): Flow<List<BucketCountRow>>

    data class BucketCountRow(
        val bucketKey: Long,
        val count: Int,
    )

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

    /**
     * 详情页时间线：**只取 id**（轻量），用于左右滑动的分页定位。
     * 绝不能用 listActiveTimeline 那样的 SELECT * 全量装载（会卡死详情页）。
     */
    @Query("""
        SELECT id FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb'
        ORDER BY mtime DESC, id DESC
    """)
    suspend fun timelineIds(): List<Long>

    /** 详情页时间线 id（旧到新），与 [pagingActiveTimelineByDateAsc] 排序一致。 */
    @Query("""
        SELECT id FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb'
        ORDER BY mtime ASC, id ASC
    """)
    suspend fun timelineIdsByDateAsc(): List<Long>

    /** 详情页时间线 id（大到小），与 [pagingActiveTimelineBySize] 排序一致。 */
    @Query("""
        SELECT id FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb'
        ORDER BY size DESC, id DESC
    """)
    suspend fun timelineIdsBySize(): List<Long>

    /** 收藏详情页时间线：只取 id。 */
    @Query("""
        SELECT id FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb' AND is_protected = 1
        ORDER BY mtime DESC, id DESC
    """)
    suspend fun favoriteTimelineIds(): List<Long>

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

    /**
     * 「已清理」累计：经本 app 永久删除的图片张数与原始字节总和。
     * status='deleted' 仅由三条永久删除路径写入（直接删除 / 回收站清空 / 缩略图清空），
     * 扫描器对磁盘消失的文件用独立的 'missing' 状态、从不写 'deleted'，故此聚合精确等于
     * 「本 app 永久清理量」，且天然涵盖功能上线前已删的历史；不加 source_type 过滤＝全部都算。
     */
    @Query("SELECT COUNT(*) AS count, IFNULL(SUM(size), 0) AS bytes FROM images WHERE status = 'deleted'")
    fun observeCleanedStats(): Flow<CleanedStatsRow>

    data class CleanedStatsRow(val count: Int, val bytes: Long)

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
            IFNULL(SUM(CASE WHEN embedding_done = 1 THEN 1 ELSE 0 END), 0) AS embeddingDone,
            IFNULL(SUM(CASE WHEN hash_done = 0
                            OR (ocr_done = 0 AND is_corrupt = 0 AND width >= 64 AND height >= 64)
                       THEN 1 ELSE 0 END), 0) AS pendingBase
        FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb'
    """)
    fun observeAnalysisProgress(): Flow<AnalysisProgressRow>

    /**
     * 分析完成度聚合。[pendingBase] = 仍需做「基础分析」(哈希/质量 或 OCR)的图片数：
     * 与 [pendingHash] / [pendingOcr] 的筛选条件保持一致（OCR 排除损坏图与过小图）。
     * 「已分析」= totalImages - pendingBase，用于总进度条与按钮三态。
     */
    data class AnalysisProgressRow(
        val totalImages: Int,
        val hashDone: Int,
        val ocrDone: Int,
        val aiDone: Int,
        val embeddingDone: Int,
        val pendingBase: Int,
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

    // ---- Phase 2：Hash / 质量 / 重复组 ----

    /** 找出待计算 hash 的活跃图片（按 mtime 倒序，最近的优先） */
    @Query("""
        SELECT * FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb' AND hash_done = 0
        ORDER BY (CASE WHEN :oldestFirst THEN mtime ELSE -mtime END) ASC
        LIMIT :limit
    """)
    suspend fun pendingHash(limit: Int, oldestFirst: Boolean): List<ImageEntity>

    /** 待 hash（即待「hash+质量」合并分析）的总数，用于进度条总量 */
    @Query("""
        SELECT COUNT(*) FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb' AND hash_done = 0
    """)
    suspend fun countPendingHash(): Int

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
        ORDER BY (CASE WHEN :oldestFirst THEN mtime ELSE -mtime END) ASC
        LIMIT :limit
    """)
    suspend fun pendingOcr(limit: Int, oldestFirst: Boolean): List<ImageEntity>

    /** 待 AI 打标签：已 hash、未 ai_done、未损坏的主图，按排序取前 limit 张 */
    @Query("""
        SELECT * FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb'
          AND hash_done = 1 AND ai_done = 0 AND is_corrupt = 0
        ORDER BY (CASE WHEN :oldestFirst THEN mtime ELSE -mtime END) ASC
        LIMIT :limit
    """)
    suspend fun pendingAiTag(limit: Int, oldestFirst: Boolean): List<ImageEntity>

    /** 待生成向量：已 hash、未 embedding_done、未损坏的主图，按排序取前 limit 张 */
    @Query("""
        SELECT * FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb'
          AND hash_done = 1 AND embedding_done = 0 AND is_corrupt = 0
        ORDER BY (CASE WHEN :oldestFirst THEN mtime ELSE -mtime END) ASC
        LIMIT :limit
    """)
    suspend fun pendingEmbedding(limit: Int, oldestFirst: Boolean): List<ImageEntity>

    @Query("UPDATE images SET ocr_done = 1 WHERE id = :id")
    suspend fun markOcrDone(id: Long)

    @Query("""
        SELECT * FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb' AND id IN (:ids)
    """)
    suspend fun getByIds(ids: List<Long>): List<ImageEntity>

    /** 求给定 id 集合的原始字节总和；调用方需分块传入，避免触及 SQLite 变量上限 */
    @Query("SELECT IFNULL(SUM(size), 0) FROM images WHERE id IN (:ids)")
    suspend fun sumSizeByIds(ids: List<Long>): Long

    // ---- 清理候选池（方案 A：按维度规则筛选）----

    /**
     * 清理候选池：已排除收藏 / 用户保留的图，只取规则判定所需的最小字段，
     * 避免把整行装载进内存。具体命中哪个维度由
     * [com.xjw.qalbum.service.cleanup.CleanupRules] 在内存判定。
     *
     * 按分析顺序（mtime 升/降序）排序，让「生成审核清理」遵守与分析一致的早到晚/晚到早顺序，
     * 从而每次只纳入对应方向的前 N 张候选。
     */
    @Query("""
        SELECT id,
               width,
               height,
               blur_score AS blurScore,
               size,
               mtime,
               is_blank AS isBlank,
               is_corrupt AS isCorrupt,
               duplicate_group_id AS duplicateGroupId,
               is_duplicate_best AS isDuplicateBest,
               delete_score AS deleteScore
        FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb'
          AND is_protected = 0 AND (user_keep IS NULL OR user_keep = 0)
        ORDER BY (CASE WHEN :oldestFirst THEN mtime ELSE -mtime END) ASC
    """)
    suspend fun cleanupCandidatePool(oldestFirst: Boolean): List<CleanupSignalRow>
}

/** 清理判定所需的最小图片信号投影（由 [ImageDao.cleanupCandidatePool] 用列别名填充） */
data class CleanupSignalRow(
    val id: Long,
    val width: Int,
    val height: Int,
    val blurScore: Float?,
    val size: Long,
    val mtime: Long,
    val isBlank: Boolean,
    val isCorrupt: Boolean,
    val duplicateGroupId: Long?,
    val isDuplicateBest: Boolean,
    val deleteScore: Float,
)
