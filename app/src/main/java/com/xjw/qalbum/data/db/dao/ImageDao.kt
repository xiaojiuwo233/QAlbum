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
 * 扫描、时间线分页、家族、归档、清理规则与统计。只记录文件信息，不做内容分析。
 */
@Dao
interface ImageDao {

    @Query("SELECT * FROM images WHERE id IN (:ids)")
    suspend fun getAnyByIds(ids: List<Long>): List<ImageEntity>

    @Query("SELECT * FROM images WHERE path IN (:paths)")
    suspend fun getAnyByPaths(paths: List<String>): List<ImageEntity>

    /** 库里有没有图（判断要不要显示首次引导） */
    @Query("SELECT EXISTS(SELECT 1 FROM images)")
    suspend fun hasAny(): Boolean

    /** 这些家族里已入库、还在原位置的文件（含 Temp 转码图、并进来的发送图片缓存；回收站里的与删掉的路径已换成内部地址） */
    @Query("SELECT * FROM images WHERE variant_group IN (:groups) AND path NOT LIKE 'qalbum-internal:%'")
    suspend fun getByFamilies(groups: List<Long>): List<ImageEntity>

    /** [archived]：归档页删除时归档的图也可删（见 PictureDeleteOptions.includeArchived） */
    @Query("""SELECT * FROM images WHERE id IN (:ids)
        AND (status IN ('active','thumbnail_trash') OR (:archived AND status='archived'))
        AND (is_protected=0 OR :archived)""")
    suspend fun getLinkedCleanupEligible(ids: List<Long>, archived: Boolean = false): List<ImageEntity>

    @Query("""UPDATE images SET status='quarantine_pending' WHERE id IN (:ids)
        AND (status IN ('active','thumbnail_trash') OR (:archived AND status='archived'))
        AND (is_protected=0 OR :archived)""")
    suspend fun markLinkedQuarantinePending(ids: List<Long>, archived: Boolean = false): Int

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

