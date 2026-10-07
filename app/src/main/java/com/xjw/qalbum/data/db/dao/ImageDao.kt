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

    @Query("SELECT * FROM images WHERE id IN (:ids)")
    suspend fun getAnyByIds(ids: List<Long>): List<ImageEntity>

    @Query("SELECT * FROM images WHERE path IN (:paths)")
    suspend fun getAnyByPaths(paths: List<String>): List<ImageEntity>

    @Query("""SELECT * FROM images WHERE id IN (:ids) AND status IN ('active','thumbnail_trash')
        AND is_protected=0 AND (user_keep IS NULL OR user_keep=0)""")
    suspend fun getLinkedCleanupEligible(ids: List<Long>): List<ImageEntity>

    @Query("""UPDATE images SET status='quarantine_pending' WHERE id IN (:ids)
        AND status IN ('active','thumbnail_trash') AND is_protected=0 AND (user_keep IS NULL OR user_keep=0)""")
    suspend fun markLinkedQuarantinePending(ids: List<Long>): Int

    // ---- 写入 ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(image: ImageEntity): Long

    @Update
    suspend fun update(image: ImageEntity)

    @Query("UPDATE images SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)

    /** 批量改状态（移入回收站时一批标记 quarantined，避免逐条事务各自 fsync）；调用方分块控制变量上限。 */
    @Query("UPDATE images SET status = :status WHERE id IN (:ids)")
    suspend fun updateStatusByIds(ids: List<Long>, status: String)

    /**
     * 守卫版批量改状态：仅迁移仍处于 [fromStatus] 的行，拒绝改写 deleted/quarantined 等
     * 已被其他路径迁走的行（并发场景下用装载快照回写必须走此版本）。返回生效行数。
     */
    @Query("UPDATE images SET status = :status WHERE id IN (:ids) AND status = :fromStatus")
    suspend fun updateStatusByIdsFrom(ids: List<Long>, status: String, fromStatus: String): Int

    /** 直接永久删除路径（原位删除，行必为 active/missing）用的无守卫标记。回收站/缩略图清空必须走 [markDeletedFromStatus]。 */
    @Query("""
        UPDATE images
        SET status = 'deleted', deleted_at = :deletedAt,
            path = 'qalbum-internal://deleted/' || id
        WHERE id IN (:ids)
    """)
    suspend fun markDeleted(ids: List<Long>, deletedAt: Long)

    /**
     * 守卫版批量标记 deleted：仅当行仍处于 [fromStatus]（'quarantined' / 'thumbnail_trash'）时生效。
     * 清空回收站逐页 purge 期间用户点了恢复时，已恢复为 active 的行不会被拿着旧页快照的
     * purge 误标 deleted（否则文件存在但从图库永久消失，且虚增「已清理」统计）。返回生效行数。
     */
    @Query("""
        UPDATE images
        SET status = 'deleted', deleted_at = :deletedAt,
            path = 'qalbum-internal://deleted/' || id
        WHERE id IN (:ids) AND status = :fromStatus
    """)
    suspend fun markDeletedFromStatus(ids: List<Long>, deletedAt: Long, fromStatus: String): Int

    /** 文件移动前的原子 claim：同时重验收藏/用户保留，真实 path 暂时保留以阻止扫描重复插入。 */
    @Query("""
        UPDATE images SET status = 'quarantine_pending'
        WHERE id IN (:ids) AND status = 'active'
          AND is_protected = 0 AND (user_keep IS NULL OR user_keep = 0)
    """)
    suspend fun markQuarantinePending(ids: List<Long>): Int

    /** 文件已安置后释放真实 path，让相同 Cache 路径的新内容可作为新图片入库。 */
    @Query("""
        UPDATE images
        SET status = 'quarantined', path = 'qalbum-internal://quarantine/' || id
        WHERE id IN (:ids) AND status = 'quarantine_pending'
    """)
    suspend fun finalizeQuarantine(ids: List<Long>): Int

    @Query("UPDATE images SET status = CASE WHEN source_type='chatthumb' THEN 'thumbnail_trash' ELSE 'active' END WHERE id IN (:ids) AND status = 'quarantine_pending'")
    suspend fun rollbackQuarantinePending(ids: List<Long>): Int

    /** 恢复前先在唯一索引中占住目标 path；失败/进程中断可由持久化日志回滚或完成。 */
    @Query("""
        UPDATE images SET status = 'restore_pending', path = :targetPath
        WHERE id = :id AND status = 'quarantined'
    """)
    suspend fun markRestorePending(id: Long, targetPath: String): Int

    @Query("""
        UPDATE images SET status = CASE WHEN source_type='chatthumb' THEN 'thumbnail_trash' ELSE 'active' END, last_seen = :lastSeen
        WHERE id = :id AND status = 'restore_pending'
    """)
    suspend fun finalizeRestore(id: Long, lastSeen: Long): Int

    @Query("""
        UPDATE images
        SET status = 'quarantined', path = 'qalbum-internal://quarantine/' || id
        WHERE id = :id AND status = 'restore_pending'
    """)
    suspend fun rollbackRestorePending(id: Long): Int

    /** 旧版本终态行仍占真实路径时先迁到内部命名空间，随后扫描才能插入当前文件。 */
    @Query("""
        UPDATE images
        SET path = 'qalbum-internal://' || status || '/' || id
        WHERE id IN (:ids) AND status IN ('deleted', 'quarantined')
    """)
    suspend fun retireTerminalPaths(ids: List<Long>): Int

    @Query("UPDATE images SET last_seen = :ts WHERE id = :id")
    suspend fun touchLastSeen(id: Long, ts: Long)

    /** keyset 分页取 status='deleted' 行 id：存量派生数据（OCR/标签/向量/排斥）的一次性清理用。 */
    @Query("SELECT id FROM images WHERE status = 'deleted' AND id > :afterId ORDER BY id LIMIT :limit")
    suspend fun deletedIdsPaged(afterId: Long, limit: Int): List<Long>

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

    /**
     * 磁盘上已消失的活跃图片 → missing（图库查询只认 active，幽灵记录自动从图库消失）。
     * `last_seen < :scanStart` 守卫：回收站恢复会在成功事务内 touchLastSeen（恢复时刻），
     * 进行中的扫描批次拿的是恢复前的文件快照，不得把刚恢复的行覆盖成 missing。
     */
    @Query("UPDATE images SET status = 'missing' WHERE id IN (:ids) AND status = 'active' AND last_seen < :scanStart")
    suspend fun markMissingByIds(ids: List<Long>, scanStart: Long)

    /** 整个三字符目录消失时按 path 范围批量标记 missing（last_seen 守卫同 [markMissingByIds]） */
    @Query("UPDATE images SET status = 'missing' WHERE path >= :low AND path < :high AND status = 'active' AND last_seen < :scanStart")
    suspend fun markMissingInPathRange(low: String, high: String, scanStart: Long)

    /** 文件以相同 size/mtime 重新出现：仅恢复 missing → active，不重置分析状态 */
    @Query("UPDATE images SET status = 'active' WHERE id IN (:ids) AND status = 'missing'")
    suspend fun reviveByIds(ids: List<Long>)

    /** 深度校验：仅修正 mime / 动图标记，不动分析状态 */
    @Query("UPDATE images SET mime = :mime, is_animated = :isAnimated WHERE id = :id")
    suspend fun fixMimeType(id: Long, mime: String, isAnimated: Boolean)

    /**
     * WHERE 的 status 守卫是硬约束：status='deleted' 是「已清理」统计的纯净数据源、quarantined 行
     * 的原路径文件归回收站流程自管——同路径上重新出现的新文件（如 QQ 重新下载同名缓存）绝不能
     * 让扫描改写这两类行的 size/mtime，否则统计被污染且该文件永远不可见。
     */
    @Query("""
        UPDATE images
        SET filename = :filename,
            parent_dir = :parentDir,
            source_type = :sourceType,
            cache_key = :cacheKey,
            size = :size,
            mtime = :mtime,
            date_bucket = CAST(strftime('%Y', :mtime / 1000, 'unixepoch', 'localtime') AS INTEGER) * 1000
                + CAST(strftime('%j', :mtime / 1000, 'unixepoch', 'localtime') AS INTEGER),
            last_seen = :lastSeen,
            mime = :mime,
            is_animated = :isAnimated,
            status = CASE
                WHEN :sourceType = 'chatthumb' THEN 'thumbnail_trash'
                WHEN status = 'missing' THEN 'active'
                ELSE status
            END,
            hash_done = CASE WHEN size != :size OR mtime != :mtime THEN 0 ELSE hash_done END,
            xxhash = CASE WHEN size != :size OR mtime != :mtime THEN NULL ELSE xxhash END,
            phash = CASE WHEN size != :size OR mtime != :mtime THEN NULL ELSE phash END,
            block_hash = CASE WHEN size != :size OR mtime != :mtime THEN NULL ELSE block_hash END,
            feature_grid = CASE WHEN size != :size OR mtime != :mtime THEN NULL ELSE feature_grid END,
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
        WHERE path = :path AND status IN ('active', 'missing', 'thumbnail_trash')
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
            hash_done = 0, xxhash = NULL, phash = NULL, block_hash = NULL, feature_grid = NULL,
            blur_score = NULL, width = 0, height = 0, is_blank = 0, is_corrupt = 0,
            duplicate_group_id = NULL, is_duplicate_best = 0,
            delete_score = 0, info_score = 0
        WHERE source_type <> 'chatthumb' AND status IN ('active', 'missing')
    """)
    suspend fun resetLocalAnalysis()

    /** 重置全部分析标记（含 OCR/AI/向量）；调用方需同时清空 ocr_text / ocr_fts / image_embeddings 表 */
    @Query("""
        UPDATE images SET
            hash_done = 0, xxhash = NULL, phash = NULL, block_hash = NULL, feature_grid = NULL,
            blur_score = NULL, width = 0, height = 0, is_blank = 0, is_corrupt = 0,
            duplicate_group_id = NULL, is_duplicate_best = 0,
            delete_score = 0, info_score = 0,
            ocr_done = 0, ai_done = 0, embedding_done = 0
        WHERE source_type <> 'chatthumb' AND status IN ('active', 'missing')
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
    fun pagingThumbnailTrash(): PagingSource<Int, ImageEntity>

    @Query("""
        SELECT COUNT(*) FROM images
        WHERE source_type = 'chatthumb' AND status = 'thumbnail_trash'
    """)
    suspend fun countThumbnailTrash(): Int

    /**
     * 缩略图回收站分页拉取（keyset 分页，id 升序游标）：一键清理时逐页取，每页 ≤ [limit] 行，
     * 单次 cursor 远低于 CursorWindow 2MB 上限，避免早期 `listThumbnailTrash()` 一次性 `SELECT *`
     * 全量物化导致 `Couldn't read row N, col 0 from CursorWindow` 崩溃。删成功的行 status 改为
     * 'deleted' 后自然从后续页排除，故固定从 id 升序推进不漏不重。
     */
    @Query("""
        SELECT * FROM images
        WHERE source_type = 'chatthumb' AND status = 'thumbnail_trash' AND id > :afterId
        ORDER BY id ASC
        LIMIT :limit
    """)
    suspend fun listThumbnailTrashPaged(afterId: Long, limit: Int): List<ImageEntity>

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

    /**
     * 日期分桶计数：bucketKey = year*1000 + dayOfYear（预计算列 date_bucket，入库时写好）。
     * 对预计算整列 GROUP BY 走 (status, source_type, date_bucket, is_protected) 覆盖索引，
     * 免去早期版本 200w 行逐行 2 次 strftime 的全表聚合（单次 3-10s、扫描期间背靠背重跑）。
     */
    @Query("""
        SELECT date_bucket AS bucketKey, COUNT(*) AS count
        FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb'
        GROUP BY date_bucket
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
        SELECT date_bucket AS bucketKey, COUNT(*) AS count
        FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb' AND is_protected = 1
        GROUP BY date_bucket
    """)
    fun observeFavoriteDateBucketCounts(): Flow<List<BucketCountRow>>

    data class BucketCountRow(
        val bucketKey: Long,
        val count: Int,
    )

    // 详情页时间线已迁窗口 API（timelineRank* + timelineIdsBefore/After*）：
    // 200w 行全量 id 装载（旧 timelineIds 系列）会阻塞详情页数秒并常驻数十 MB 装箱内存，已删除。

    /** 收藏详情页时间线：只取 id。 */
    @Query("""
        SELECT id FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb' AND is_protected = 1
        ORDER BY mtime DESC, id DESC
    """)
    suspend fun favoriteTimelineIds(): List<Long>

    // ---- 时间线定位 / 窗口（keyset，替代全量 id 装载）----
    //
    // 详情页左右滑动的定位范式：先用 timelineRank* 拿当前图的序号（COUNT，走覆盖索引），
    // 再用 timelineIds{After,Before}* 按排序键取邻域窗口页，滑动到窗口边缘时续取下一页。
    // DATE_ASC 排序无需独立方法：after(asc) 即 [timelineIdsBeforeDateDesc]、before(asc) 即
    // [timelineIdsAfterDateDesc]，rank(asc) = 总数 - rankDesc - 1 或直接用 [timelineRankDateAsc]。

    /** 主时间线总数（suspend 一次性查询；Flow 版见 [observeImageCount]） */
    @Query("SELECT COUNT(*) FROM images WHERE status = 'active' AND source_type <> 'chatthumb'")
    suspend fun countActiveTimeline(): Int

    /** 「新到旧」时间线中给定排序键的 rank（0-based，= 排在它前面的行数） */
    @Query("""
        SELECT COUNT(*) FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb'
          AND (mtime > :mtime OR (mtime = :mtime AND id > :id))
    """)
    suspend fun timelineRankDateDesc(mtime: Long, id: Long): Int

    /** 「旧到新」时间线中给定排序键的 rank（0-based） */
    @Query("""
        SELECT COUNT(*) FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb'
          AND (mtime < :mtime OR (mtime = :mtime AND id < :id))
    """)
    suspend fun timelineRankDateAsc(mtime: Long, id: Long): Int

    /** 「大到小」时间线中给定排序键的 rank（0-based） */
    @Query("""
        SELECT COUNT(*) FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb'
          AND (size > :size OR (size = :size AND id > :id))
    """)
    suspend fun timelineRankSizeDesc(size: Long, id: Long): Int

    /** 「新到旧」时间线中排序键之后（更旧方向）的一页 id，不含键自身 */
    @Query("""
        SELECT id FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb'
          AND (mtime < :mtime OR (mtime = :mtime AND id < :id))
        ORDER BY mtime DESC, id DESC
        LIMIT :limit
    """)
    suspend fun timelineIdsAfterDateDesc(mtime: Long, id: Long, limit: Int): List<Long>

    /** 「新到旧」时间线中排序键之前（更新方向）的一页 id：返回序为离键近→远，调用方按需 reverse */
    @Query("""
        SELECT id FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb'
          AND (mtime > :mtime OR (mtime = :mtime AND id > :id))
        ORDER BY mtime ASC, id ASC
        LIMIT :limit
    """)
    suspend fun timelineIdsBeforeDateDesc(mtime: Long, id: Long, limit: Int): List<Long>

    /** 「大到小」时间线中排序键之后（更小方向）的一页 id，不含键自身 */
    @Query("""
        SELECT id FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb'
          AND (size < :size OR (size = :size AND id < :id))
        ORDER BY size DESC, id DESC
        LIMIT :limit
    """)
    suspend fun timelineIdsAfterSizeDesc(size: Long, id: Long, limit: Int): List<Long>

    /** 「大到小」时间线中排序键之前（更大方向）的一页 id：返回序为离键近→远，调用方按需 reverse */
    @Query("""
        SELECT id FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb'
          AND (size > :size OR (size = :size AND id > :id))
        ORDER BY size ASC, id ASC
        LIMIT :limit
    """)
    suspend fun timelineIdsBeforeSizeDesc(size: Long, id: Long, limit: Int): List<Long>

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

    /**
     * 「今日已清理」：deleted_at 落在 [since, now] 内的永久删除张数与字节。
     * deleted_at 由三条永久删除路径写入；功能上线前删除的历史行 deleted_at 为 NULL，不满足 >= since
     * 而被自动排除，符合「今日」语义。复用 [CleanedStatsRow]。
     */
    @Query("SELECT COUNT(*) AS count, IFNULL(SUM(size), 0) AS bytes FROM images WHERE status = 'deleted' AND deleted_at >= :since")
    fun observeCleanedStatsSince(since: Long): Flow<CleanedStatsRow>

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
                            OR ((block_hash IS NULL OR feature_grid IS NULL) AND is_corrupt = 0)
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

    /**
     * 「已分析」= 已完成全部**当前需要**的分析阶段的图片数（与图库左下角完成角标同口径）。
     * 入参为各阶段是否「需要」（由调用方按设置开关 + AI 服务可用性算好）。
     * hash 判定必须与 [pendingHashIds] 完全一致（含 block_hash/feature_grid 回填条件），
     * 否则 v8/v9 升级用户的存量回填永远不会被「继续分析」按钮触发。
     * 对损坏 / 过小图宽容：这些图本就被对应阶段跳过（见 pendingOcr/AiTag/Embedding 的筛选），
     * 视作该阶段「无需再做」，否则进度永远到不了 100%、按钮也永远点不亮「已全部分析」。
     */
    @Query("""
        SELECT COUNT(*) FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb'
          AND hash_done = 1
          AND ((block_hash IS NOT NULL AND feature_grid IS NOT NULL) OR is_corrupt = 1)
          AND (:ocrRequired = 0 OR ocr_done = 1 OR is_corrupt = 1 OR width < 64 OR height < 64)
          AND (:aiRequired = 0 OR ai_done = 1 OR is_corrupt = 1)
          AND (:embRequired = 0 OR embedding_done = 1 OR is_corrupt = 1)
    """)
    fun observeFullyAnalyzedCount(
        ocrRequired: Boolean,
        aiRequired: Boolean,
        embRequired: Boolean,
    ): Flow<Int>

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

    /**
     * 待 hash（含质量）阶段的图片 **id**，按分析顺序排序、取前 [limit] 个（仅 id，轻量）。
     * 管线先取 id 列表（一次排序），再用 [getByIds] 按块装载实体处理——内存恒定、不重排、
     * 不会因失败图反复重查（与 [timelineIds] 的「只取 id」轻量范式一致）。
     *
     * 各 pending 查询均拆成 ASC/DESC 两个常量排序版本：`ORDER BY (CASE …)` 表达式排序会让
     * SQLite 无法用 mtime 索引按序扫描并在凑满 LIMIT 后提前终止，退化成 200w 行全表扫描
     * + temp B-tree 排序（每轮 4 个阶段各付一次 10~40s）。
     */
    suspend fun pendingHashPage(
        limit: Int,
        oldestFirst: Boolean,
        afterMtime: Long,
        afterId: Long,
    ): List<PendingAnalysisRow> = if (oldestFirst) {
        pendingHashPageAsc(limit, afterMtime, afterId)
    } else {
        pendingHashPageDesc(limit, afterMtime, afterId)
    }

    @Query("""
        SELECT id, mtime FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb'
          AND (hash_done = 0 OR ((block_hash IS NULL OR feature_grid IS NULL) AND is_corrupt = 0))
          AND (mtime > :afterMtime OR (mtime = :afterMtime AND id > :afterId))
        ORDER BY mtime ASC, id ASC
        LIMIT :limit
    """)
    suspend fun pendingHashPageAsc(limit: Int, afterMtime: Long, afterId: Long): List<PendingAnalysisRow>

    @Query("""
        SELECT id, mtime FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb'
          AND (hash_done = 0 OR ((block_hash IS NULL OR feature_grid IS NULL) AND is_corrupt = 0))
          AND (mtime < :afterMtime OR (mtime = :afterMtime AND id < :afterId))
        ORDER BY mtime DESC, id DESC
        LIMIT :limit
    """)
    suspend fun pendingHashPageDesc(limit: Int, afterMtime: Long, afterId: Long): List<PendingAnalysisRow>

    /** 待 hash（即待「hash+质量」合并分析）的总数，用于进度条总量；含待回填 block_hash 的存量图 */
    @Query("""
        SELECT COUNT(*) FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb'
          AND (hash_done = 0 OR ((block_hash IS NULL OR feature_grid IS NULL) AND is_corrupt = 0))
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

    /**
     * 合并 hash + 质量为单条 UPDATE：每图一次写（原是 saveHash + saveQuality 两次写两个事务）。
     * WHERE 带乐观校验（size/mtime 取自装载实体时的快照）：文件在「装载 → 读字节计算 → 写回」的
     * 块级窗口内被重写（聊天缓存重新下载是常态）、扫描已重置分析字段时，本条陈旧结果直接落空
     * （不匹配 0 行），该图留在 pendingHashIds 待下轮重算——否则旧内容的 hash 会永久挂到新文件上，
     * 喂给去重聚类可能把非重复图聚成重复组进而被清理误删。
     */
    @Query("""
        UPDATE images
        SET xxhash = :xxhash, phash = :phash, block_hash = :blockHash, feature_grid = :featureGrid, hash_done = 1,
            width = :w, height = :h,
            blur_score = :blur, is_blank = :blank, is_corrupt = :corrupt
        WHERE id = :id AND size = :expectedSize AND mtime = :expectedMtime
    """)
    suspend fun saveHashQuality(
        id: Long,
        xxhash: String?,
        phash: Long?,
        blockHash: ByteArray?,
        featureGrid: ByteArray?,
        w: Int,
        h: Int,
        blur: Float?,
        blank: Boolean,
        corrupt: Boolean,
        expectedSize: Long,
        expectedMtime: Long,
    )

    /**
     * 整块 hash+质量结果包在一个事务里写回：20w 图原本 40w 个独立事务（各自 fsync、
     * 还在 4 个并发 worker 间争抢写锁），按块（[com.xjw.qalbum.service.analysis] 的 HASH_CHUNK）
     * 批写后降到约千级事务。与扫描的 [updateBatchFromScan] 同范式。
     */
    @Transaction
    suspend fun saveHashQualityBatch(rows: List<HashQualityWrite>) {
        for (r in rows) {
            saveHashQuality(
                r.id, r.xxhash, r.phash, r.blockHash, r.featureGrid, r.width, r.height, r.blur, r.blank, r.corrupt,
                r.expectedSize, r.expectedMtime,
            )
        }
    }

    /** 重复组聚类辅助：按 xxhash 分组的 ID 列表（排除 hash 为 null） */
    @Query("""
        SELECT xxhash AS hash, GROUP_CONCAT(id) AS ids
        FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb' AND xxhash IS NOT NULL
        GROUP BY xxhash, size
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

    /**
     * 重复聚类投影的 keyset 分页变体（id 升序游标）：聚类器逐页拉、按 phash 前缀桶分片进内存，
     * 峰值内存 = 单页 + 活跃桶，替代全量装载。精确重复（同 xxhash）另有 SQL 侧 [xxhashGroups]
     * 只装载冲突组，无需经过本投影。
     */
    @Query("""
        SELECT id, xxhash, phash, block_hash AS blockHash, feature_grid AS featureGrid, width, height, size, is_blank AS isBlank, is_animated AS isAnimated FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb'
          AND (xxhash IS NOT NULL OR phash IS NOT NULL)
          AND id > :afterId
        ORDER BY id
        LIMIT :limit
    """)
    suspend fun dedupProjectionPaged(afterId: Long, limit: Int): List<DedupRow>

    /** 批量写回重复分配（一个事务，避免逐图 update 各起一次写锁） */
    @Transaction
    suspend fun assignDuplicates(items: List<DuplicateAssignment>) {
        items.forEach { assignDuplicate(it.id, it.groupId, it.isBest) }
    }

    /**
     * 原子替换全部重复分配：清空旧分配 + 写入新分配在**同一事务**，
     * 避免查重写回窗口内其他读者看到「已清空未重写」的半旧半新状态。
     */
    @Transaction
    suspend fun replaceDuplicateAssignments(items: List<DuplicateAssignment>) {
        clearDuplicateAssignments()
        assignDuplicates(items)
    }

    /**
     * 所有 active 重复图（属于某重复组）；「审核全部重复」一次性纳入用，窄投影、无 LIMIT。
     * 拆 ASC/DESC 常量排序（同 [pendingHashIds]），避免 CASE 表达式排序失去 mtime 索引。
     */
    suspend fun allDuplicateRows(oldestFirst: Boolean): List<DuplicateGroupRow> =
        if (oldestFirst) allDuplicateRowsAsc() else allDuplicateRowsDesc()

    @Query("""
        SELECT id, duplicate_group_id AS duplicateGroupId, size
        FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb'
          AND is_protected = 0 AND (user_keep IS NULL OR user_keep = 0)
          AND duplicate_group_id IS NOT NULL
        ORDER BY mtime ASC, id ASC
    """)
    suspend fun allDuplicateRowsAsc(): List<DuplicateGroupRow>

    @Query("""
        SELECT id, duplicate_group_id AS duplicateGroupId, size
        FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb'
          AND is_protected = 0 AND (user_keep IS NULL OR user_keep = 0)
          AND duplicate_group_id IS NOT NULL
        ORDER BY mtime DESC, id DESC
    """)
    suspend fun allDuplicateRowsDesc(): List<DuplicateGroupRow>

    @Query("UPDATE images SET delete_score = :score WHERE id = :id")
    suspend fun updateDeleteScore(id: Long, score: Float)

    @Query("UPDATE images SET info_score = :score WHERE id = :id")
    suspend fun updateInfoScore(id: Long, score: Float)

    /** 合并 info+delete 评分为单条 UPDATE（原是两次独立写）。 */
    @Query("UPDATE images SET info_score = :info, delete_score = :del WHERE id = :id")
    suspend fun updateScores(id: Long, info: Float, del: Float)

    /** 整页评分一个事务写回：全库 20w 评分原本 40w 个独立事务，按页批写后降到约 400 个。 */
    @Transaction
    suspend fun saveScoresBatch(rows: List<ScoreWrite>) {
        for (r in rows) updateScores(r.id, r.info, r.del)
    }

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

    /**
     * 批量取多组的 active 成员（审核「全部重复」装载用）：一次 IN 查询替代逐组 N+1，
     * 调用方需按变量上限分块。组内顺序与 [getByDuplicateGroup] 一致（保留图优先）。
     */
    @Query("""
        SELECT * FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb'
          AND duplicate_group_id IN (:groupIds)
        ORDER BY is_duplicate_best DESC, size DESC
    """)
    suspend fun getActiveByDuplicateGroupIds(groupIds: List<Long>): List<ImageEntity>

    /** 取若干图的视觉特征（反馈学习用） */
    @Query("SELECT id, block_hash AS blockHash, feature_grid AS featureGrid FROM images WHERE id IN (:ids)")
    suspend fun featuresByIds(ids: List<Long>): List<ImageFeatureRow>

    /** 「推荐删除」候选池：活跃非保护、有特征网格的图，按 delete_score 降序粗筛限量（不全库打分，守 20w 约束）。 */
    @Query("""
        SELECT id, feature_grid AS featureGrid, blur_score AS blurScore, width, height, size,
               source_type AS sourceType, is_animated AS isAnimated, mtime,
               (duplicate_group_id IS NOT NULL) AS inDuplicateGroup
        FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb'
          AND is_protected = 0 AND (user_keep IS NULL OR user_keep = 0)
          AND feature_grid IS NOT NULL
        ORDER BY delete_score DESC
        LIMIT :limit
    """)
    suspend fun recommendCandidatePool(limit: Int): List<RecommendCandidateRow>

    /** 某重复组的全部成员 id（人工拆分用） */
    @Query("SELECT id FROM images WHERE duplicate_group_id = :groupId")
    suspend fun imageIdsInDuplicateGroup(groupId: Long): List<Long>

    /** 清除某重复组的分配（人工拆分时调用，不影响其他组） */
    @Query("UPDATE images SET duplicate_group_id = NULL, is_duplicate_best = 0 WHERE duplicate_group_id = :groupId")
    suspend fun clearDuplicateGroup(groupId: Long)

    @Query("UPDATE images SET user_keep = :keep WHERE id = :id")
    suspend fun setUserKeep(id: Long, keep: Boolean?)

    /** 批量设置/清除用户保留（IN 更新）：调用方按 SQLite 变量上限分块并包进外层事务 */
    @Query("UPDATE images SET user_keep = :keep WHERE id IN (:ids)")
    suspend fun setUserKeepByIds(ids: List<Long>, keep: Boolean?)

    @Query("UPDATE images SET is_protected = :isFavorite WHERE id = :id")
    suspend fun setFavorite(id: Long, isFavorite: Boolean)

    /** 批量收藏 / 取消收藏（IN 更新）：调用方按 SQLite 变量上限分块并包进外层事务 */
    @Query("UPDATE images SET is_protected = :isFavorite WHERE id IN (:ids)")
    suspend fun setFavoriteByIds(ids: List<Long>, isFavorite: Boolean)

    /** 自动收藏批量写入（仅置收藏，不取消）：评分页末命中集合一次 IN 更新 */
    suspend fun favoriteByIds(ids: List<Long>) = setFavoriteByIds(ids, true)

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

    /** 待 OCR 的图片 **id**（排除损坏 / 过小图），按分析顺序取前 [limit] 个；ASC/DESC 拆分同 [pendingHashIds] */
    suspend fun pendingOcrPage(
        limit: Int,
        oldestFirst: Boolean,
        afterMtime: Long,
        afterId: Long,
    ): List<PendingAnalysisRow> = if (oldestFirst) {
        pendingOcrPageAsc(limit, afterMtime, afterId)
    } else {
        pendingOcrPageDesc(limit, afterMtime, afterId)
    }

    @Query("""
        SELECT id, mtime FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb' AND ocr_done = 0
          AND is_corrupt = 0
          AND width >= 64 AND height >= 64
          AND (mtime > :afterMtime OR (mtime = :afterMtime AND id > :afterId))
        ORDER BY mtime ASC, id ASC
        LIMIT :limit
    """)
    suspend fun pendingOcrPageAsc(limit: Int, afterMtime: Long, afterId: Long): List<PendingAnalysisRow>

    @Query("""
        SELECT id, mtime FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb' AND ocr_done = 0
          AND is_corrupt = 0
          AND width >= 64 AND height >= 64
          AND (mtime < :afterMtime OR (mtime = :afterMtime AND id < :afterId))
        ORDER BY mtime DESC, id DESC
        LIMIT :limit
    """)
    suspend fun pendingOcrPageDesc(limit: Int, afterMtime: Long, afterId: Long): List<PendingAnalysisRow>

    /** 待 AI 打标签的图片 **id**：已 hash、未 ai_done、未损坏，按分析顺序取前 [limit] 个 */
    suspend fun pendingAiTagPage(
        limit: Int,
        oldestFirst: Boolean,
        afterMtime: Long,
        afterId: Long,
    ): List<PendingAnalysisRow> = if (oldestFirst) {
        pendingAiTagPageAsc(limit, afterMtime, afterId)
    } else {
        pendingAiTagPageDesc(limit, afterMtime, afterId)
    }

    @Query("""
        SELECT id, mtime FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb'
          AND hash_done = 1 AND is_corrupt = 0
          AND (ai_done = 0 OR NOT EXISTS (SELECT 1 FROM ai_analysis a WHERE a.image_id = images.id))
          AND (mtime > :afterMtime OR (mtime = :afterMtime AND id > :afterId))
        ORDER BY mtime ASC, id ASC
        LIMIT :limit
    """)
    suspend fun pendingAiTagPageAsc(limit: Int, afterMtime: Long, afterId: Long): List<PendingAnalysisRow>

    @Query("""
        SELECT id, mtime FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb'
          AND hash_done = 1 AND is_corrupt = 0
          AND (ai_done = 0 OR NOT EXISTS (SELECT 1 FROM ai_analysis a WHERE a.image_id = images.id))
          AND (mtime < :afterMtime OR (mtime = :afterMtime AND id < :afterId))
        ORDER BY mtime DESC, id DESC
        LIMIT :limit
    """)
    suspend fun pendingAiTagPageDesc(limit: Int, afterMtime: Long, afterId: Long): List<PendingAnalysisRow>

    /** 待生成向量的图片 **id**：已 hash、未 embedding_done、未损坏，按分析顺序取前 [limit] 个 */
    suspend fun pendingEmbeddingPage(
        limit: Int,
        oldestFirst: Boolean,
        afterMtime: Long,
        afterId: Long,
        model: String = "",
    ): List<PendingAnalysisRow> = if (oldestFirst) {
        pendingEmbeddingPageAsc(limit, afterMtime, afterId, model)
    } else {
        pendingEmbeddingPageDesc(limit, afterMtime, afterId, model)
    }

    @Query("""
        SELECT id, mtime FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb'
          AND hash_done = 1 AND is_corrupt = 0
          AND (embedding_done = 0 OR EXISTS (
              SELECT 1 FROM image_embeddings e WHERE e.image_id = images.id AND e.model <> :model
          ))
          AND (mtime > :afterMtime OR (mtime = :afterMtime AND id > :afterId))
        ORDER BY mtime ASC, id ASC
        LIMIT :limit
    """)
    suspend fun pendingEmbeddingPageAsc(limit: Int, afterMtime: Long, afterId: Long, model: String): List<PendingAnalysisRow>

    @Query("""
        SELECT id, mtime FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb'
          AND hash_done = 1 AND is_corrupt = 0
          AND (embedding_done = 0 OR EXISTS (
              SELECT 1 FROM image_embeddings e WHERE e.image_id = images.id AND e.model <> :model
          ))
          AND (mtime < :afterMtime OR (mtime = :afterMtime AND id < :afterId))
        ORDER BY mtime DESC, id DESC
        LIMIT :limit
    """)
    suspend fun pendingEmbeddingPageDesc(limit: Int, afterMtime: Long, afterId: Long, model: String): List<PendingAnalysisRow>

    // ocr_done 标记已并入 OcrDao.saveOcrResultsBatch（带乐观校验 + 按块事务提交），
    // 不再提供无守卫的单条 markOcrDone：OCR 期间文件被重写时会把陈旧内容标成已完成。

    /**
     * 定向标记 AI 打标完成（带 size/mtime 乐观守卫）：文件在打标窗口内被重写则守卫不命中、
     * 不落陈旧完成标记；只置 ai_done，不整行回写 chunk 装载时的陈旧快照。
     */
    @Query("UPDATE images SET ai_done = 1, embedding_done = 0 WHERE id = :id AND status = 'active' AND size = :size AND mtime = :mtime")
    suspend fun markAiTagDone(id: Long, size: Long, mtime: Long): Int

    /** 定向标记 embedding 完成：只置 embedding_done，不整行回写陈旧快照 */
    @Query("UPDATE images SET embedding_done = 1 WHERE id = :id AND status = 'active' AND size = :size AND mtime = :mtime")
    suspend fun markEmbeddingDone(id: Long, size: Long, mtime: Long): Int

    @Query("""
        SELECT * FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb' AND id IN (:ids)
    """)
    suspend fun getByIds(ids: List<Long>): List<ImageEntity>

    /** 隔离事务内重验最终保护状态；与 markQuarantinePending 同一事务使用。 */
    @Query("""
        SELECT * FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb' AND id IN (:ids)
          AND is_protected = 0 AND (user_keep IS NULL OR user_keep = 0)
    """)
    suspend fun getCleanupEligibleByIds(ids: List<Long>): List<ImageEntity>

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
     *
     * 拆 ASC/DESC 常量排序：`ORDER BY (CASE …)` 表达式排序会让 SQLite 放弃 mtime 索引，
     * 每次生成审核都付 200w 行全表扫描 + 数十万命中行 temp B-tree 排序（数秒到十几秒）；
     * 常量排序可按索引序扫描并在凑满 LIMIT 后提前终止。
     */
    suspend fun cleanupCandidatePool(
        corruptEnabled: Boolean,
        blankEnabled: Boolean,
        duplicateEnabled: Boolean,
        lowResEnabled: Boolean,
        lowResMaxPixels: Long,
        blurEnabled: Boolean,
        blurMaxScore: Float,
        tinyEnabled: Boolean,
        tinyMaxBytes: Long,
        emojiEnabled: Boolean,
        emojiSquareRatio: Float,
        emojiMaxSide: Int,
        emojiMinSide: Int,
        oldestFirst: Boolean,
        poolLimit: Int,
    ): List<CleanupSignalRow> = if (oldestFirst) {
        cleanupCandidatePoolAsc(
            corruptEnabled, blankEnabled, duplicateEnabled, lowResEnabled, lowResMaxPixels,
            blurEnabled, blurMaxScore, tinyEnabled, tinyMaxBytes,
            emojiEnabled, emojiSquareRatio, emojiMaxSide, emojiMinSide, poolLimit,
        )
    } else {
        cleanupCandidatePoolDesc(
            corruptEnabled, blankEnabled, duplicateEnabled, lowResEnabled, lowResMaxPixels,
            blurEnabled, blurMaxScore, tinyEnabled, tinyMaxBytes,
            emojiEnabled, emojiSquareRatio, emojiMaxSide, emojiMinSide, poolLimit,
        )
    }

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
          AND (
                (:corruptEnabled AND is_corrupt = 1)
             OR (:blankEnabled AND is_blank = 1)
             OR (:duplicateEnabled AND duplicate_group_id IS NOT NULL)
             OR (:lowResEnabled AND width > 0 AND height > 0 AND width * height < :lowResMaxPixels)
             OR (:blurEnabled AND blur_score IS NOT NULL AND blur_score < :blurMaxScore)
             OR (:tinyEnabled AND size >= 1 AND size < :tinyMaxBytes)
             OR (:emojiEnabled AND width > 0 AND height > 0
                              AND CAST(MIN(width, height) AS REAL) / MAX(width, height) >= :emojiSquareRatio
                              AND MAX(width, height) <= :emojiMaxSide
                              AND MIN(width, height) >= :emojiMinSide)
          )
        ORDER BY mtime ASC, id ASC
        LIMIT :poolLimit
    """)
    suspend fun cleanupCandidatePoolAsc(
        corruptEnabled: Boolean,
        blankEnabled: Boolean,
        duplicateEnabled: Boolean,
        lowResEnabled: Boolean,
        lowResMaxPixels: Long,
        blurEnabled: Boolean,
        blurMaxScore: Float,
        tinyEnabled: Boolean,
        tinyMaxBytes: Long,
        emojiEnabled: Boolean,
        emojiSquareRatio: Float,
        emojiMaxSide: Int,
        emojiMinSide: Int,
        poolLimit: Int,
    ): List<CleanupSignalRow>

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
          AND (
                (:corruptEnabled AND is_corrupt = 1)
             OR (:blankEnabled AND is_blank = 1)
             OR (:duplicateEnabled AND duplicate_group_id IS NOT NULL)
             OR (:lowResEnabled AND width > 0 AND height > 0 AND width * height < :lowResMaxPixels)
             OR (:blurEnabled AND blur_score IS NOT NULL AND blur_score < :blurMaxScore)
             OR (:tinyEnabled AND size >= 1 AND size < :tinyMaxBytes)
             OR (:emojiEnabled AND width > 0 AND height > 0
                              AND CAST(MIN(width, height) AS REAL) / MAX(width, height) >= :emojiSquareRatio
                              AND MAX(width, height) <= :emojiMaxSide
                              AND MIN(width, height) >= :emojiMinSide)
          )
        ORDER BY mtime DESC, id DESC
        LIMIT :poolLimit
    """)
    suspend fun cleanupCandidatePoolDesc(
        corruptEnabled: Boolean,
        blankEnabled: Boolean,
        duplicateEnabled: Boolean,
        lowResEnabled: Boolean,
        lowResMaxPixels: Long,
        blurEnabled: Boolean,
        blurMaxScore: Float,
        tinyEnabled: Boolean,
        tinyMaxBytes: Long,
        emojiEnabled: Boolean,
        emojiSquareRatio: Float,
        emojiMaxSide: Int,
        emojiMinSide: Int,
        poolLimit: Int,
    ): List<CleanupSignalRow>

    // ---- 按标签筛选（标签管理页）----

    /** 某标签下的活跃图片，分页（走 image_tags.tag_id 索引 + images 主键连接） */
    @Query("""
        SELECT i.* FROM images i
        INNER JOIN image_tags it ON it.image_id = i.id
        WHERE it.tag_id = :tagId AND i.status = 'active' AND i.source_type <> 'chatthumb'
        ORDER BY i.mtime DESC, i.id DESC
    """)
    fun pagingImagesByTag(tagId: Long): PagingSource<Int, ImageEntity>

    /** 某标签下活跃图片数（标签列表计数 / 删除前总量） */
    @Query("""
        SELECT COUNT(*) FROM images i
        INNER JOIN image_tags it ON it.image_id = i.id
        WHERE it.tag_id = :tagId AND i.status = 'active' AND i.source_type <> 'chatthumb'
    """)
    suspend fun countActiveImagesByTag(tagId: Long): Int

    /**
     * 某标签下活跃图片的「日期分桶计数」（year*1000 + dayOfYear → 张数），供标签详情页时间线表头显示「（N）」。
     * 日期口径与 [observeTimelineDateBucketCounts] 完全一致，保证与图库分组对齐。
     */
    @Query("""
        SELECT i.date_bucket AS bucketKey, COUNT(*) AS count
        FROM images i
        INNER JOIN image_tags it ON it.image_id = i.id
        WHERE it.tag_id = :tagId AND i.status = 'active' AND i.source_type <> 'chatthumb'
        GROUP BY i.date_bucket
    """)
    fun observeTagDateBucketCounts(tagId: Long): Flow<List<BucketCountRow>>

    /**
     * 某标签下活跃图片的 id，keyset 分块（id 升序，传上一块末尾 id 作 [afterId]）。
     * 「删除该标签下全部图片」时按块取出逐块处理，绝不一次性把整组 id 装载进内存。
     */
    @Query("""
        SELECT i.id FROM images i
        INNER JOIN image_tags it ON it.image_id = i.id
        WHERE it.tag_id = :tagId AND i.status = 'active' AND i.source_type <> 'chatthumb'
          AND i.id > :afterId
        ORDER BY i.id
        LIMIT :limit
    """)
    suspend fun imageIdsByTagChunk(tagId: Long, afterId: Long, limit: Int): List<Long>
}

/** 一张图的 info/delete 评分，供 [ImageDao.saveScoresBatch] 整页事务写回 */
data class ScoreWrite(
    val id: Long,
    val info: Float,
    val del: Float,
)

/**
 * 一张图的 hash+质量分析结果，供 [ImageDao.saveHashQualityBatch] 整块事务写回。
 * [expectedSize]/[expectedMtime] 取自读文件前装载的实体快照，写回时作乐观校验（见 saveHashQuality）。
 */
data class HashQualityWrite(
    val id: Long,
    val xxhash: String?,
    val phash: Long?,
    val blockHash: ByteArray?,
    val featureGrid: ByteArray?,
    val width: Int,
    val height: Int,
    val blur: Float?,
    val blank: Boolean,
    val corrupt: Boolean,
    val expectedSize: Long,
    val expectedMtime: Long,
)

/** 分析待办 keyset 页的稳定排序键；失败项也推进游标，避免首屏饥饿。 */
data class PendingAnalysisRow(
    val id: Long,
    val mtime: Long,
)

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

/** 重复聚类所需的最小投影（由 [ImageDao.dedupProjection] 填充） */
data class DedupRow(
    val id: Long,
    val xxhash: String?,
    val phash: Long?,
    val blockHash: ByteArray?,
    val featureGrid: ByteArray?,
    val width: Int,
    val height: Int,
    val size: Long,
    val isBlank: Boolean,
    val isAnimated: Boolean,
)

/** 一条重复分配结果（由 [DuplicateClusterer] 产出，[ImageDao.assignDuplicates] 批量写回） */
data class DuplicateAssignment(val id: Long, val groupId: Long, val isBest: Boolean)

/** 重复图最小投影（「审核全部重复」按组聚合用） */
data class DuplicateGroupRow(val id: Long, val duplicateGroupId: Long, val size: Long)

/** 一张图的视觉特征投影（反馈学习用） */
data class ImageFeatureRow(val id: Long, val blockHash: ByteArray?, val featureGrid: ByteArray?)

/** 「推荐删除」候选的特征投影 */
data class RecommendCandidateRow(
    val id: Long,
    val featureGrid: ByteArray?,
    val blurScore: Float?,
    val width: Int,
    val height: Int,
    val size: Long,
    val sourceType: String,
    val isAnimated: Boolean,
    val mtime: Long,
    val inDuplicateGroup: Boolean,
)