    /** 文件移动前的原子 claim：同时重验归档，真实 path 暂时保留以阻止扫描重复插入。 */
    @Query("""
        UPDATE images SET status = 'quarantine_pending'
        WHERE id IN (:ids) AND status = 'active' AND is_protected = 0
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

    /** [finalizeRestore] 的整批版本（一批最多几百行，一条语句） */
    @Query("""
        UPDATE images SET status = CASE WHEN source_type='chatthumb' THEN 'thumbnail_trash' ELSE 'active' END, last_seen = :lastSeen
        WHERE id IN (:ids) AND status = 'restore_pending'
    """)
    suspend fun finalizeRestoreBatch(ids: List<Long>, lastSeen: Long): Int

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
     * 磁盘上已消失的活跃图片（含已归档的）→ missing（图库查询只认 active，幽灵记录自动从图库消失；
     * 归档标记 is_protected 保留，文件回来复活后由扫描结束时的归档同步重新隐藏）。
     * `last_seen < :scanStart` 守卫：回收站恢复会在成功事务内 touchLastSeen（恢复时刻），
     * 进行中的扫描批次拿的是恢复前的文件快照，不得把刚恢复的行覆盖成 missing。
     */
    @Query("UPDATE images SET status = 'missing' WHERE id IN (:ids) AND status IN ('active', 'thumbnail_trash', 'archived') AND last_seen < :scanStart")
    suspend fun markMissingByIds(ids: List<Long>, scanStart: Long)

    /** 整个三字符目录消失时按 path 范围批量标记 missing（last_seen 守卫同 [markMissingByIds]） */
    @Query("UPDATE images SET status = 'missing' WHERE path >= :low AND path < :high AND status IN ('active', 'thumbnail_trash', 'archived') AND last_seen < :scanStart")
    suspend fun markMissingInPathRange(low: String, high: String, scanStart: Long)

    /** 文件以相同 size/mtime 重新出现：仅恢复 missing（缩略图回到 thumbnail_trash，其余 active） */
    @Query("UPDATE images SET status = CASE WHEN source_type = 'chatthumb' THEN 'thumbnail_trash' ELSE 'active' END WHERE id IN (:ids) AND status = 'missing'")
    suspend fun reviveByIds(ids: List<Long>)

    /** 深度校验：仅修正 mime / 动图标记 */
    @Query("UPDATE images SET mime = :mime, is_animated = :isAnimated WHERE id = :id")
    suspend fun fixMimeType(id: Long, mime: String, isAnimated: Boolean)

    /**
     * 宽高取本次读到的文件头，读不到（0）且内容没变时保留原值；文件被 QQ 重写（如重新下载原图）后
     * 压缩状态 optimized 清零。
     *
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
                WHEN status = 'archived' THEN 'archived'
                WHEN :sourceType = 'chatthumb' THEN 'thumbnail_trash'
                WHEN status = 'missing' THEN 'active'
                ELSE status
            END,
            width = CASE WHEN :width > 0 OR size != :size OR mtime != :mtime THEN :width ELSE width END,
            height = CASE WHEN :width > 0 OR size != :size OR mtime != :mtime THEN :height ELSE height END,
            optimized = CASE WHEN size != :size OR mtime != :mtime THEN 0 ELSE optimized END
        WHERE path = :path AND status IN ('active', 'missing', 'thumbnail_trash', 'archived')
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
        width: Int,
        height: Int,
    )

    /**
     * 批量插入新增记录（调用方已通过内存 path 快照确保这些 path 不在库中，故不会重复）。
     * 用 IGNORE 兜底极端并发下的冲突。chatthumb 的 status 由调用方在构造 entity 时设好。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(images: List<ImageEntity>)

    /**
     * 批量更新已存在记录的扫描字段（size/mtime 变化时由 [updateFromScanByPath] 清掉压缩状态）。
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
                width = image.width,
                height = image.height,
            )
        }
    }

    // ---- 宽高补读与原图压缩 ----

    /** 还没读到宽高的行（宽高为 0）；补读失败的记 -1，不再反复尝试 */
    @Query("""
        SELECT id, path FROM images
        WHERE width = 0 AND status IN ('active', 'thumbnail_trash', 'archived') AND id > :afterId
        ORDER BY id LIMIT :limit
    """)
    suspend fun rowsMissingSize(afterId: Long, limit: Int): List<PathRow>

    @Query("SELECT COUNT(*) FROM images WHERE width = 0 AND status IN ('active', 'thumbnail_trash', 'archived')")
    suspend fun countMissingSize(): Int

    @Query("UPDATE images SET width = :width, height = :height WHERE id = :id AND width = 0")
    suspend fun saveSize(id: Long, width: Int, height: Int)

    @Transaction
    suspend fun saveSizes(rows: List<SizeWrite>) {
        rows.forEach { saveSize(it.id, it.width, it.height) }
    }

    data class PathRow(val id: Long, val path: String)

    data class SizeWrite(val id: Long, val width: Int, val height: Int)

    /**
     * 要核对宽高的行：长边不超过 [maxSide] 的非缩略图（旧版分析写入的缩小尺寸都在其中，见 ImageSizeBackfill）。
     * 回收站里的读移进去的那份文件。
     */
    @Query("""
        SELECT i.id, COALESCE(q.quarantine_path, i.path) AS path, i.width, i.height FROM images i
        LEFT JOIN quarantine q ON q.image_id = i.id AND i.status = 'quarantined' AND q.state = 'ready'
        WHERE i.id > :afterId AND i.width > 0 AND MAX(i.width, i.height) <= :maxSide AND i.source_type <> 'chatthumb'
          AND (i.status IN ('active', 'archived') OR q.id IS NOT NULL)
        ORDER BY i.id LIMIT :limit
    """)
    suspend fun rowsWithSmallSize(maxSide: Int, afterId: Long, limit: Int): List<SizeRow>

    @Query("""
        SELECT COUNT(*) FROM images WHERE width > 0 AND MAX(width, height) <= :maxSide AND source_type <> 'chatthumb'
          AND status IN ('active', 'archived', 'quarantined')
    """)
    suspend fun countWithSmallSize(maxSide: Int): Int

    /** 只改核对时读到的那个值（期间被扫描或压缩改过的不动） */
    @Query("UPDATE images SET width = :width, height = :height WHERE id = :id AND width = :oldWidth AND height = :oldHeight")
    suspend fun correctSize(id: Long, oldWidth: Int, oldHeight: Int, width: Int, height: Int)

    @Transaction
    suspend fun correctSizes(rows: List<SizeFix>) {
        rows.forEach { correctSize(it.id, it.oldWidth, it.oldHeight, it.width, it.height) }
    }

    data class SizeRow(val id: Long, val path: String, val width: Int, val height: Int)

    data class SizeFix(val id: Long, val oldWidth: Int, val oldHeight: Int, val width: Int, val height: Int)

    /** 压缩 / 还原成功后写回大小、宽高与格式（PNG 压缩后是 JPEG）；文件在此期间被扫描或 QQ 改过时不匹配，返回 0 */
    @Query("""
        UPDATE images SET size = :size, width = :width, height = :height, optimized = :optimized, mime = :mime
        WHERE id = :id AND size = :expectedSize AND mtime = :expectedMtime
    """)
    suspend fun applyOptimization(
        id: Long, size: Long, width: Int, height: Int, optimized: Int, mime: String, expectedSize: Long, expectedMtime: Long,
    ): Int

    /** 压缩过或从压缩还原过的图片（启动时修正 0.2.1 留下的文件属主） */
    @Query("SELECT id, path FROM images WHERE optimized IN (1, 2) AND id > :afterId ORDER BY id LIMIT :limit")
    suspend fun optimizedPaths(afterId: Long, limit: Int): List<PathRow>

    /** 压缩不划算或无法处理：记下，规则不再列出 */
    @Query("UPDATE images SET optimized = :state WHERE id = :id AND optimized = 0")
    suspend fun markOptimized(id: Long, state: Int)

    // ---- 单条 / 路径查询 ----

    @Query("SELECT * FROM images WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): ImageEntity?

    @Query("SELECT * FROM images WHERE path = :path LIMIT 1")
    suspend fun getByPath(path: String): ImageEntity?

    @Query("""
        SELECT COUNT(*) AS count, IFNULL(SUM(size), 0) AS bytes
        FROM images
        WHERE source_type = 'chatthumb' AND status = 'thumbnail_trash'
    """)
    fun observeThumbnailTrashSummary(): Flow<ThumbnailTrashSummary>

    /** 同上的一次性查询（清理页经 StatsCache 缓存） */
    @Query("""
        SELECT COUNT(*) AS count, IFNULL(SUM(size), 0) AS bytes
        FROM images
        WHERE source_type = 'chatthumb' AND status = 'thumbnail_trash'
    """)
    suspend fun thumbnailTrashSummary(): ThumbnailTrashSummary

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
        WHERE status = 'active' AND source_type <> 'chatthumb' AND (:stacked = 0 OR variant_hidden = 0)
        ORDER BY mtime DESC, id DESC
    """)
    fun pagingActiveTimeline(stacked: Boolean): PagingSource<Int, ImageEntity>

    /** 时间线视图（旧到新）：mtime 升序。 */
    @Query("""
        SELECT * FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb' AND (:stacked = 0 OR variant_hidden = 0)
        ORDER BY mtime ASC, id ASC
    """)
    fun pagingActiveTimelineByDateAsc(stacked: Boolean): PagingSource<Int, ImageEntity>

    /** 时间线视图（大到小）：size 降序，走 index_images_size 避免 25w 图 filesort。 */
    @Query("""
        SELECT * FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb'
        ORDER BY size DESC, id DESC
    """)
    fun pagingActiveTimelineBySize(): PagingSource<Int, ImageEntity>

    /** 合并显示的大到小：按整组合计（variant_bytes）降序，走 index_images_variant_bytes */
    @Query("""
        SELECT * FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb' AND variant_hidden = 0
        ORDER BY variant_bytes DESC, id DESC
    """)
    fun pagingStackedTimelineBySize(): PagingSource<Int, ImageEntity>

    /**
     * 日期分桶计数：bucketKey = year*1000 + dayOfYear（预计算列 date_bucket，入库时写好）。
     * 对预计算整列 GROUP BY 走 (status, source_type, date_bucket, is_protected) 覆盖索引，
     * 免去早期版本 200w 行逐行 2 次 strftime 的全表聚合（单次 3-10s、扫描期间背靠背重跑）。
     */
    @Query("""
        SELECT date_bucket AS bucketKey, COUNT(*) AS count
        FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb' AND (:stacked = 0 OR variant_hidden = 0)
        GROUP BY date_bucket
    """)
    fun observeTimelineDateBucketCounts(stacked: Boolean): Flow<List<BucketCountRow>>

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

    /** 合并显示的大小分组：按整组合计分桶（与 [pagingStackedTimelineBySize] 的排序一致） */
    @Query("""
        SELECT
            CASE
                WHEN variant_bytes >= 10485760 THEN 100000
                WHEN variant_bytes >= 1048576 THEN 10000 + (variant_bytes / 1048576)
                WHEN variant_bytes >= 102400 THEN 1000 + (variant_bytes / 102400)
                WHEN variant_bytes >= 10240 THEN 100 + (variant_bytes / 10240)
                ELSE 0
            END AS bucketKey,
            COUNT(*) AS count
        FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb' AND variant_hidden = 0
        GROUP BY bucketKey
    """)
    fun observeStackedSizeBucketCounts(): Flow<List<BucketCountRow>>

    data class BucketCountRow(
        val bucketKey: Long,
        val count: Int,
    )

    // 详情页时间线已迁窗口 API（timelineRank* + timelineIdsBefore/After*）：
    // 200w 行全量 id 装载（旧 timelineIds 系列）会阻塞详情页数秒并常驻数十 MB 装箱内存，已删除。

    // ---- 时间线定位 / 窗口（keyset，替代全量 id 装载）----
    //
    // 详情页左右滑动的定位范式：先用 timelineRank* 拿当前图的序号（COUNT，走覆盖索引），
    // 再用 timelineIds{After,Before}* 按排序键取邻域窗口页，滑动到窗口边缘时续取下一页。
    // DATE_ASC 排序无需独立方法：after(asc) 即 [timelineIdsBeforeDateDesc]、before(asc) 即
    // [timelineIdsAfterDateDesc]，rank(asc) = 总数 - rankDesc - 1 或直接用 [timelineRankDateAsc]。

    /** 主时间线总数（suspend 一次性查询；Flow 版见 [observeImageCount]） */
    @Query("SELECT COUNT(*) FROM images WHERE status = 'active' AND source_type <> 'chatthumb' AND (:stacked = 0 OR variant_hidden = 0)")
    suspend fun countActiveTimeline(stacked: Boolean): Int

    /** 「新到旧」时间线中给定排序键的 rank（0-based，= 排在它前面的行数） */
    @Query("""
        SELECT COUNT(*) FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb' AND (:stacked = 0 OR variant_hidden = 0)
          AND (mtime > :mtime OR (mtime = :mtime AND id > :id))
    """)
    suspend fun timelineRankDateDesc(mtime: Long, id: Long, stacked: Boolean): Int

    /** 「旧到新」时间线中给定排序键的 rank（0-based） */
    @Query("""
        SELECT COUNT(*) FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb' AND (:stacked = 0 OR variant_hidden = 0)
          AND (mtime < :mtime OR (mtime = :mtime AND id < :id))
    """)
    suspend fun timelineRankDateAsc(mtime: Long, id: Long, stacked: Boolean): Int

    /** 「大到小」时间线中给定排序键的 rank（0-based） */
    @Query("""
        SELECT COUNT(*) FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb'
          AND (size > :size OR (size = :size AND id > :id))
    """)
    suspend fun timelineRankSizeDesc(size: Long, id: Long): Int

    /** 合并显示的「大到小」时间线中给定排序键（整组合计）的 rank */
    @Query("""
        SELECT COUNT(*) FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb' AND variant_hidden = 0
          AND (variant_bytes > :bytes OR (variant_bytes = :bytes AND id > :id))
    """)
    suspend fun stackedRankSizeDesc(bytes: Long, id: Long): Int

    /** 「新到旧」时间线中排序键之后（更旧方向）的一页 id，不含键自身 */
    @Query("""
        SELECT id FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb' AND (:stacked = 0 OR variant_hidden = 0)
          AND (mtime < :mtime OR (mtime = :mtime AND id < :id))
        ORDER BY mtime DESC, id DESC
        LIMIT :limit
    """)
    suspend fun timelineIdsAfterDateDesc(mtime: Long, id: Long, limit: Int, stacked: Boolean): List<Long>

    /** 「新到旧」时间线中排序键之前（更新方向）的一页 id：返回序为离键近→远，调用方按需 reverse */
    @Query("""
        SELECT id FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb' AND (:stacked = 0 OR variant_hidden = 0)
          AND (mtime > :mtime OR (mtime = :mtime AND id > :id))
        ORDER BY mtime ASC, id ASC
        LIMIT :limit
    """)
    suspend fun timelineIdsBeforeDateDesc(mtime: Long, id: Long, limit: Int, stacked: Boolean): List<Long>

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

    /** 合并显示的「大到小」：排序键之后的一页 id */
    @Query("""
        SELECT id FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb' AND variant_hidden = 0
          AND (variant_bytes < :bytes OR (variant_bytes = :bytes AND id < :id))
        ORDER BY variant_bytes DESC, id DESC
        LIMIT :limit
    """)
    suspend fun stackedIdsAfterSizeDesc(bytes: Long, id: Long, limit: Int): List<Long>

    /** 合并显示的「大到小」：排序键之前的一页 id（离键近→远） */
    @Query("""
        SELECT id FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb' AND variant_hidden = 0
          AND (variant_bytes > :bytes OR (variant_bytes = :bytes AND id > :id))
        ORDER BY variant_bytes ASC, id ASC
        LIMIT :limit
    """)
    suspend fun stackedIdsBeforeSizeDesc(bytes: Long, id: Long, limit: Int): List<Long>

    // ---- 点分组标题全选：取一个日期 / 大小分组里的全部图片 ----

    @Query("""
        SELECT id FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb' AND (:stacked = 0 OR variant_hidden = 0)
          AND mtime >= :from AND mtime < :until
    """)
    suspend fun timelineIdsBetween(from: Long, until: Long, stacked: Boolean): List<Long>

    @Query("SELECT id FROM images WHERE status = 'active' AND source_type <> 'chatthumb' AND size >= :low AND size < :high")
    suspend fun timelineIdsBySize(low: Long, high: Long): List<Long>

    @Query("""
        SELECT id FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb' AND variant_hidden = 0
          AND variant_bytes >= :low AND variant_bytes < :high
    """)
    suspend fun stackedIdsBySize(low: Long, high: Long): List<Long>

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

    /** 同上的一次性查询（清理页经 StatsCache 缓存） */
    @Query("""
        SELECT source_type AS source, COUNT(*) AS count, IFNULL(SUM(size), 0) AS bytes
        FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb'
        GROUP BY source_type
    """)
    suspend fun sourceBreakdown(): List<SourceBreakdownRow>

    data class SourceBreakdownRow(
        val source: String,
        val count: Int,
        val bytes: Long,
    )

    // ---- 图片家族（同一张 QQ 图片的原图 / 预览图 / 缩略图，见 QqCachePaths.familyKey）----

    /** 家族的现存版本：原图、预览图、其他、缩略图依次，同类大的在前 */
    @Query("""
        SELECT * FROM images
        WHERE variant_group = :group AND status IN ('active', 'thumbnail_trash')
        ORDER BY CASE source_type WHEN 'chatraw' THEN 0 WHEN 'chatimg' THEN 1 WHEN 'chatthumb' THEN 3 ELSE 2 END, size DESC, id
    """)
    suspend fun familyMembers(group: Long): List<ImageEntity>

    /** 删除确认用：所选图片的路径、大小、家族与是否已归档 */
    @Query("""
        SELECT id, path, size, variant_group AS variantGroup, is_protected AS isArchived
        FROM images WHERE id IN (:ids)
    """)
    suspend fun deleteFactRows(ids: List<Long>): List<DeleteFactRow>

    data class DeleteFactRow(val id: Long, val path: String, val size: Long, val variantGroup: Long?, val isArchived: Boolean)

    /** 这些家族的现存版本（大小用于「连同其他版本」的合计）；[archived] 时也算归档隐藏的版本 */
    @Query("""SELECT id, variant_group AS variantGroup, size FROM images WHERE variant_group IN (:groups)
        AND (status IN ('active', 'thumbnail_trash') OR (:archived AND status = 'archived'))""")
    suspend fun familyMemberSizes(groups: List<Long>, archived: Boolean = false): List<FamilyMemberSize>

    data class FamilyMemberSize(val id: Long, val variantGroup: Long, val size: Long)

    /** 回填家族键：按 id 分页取路径（已在回收站的图用原位置路径） */
    @Query("""
        SELECT i.id AS id, COALESCE(q.original_path, i.path) AS path FROM images i
        LEFT JOIN quarantine q ON q.image_id = i.id
        WHERE i.id > :afterId ORDER BY i.id LIMIT :limit
    """)
    suspend fun variantKeyRows(afterId: Long, limit: Int): List<VariantKeyRow>

    data class VariantKeyRow(val id: Long, val path: String)

    @Query("SELECT COALESCE(MAX(id), 0) FROM images")
    suspend fun maxId(): Long

    // ---- 归档（is_protected，见 ImageEntity 与 ArchiveManager）----
    // 总是按整个家族（同一张图的原图 / 预览图 / 缩略图）设置；只动现存的行，回收站里的不碰。
    // :ids 在语句里出现两次，调用方按 SQLite 变量上限的一半以内分块。

    /** 把这些图连同同一张图的其他版本标为归档 */
    @Query("""
        UPDATE images SET is_protected = 1
        WHERE is_protected = 0 AND status IN ('active', 'thumbnail_trash', 'missing', 'archived')
          AND (id IN (:ids) OR variant_group IN (SELECT variant_group FROM images WHERE id IN (:ids) AND variant_group IS NOT NULL))
    """)
    suspend fun protectFamilies(ids: List<Long>): Int

    /** 不显示归档时：这些图的家族里已归档的行转为 archived */
    @Query("""
        UPDATE images SET status = 'archived'
        WHERE is_protected = 1 AND status IN ('active', 'thumbnail_trash')
          AND (id IN (:ids) OR variant_group IN (SELECT variant_group FROM images WHERE id IN (:ids) AND variant_group IS NOT NULL))
    """)
    suspend fun hideFamilies(ids: List<Long>): Int

    /** 取消归档：整个家族恢复原来的状态（缩略图回到 thumbnail_trash，其余 active） */
    @Query("""
        UPDATE images SET is_protected = 0,
            status = CASE WHEN status <> 'archived' THEN status WHEN source_type = 'chatthumb' THEN 'thumbnail_trash' ELSE 'active' END
        WHERE is_protected = 1
          AND (id IN (:ids) OR variant_group IN (SELECT variant_group FROM images WHERE id IN (:ids) AND variant_group IS NOT NULL))
    """)
    suspend fun unprotectFamilies(ids: List<Long>): Int

    /** 归档（[hidden] = 不在图库显示归档）；整批一个事务，只触发一次表失效 */
    @Transaction
    suspend fun archive(ids: List<Long>, hidden: Boolean) {
        ids.chunked(ARCHIVE_CHUNK).forEach { page ->
            protectFamilies(page)
            if (hidden) hideFamilies(page)
        }
    }

    @Transaction
    suspend fun unarchive(ids: List<Long>) {
        ids.chunked(ARCHIVE_CHUNK).forEach { unprotectFamilies(it) }
    }

    /** 后来入库 / 重新出现的版本（如之后才下载的原图）跟随已归档的家族一起归档 */
    @Query("""
        UPDATE images SET is_protected = 1
        WHERE is_protected = 0 AND status IN ('active', 'thumbnail_trash', 'missing', 'archived')
          AND variant_group IN (SELECT variant_group FROM images WHERE is_protected = 1 AND variant_group IS NOT NULL
              AND status IN ('active', 'thumbnail_trash', 'missing', 'archived'))
    """)
    suspend fun protectArchivedFamilies(): Int

    @Query("UPDATE images SET status = 'archived' WHERE is_protected = 1 AND status IN ('active', 'thumbnail_trash')")
    suspend fun hideArchived(): Int

    @Query("UPDATE images SET status = CASE WHEN source_type = 'chatthumb' THEN 'thumbnail_trash' ELSE 'active' END WHERE status = 'archived'")
    suspend fun showArchived(): Int

    /** 让归档与「是否在图库显示」设置一致：补齐家族，再统一隐藏或显示 */
    @Transaction
    suspend fun syncArchive(hidden: Boolean): Int = protectArchivedFamilies() + if (hidden) hideArchived() else showArchived()

    // 归档页：每张图只显示一个版本（优先非缩略图、再取最大的），只有缩略图的图也能看到。
    // [statuses] 不显示归档时为 archived，照常显示时为 active / thumbnail_trash。

    @Query("""
        SELECT * FROM images i
        WHERE i.is_protected = 1 AND i.status IN (:statuses)
          AND (i.variant_group IS NULL OR i.id = (SELECT f.id FROM images f WHERE f.variant_group = i.variant_group
              AND f.is_protected = 1 AND f.status IN (:statuses) ORDER BY f.source_type = 'chatthumb', f.size DESC, f.id LIMIT 1))
        ORDER BY
          CASE WHEN :sort = 0 THEN i.mtime END DESC,
          CASE WHEN :sort = 1 THEN i.mtime END ASC,
          CASE WHEN :sort = 2 THEN i.size END DESC,
          CASE WHEN :sort = 3 THEN i.size END ASC,
          CASE WHEN :sort IN (0, 2) THEN i.id END DESC,
          CASE WHEN :sort IN (1, 3) THEN i.id END ASC
    """)
    /** 归档页：每张图只取一个版本；[sort] 是 SourceSort 的序号（0 新到旧、1 旧到新、2 大到小、3 小到大） */
    fun pagingArchiveTimeline(statuses: List<String>, sort: Int): PagingSource<Int, ImageEntity>

    @Query("""
        SELECT COUNT(*) FROM images i
        WHERE i.is_protected = 1 AND i.status IN (:statuses)
          AND (i.variant_group IS NULL OR i.id = (SELECT f.id FROM images f WHERE f.variant_group = i.variant_group
              AND f.is_protected = 1 AND f.status IN (:statuses) ORDER BY f.source_type = 'chatthumb', f.size DESC, f.id LIMIT 1))
    """)
    fun observeArchiveCount(statuses: List<String>): Flow<Int>

    /** 同上的一次性查询（清理页经 StatsCache 缓存） */
    @Query("""
        SELECT COUNT(*) FROM images i
        WHERE i.is_protected = 1 AND i.status IN (:statuses)
          AND (i.variant_group IS NULL OR i.id = (SELECT f.id FROM images f WHERE f.variant_group = i.variant_group
              AND f.is_protected = 1 AND f.status IN (:statuses) ORDER BY f.source_type = 'chatthumb', f.size DESC, f.id LIMIT 1))
    """)
    suspend fun archiveCount(statuses: List<String>): Int

    @Query("""
        SELECT i.date_bucket AS bucketKey, COUNT(*) AS count FROM images i
        WHERE i.is_protected = 1 AND i.status IN (:statuses)
          AND (i.variant_group IS NULL OR i.id = (SELECT f.id FROM images f WHERE f.variant_group = i.variant_group
              AND f.is_protected = 1 AND f.status IN (:statuses) ORDER BY f.source_type = 'chatthumb', f.size DESC, f.id LIMIT 1))
        GROUP BY i.date_bucket
    """)
    fun observeArchiveDateBucketCounts(statuses: List<String>): Flow<List<BucketCountRow>>

    /** 归档页某一天的全部图片（点日期标题全选） */
    @Query("""
        SELECT i.id FROM images i
        WHERE i.is_protected = 1 AND i.status IN (:statuses) AND i.mtime >= :from AND i.mtime < :until
          AND (i.variant_group IS NULL OR i.id = (SELECT f.id FROM images f WHERE f.variant_group = i.variant_group
              AND f.is_protected = 1 AND f.status IN (:statuses) ORDER BY f.source_type = 'chatthumb', f.size DESC, f.id LIMIT 1))
    """)
    suspend fun archiveIdsBetween(statuses: List<String>, from: Long, until: Long): List<Long>

    /** 归档页看图的左右滑动顺序（归档的图规模小，全量取 id；[sort] 同 [pagingArchiveTimeline]） */
    @Query("""
        SELECT i.id FROM images i
        WHERE i.is_protected = 1 AND i.status IN (:statuses)
          AND (i.variant_group IS NULL OR i.id = (SELECT f.id FROM images f WHERE f.variant_group = i.variant_group
              AND f.is_protected = 1 AND f.status IN (:statuses) ORDER BY f.source_type = 'chatthumb', f.size DESC, f.id LIMIT 1))
        ORDER BY
          CASE WHEN :sort = 0 THEN i.mtime END DESC,
          CASE WHEN :sort = 1 THEN i.mtime END ASC,
          CASE WHEN :sort = 2 THEN i.size END DESC,
          CASE WHEN :sort = 3 THEN i.size END ASC,
          CASE WHEN :sort IN (0, 2) THEN i.id END DESC,
          CASE WHEN :sort IN (1, 3) THEN i.id END ASC
    """)
    suspend fun archiveTimelineIds(statuses: List<String>, sort: Int): List<Long>

    /** 归档页按大小排列时的分组计数（分组同 [observeTimelineSizeBucketCounts]） */
    @Query("""
        SELECT
            CASE
                WHEN i.size >= 10485760 THEN 100000
                WHEN i.size >= 1048576 THEN 10000 + (i.size / 1048576)
                WHEN i.size >= 102400 THEN 1000 + (i.size / 102400)
                WHEN i.size >= 10240 THEN 100 + (i.size / 10240)
                ELSE 0
            END AS bucketKey,
            COUNT(*) AS count
        FROM images i
        WHERE i.is_protected = 1 AND i.status IN (:statuses)
          AND (i.variant_group IS NULL OR i.id = (SELECT f.id FROM images f WHERE f.variant_group = i.variant_group
              AND f.is_protected = 1 AND f.status IN (:statuses) ORDER BY f.source_type = 'chatthumb', f.size DESC, f.id LIMIT 1))
        GROUP BY bucketKey
    """)
    fun observeArchiveSizeBucketCounts(statuses: List<String>): Flow<List<BucketCountRow>>

    /** 归档页某个大小区间 [low, high) 的全部图片（点大小标题全选） */
    @Query("""
        SELECT i.id FROM images i
        WHERE i.is_protected = 1 AND i.status IN (:statuses) AND i.size >= :low AND i.size < :high
          AND (i.variant_group IS NULL OR i.id = (SELECT f.id FROM images f WHERE f.variant_group = i.variant_group
              AND f.is_protected = 1 AND f.status IN (:statuses) ORDER BY f.source_type = 'chatthumb', f.size DESC, f.id LIMIT 1))
    """)
    suspend fun archiveIdsBySize(statuses: List<String>, low: Long, high: Long): List<Long>

    @Query("""
        SELECT * FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb' AND id IN (:ids)
    """)
    suspend fun getByIds(ids: List<Long>): List<ImageEntity>

    /** 隔离事务内重验最终保护状态；与 markQuarantinePending 同一事务使用。 */
    @Query("""
        SELECT * FROM images
        WHERE status = 'active' AND source_type <> 'chatthumb' AND id IN (:ids)
          AND is_protected = 0
    """)
    suspend fun getCleanupEligibleByIds(ids: List<Long>): List<ImageEntity>
}

/** 归档按家族更新时每块的 id 数（:ids 在语句里出现两次，须在 SQLite 变量上限的一半以内） */
private const val ARCHIVE_CHUNK = 400
