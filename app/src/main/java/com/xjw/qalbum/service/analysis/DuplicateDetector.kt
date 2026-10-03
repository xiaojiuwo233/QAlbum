package com.xjw.qalbum.service.analysis

import androidx.room.withTransaction
import com.xjw.qalbum.data.db.AppDatabase
import com.xjw.qalbum.data.db.dao.DedupExclusionDao
import com.xjw.qalbum.data.db.dao.DedupRow
import com.xjw.qalbum.data.db.dao.DuplicateAssignment
import com.xjw.qalbum.data.db.dao.ImageDao
import com.xjw.qalbum.domain.model.DuplicateStrictness
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.coroutines.coroutineContext

/**
 * 重复检测器（流式）。
 *
 * 200w 规模下禁止全量装载投影（旧 [ImageDao.dedupProjection] 一次物化约 500MB+ 必 OOM），改为：
 * - **精确重复**：SQL 侧 `GROUP BY xxhash` 只装载冲突组（[ImageDao.xxhashGroups]），不经全量投影；
 * - **视觉相似**：[ImageDao.dedupProjectionPaged] keyset 分页扫描 + pHash 多带 LSH。
 *   小库（可参与条目 ≤ [IN_MEMORY_LIMIT]）单次扫描全内存聚类；大库按「带 × 键区间」多轮扫描分片，
 *   每轮内存只保留落在当前键区间的条目，跨轮连通性由惰性并查集（只记连过边的 id）全程累积。
 * - **写回**：清空旧分配与写入新分配在**同一事务**（[ImageDao.replaceDuplicateAssignments]），
 *   聚类窗口内取消 / 崩溃不会把全库分组留在「已清空未写回」的中间态。
 *
 * 整个聚类在 [Dispatchers.Default] 上执行（纯 CPU 与调用方 dispatcher 解耦），桶间 / 页间
 * 周期性检查取消，cancel 在聚类阶段即时生效。
 */
class DuplicateDetector(
    private val db: AppDatabase,
    private val imageDao: ImageDao,
    private val dedupExclusionDao: DedupExclusionDao,
) {

    suspend fun recluster(
        maxVisualGroupSize: Int = DuplicateClusterer.DEFAULT_MAX_VISUAL_GROUP,
        strictness: DuplicateStrictness = DuplicateStrictness.DEFAULT,
        model: SimilarityModel = SimilarityModel.DEFAULT,
    ): Unit = withContext(Dispatchers.Default) {
        val context = coroutineContext
        val exclusions = buildExclusionMap()
        val assignments = ArrayList<DuplicateClusterer.Assignment>()
        var nextGroup = imageDao.maxDuplicateGroupId() + 1

        // ---- 1) 精确重复：只装载冲突组，分批取成员尺寸选 best ----
        val exactIds = ArrayList<Long>()
        run {
            val groups = imageDao.xxhashGroups() // 仅 COUNT>1 的组（hash + id CSV），重复子集远小于全库
            val buffer = ArrayList<List<Long>>()
            var buffered = 0
            for (row in groups) {
                val ids = row.ids.split(',').mapNotNull { it.toLongOrNull() }
                if (ids.size <= 1) continue
                buffer += ids
                buffered += ids.size
                if (buffered >= MEMBER_FLUSH_THRESHOLD) {
                    nextGroup = assignGroupsBatched(buffer, nextGroup, assignments, exactIds)
                    buffer.clear()
                    buffered = 0
                    coroutineContext.ensureActive()
                }
            }
            nextGroup = assignGroupsBatched(buffer, nextGroup, assignments, exactIds)
        }
        // 精确组成员不再参与视觉聚类：排序数组 + 二分，避免几十万 id 的装箱 HashSet
        val assignedExact = exactIds.toLongArray().also { it.sort() }

        // ---- 2) 视觉相似：分页流式 + 多带 LSH ----
        val uf = DuplicateClusterer.LazyUnionFind()
        fun eligible(row: DedupRow): Boolean =
            DuplicateClusterer.isVisualEligible(row.phash, row.blockHash, row.featureGrid, row.isBlank, row.isAnimated) &&
                java.util.Arrays.binarySearch(assignedExact, row.id) < 0

        // xxhash 置 null：视觉阶段用不到，不为全库条目各留一份 16 字符 String
        fun toItem(row: DedupRow) = DuplicateClusterer.Item(
            id = row.id, xxhash = null, phash = row.phash, width = row.width, height = row.height,
            size = row.size, blockHash = row.blockHash, featureGrid = row.featureGrid,
            isBlank = row.isBlank, isAnimated = row.isAnimated,
        )

        // 第一遍扫描：各带 16bit 键直方图；若总量 ≤ IN_MEMORY_LIMIT 顺路把条目留在内存（单扫描完成）
        val histograms = Array(DuplicateClusterer.BAND_COUNT) { IntArray(DuplicateClusterer.BAND_KEY_SPACE) }
        var collected: ArrayList<DuplicateClusterer.Item>? = ArrayList()
        var eligibleTotal = 0
        forEachDedupPage { row ->
            if (!eligible(row)) return@forEachDedupPage
            eligibleTotal++
            val phash = row.phash!!
            for (band in 0 until DuplicateClusterer.BAND_COUNT) {
                histograms[band][DuplicateClusterer.bandKey(phash, band)]++
            }
            val list = collected
            if (list != null) {
                if (eligibleTotal > IN_MEMORY_LIMIT) collected = null // 超内存预算：丢弃收集，改走分片重扫
                else list.add(toItem(row))
            }
        }

        val inMemory = collected
        if (inMemory != null) {
            DuplicateClusterer.matchAllBands(
                inMemory, uf, strictness.visualThreshold, model,
                DuplicateClusterer.DEFAULT_ASPECT_TOLERANCE, exclusions,
                checkActive = { context.ensureActive() },
            )
        } else {
            Timber.i("DuplicateDetector: $eligibleTotal visual candidates > $IN_MEMORY_LIMIT, banded range passes")
            for (band in 0 until DuplicateClusterer.BAND_COUNT) {
                val histogram = histograms[band]
                for (range in planRanges(histogram)) {
                    coroutineContext.ensureActive()
                    val buckets = HashMap<Int, ArrayList<DuplicateClusterer.Item>>()
                    forEachDedupPage { row ->
                        if (!eligible(row)) return@forEachDedupPage
                        val key = DuplicateClusterer.bandKey(row.phash!!, band)
                        // 单键条目 < 2 无法成对；单键超硬顶（病态倾斜桶）跳过防 OOM，宁漏勿崩
                        if (key !in range) return@forEachDedupPage
                        val count = histogram[key]
                        if (count < 2 || count > SINGLE_KEY_HARD_CAP) return@forEachDedupPage
                        buckets.getOrPut(key) { ArrayList(count) }.add(toItem(row))
                    }
                    for (bucket in buckets.values) {
                        DuplicateClusterer.matchBucket(
                            bucket, uf, strictness.visualThreshold, model,
                            DuplicateClusterer.DEFAULT_ASPECT_TOLERANCE, exclusions,
                            checkActive = { context.ensureActive() },
                        )
                        coroutineContext.ensureActive()
                    }
                }
            }
        }

        // ---- 3) 连通分量 → 组大小过滤 → 分批取成员尺寸选 best ----
        val components = uf.components().values.filter { it.size in 2..maxVisualGroupSize }
        run {
            val buffer = ArrayList<List<Long>>()
            var buffered = 0
            for (memberIds in components) {
                buffer += memberIds
                buffered += memberIds.size
                if (buffered >= MEMBER_FLUSH_THRESHOLD) {
                    nextGroup = assignGroupsBatched(buffer, nextGroup, assignments, collectAssigned = null) { members ->
                        DuplicateClusterer.partitionVisualComponent(members, strictness.visualThreshold, model, exclusions = exclusions)
                    }
                    buffer.clear()
                    buffered = 0
                }
            }
            nextGroup = assignGroupsBatched(buffer, nextGroup, assignments, collectAssigned = null) { members ->
                DuplicateClusterer.partitionVisualComponent(members, strictness.visualThreshold, model, exclusions = exclusions)
            }
        }

        // ---- 4) 原子写回：清空旧分配 + 写入新分配同一事务 ----
        // 聚类是分钟级窗口，期间用户新做的拆分（新 cohort）不在开跑时读的 exclusions 里；
        // 写回事务内重读最新 exclusions 复核，冲突组整组丢弃本轮分配——拆了的不被聚回来。
        // splitDuplicateGroup 也在 db 事务内写入，两边在同一写锁下串行，无交错窗口。
        val written = db.withTransaction {
            val freshExclusions = buildExclusionMap()
            val allowed = dropExcludedGroups(assignments, freshExclusions)
            val staleGroups = HashSet<Long>()
            for (chunk in allowed.chunked(GET_BY_IDS_CHUNK)) {
                val current = imageDao.getByIds(chunk.map { it.id }).associateBy { it.id }
                for (assignment in chunk) {
                    val image = current[assignment.id]
                    if (image == null || !image.hashDone || image.size != assignment.expectedSize || image.mtime != assignment.expectedMtime) {
                        staleGroups += assignment.groupId
                    }
                }
            }
            val toWrite = allowed.filterNot { it.groupId in staleGroups }
            imageDao.replaceDuplicateAssignments(
                toWrite.map { DuplicateAssignment(it.id, it.groupId, it.isBest) }
            )
            toWrite
        }
        val groupCount = written.asSequence().map { it.groupId }.distinct().count()
        Timber.i(
            "DuplicateDetector.recluster: ${written.size} rows into $groupCount groups " +
                "(candidates=$eligibleTotal, strictness=$strictness, exclusions=${exclusions.size}, edges=${uf.size})",
        )
    }

    /**
     * 按最新 exclusions 复核待写入分组：同一 cohort 的两个成员不得落入同一组，
     * 冲突组整组丢弃（该组本轮不写分配，下一轮聚类会按新 exclusions 正确分组）。
     */
    private fun dropExcludedGroups(
        assignments: List<DuplicateClusterer.Assignment>,
        exclusions: Map<Long, Set<Long>>,
    ): List<DuplicateClusterer.Assignment> {
        if (exclusions.isEmpty() || assignments.isEmpty()) return assignments
        val dropped = HashSet<Long>()
        for ((groupId, members) in assignments.groupBy { it.groupId }) {
            val seenCohorts = HashSet<Long>()
            outer@ for (m in members) {
                val cohorts = exclusions[m.id] ?: continue
                for (c in cohorts) {
                    if (!seenCohorts.add(c)) {
                        dropped.add(groupId)
                        break@outer
                    }
                }
            }
        }
        if (dropped.isEmpty()) return assignments
        Timber.i("DuplicateDetector: dropped ${dropped.size} groups conflicting with fresh exclusions")
        return assignments.filterNot { it.groupId in dropped }
    }

    /** keyset 分页遍历去重投影（页间检查取消）；[block] 内仅做过滤 / 收集，不挂起。 */
    private suspend fun forEachDedupPage(block: (DedupRow) -> Unit) {
        var afterId = 0L
        while (true) {
            val page = imageDao.dedupProjectionPaged(afterId, DEDUP_PAGE)
            if (page.isEmpty()) return
            for (row in page) block(row)
            afterId = page.last().id
            coroutineContext.ensureActive()
        }
    }

    /**
     * 对一批组分配组号并选 best（分批装载成员实体取质量排序键，工作集只有当前批）。
     * 返回下一个可用组号；[collectAssigned] 非空时把已分配的成员 id 追加进去（供后续排除）。
     */
    private suspend fun assignGroupsBatched(
        groups: List<List<Long>>,
        startGroup: Long,
        assignments: MutableList<DuplicateClusterer.Assignment>,
        collectAssigned: MutableList<Long>?,
        partition: ((List<DuplicateClusterer.Item>) -> List<List<DuplicateClusterer.Item>>)? = null,
    ): Long {
        if (groups.isEmpty()) return startGroup
        val items = HashMap<Long, DuplicateClusterer.Item>()
        groups.asSequence().flatten().chunked(GET_BY_IDS_CHUNK).forEach { chunk ->
            imageDao.getByIds(chunk).forEach {
                items[it.id] = DuplicateClusterer.Item(
                    it.id, it.xxhash, it.phash, it.width, it.height, it.size,
                    it.blockHash, it.featureGrid, it.isBlank, it.isAnimated, it.mtime,
                )
            }
        }
        var nextGroup = startGroup
        for (memberIds in groups) {
            val known = memberIds.mapNotNull { items[it] }
            val groupsNow = partition?.invoke(known) ?: known.filter { it.xxhash != null }.groupBy { it.xxhash to it.size }.values
            for (group in groupsNow) {
                if (group.size <= 1) continue
                val best = group.maxWithOrNull(DuplicateClusterer.qualityComparator)!!
                group.forEach { assignments += DuplicateClusterer.Assignment(it.id, nextGroup, it.id == best.id, it.size, it.mtime) }
                collectAssigned?.addAll(group.map { it.id })
                nextGroup++
            }
        }
        return nextGroup
    }

    /**
     * 把 16bit 键空间切成若干连续区间，每区间「可成对条目数」不超过 [IN_MEMORY_LIMIT]
     * （单键超预算时独占一个区间，收集阶段再按 [SINGLE_KEY_HARD_CAP] 兜底）。
     */
    private fun planRanges(histogram: IntArray): List<IntRange> {
        val ranges = ArrayList<IntRange>()
        var lo = 0
        var acc = 0L
        for (key in histogram.indices) {
            val c = histogram[key]
            if (c < 2) continue // 单例键不产生配对，不占预算（收集阶段同样跳过）
            if (acc + c > IN_MEMORY_LIMIT && acc > 0) {
                ranges.add(lo until key)
                lo = key
                acc = 0
            }
            acc += c
        }
        ranges.add(lo until histogram.size)
        // 全空区间（无任何可成对键）无需扫描
        return ranges.filter { r -> (r.first until r.last + 1).any { histogram[it] >= 2 } }
    }

    /** 构建 imageId → 所属 cohort 集合（人工拆分的「禁止再聚」约束）。表很小，全量读入。 */
    private suspend fun buildExclusionMap(): Map<Long, Set<Long>> {
        val rows = dedupExclusionDao.all()
        if (rows.isEmpty()) return emptyMap()
        val map = HashMap<Long, MutableSet<Long>>()
        for (r in rows) map.getOrPut(r.imageId) { mutableSetOf() }.add(r.cohortId)
        return map
    }

    companion object {
        const val ALGORITHM_VERSION = 2
        /** 去重投影分页大小（keyset 游标）。 */
        private const val DEDUP_PAGE = 2000

        /**
         * 视觉候选全内存聚类的条目上限：单条 Item ≈ 250B（含两个 32B 特征数组与装箱 phash），
         * 15w ≈ 40MB。超过则按「带 × 键区间」分片多轮扫描，内存只保留当前区间。
         */
        private const val IN_MEMORY_LIMIT = 150_000

        /** 单个 16bit 键的条目硬顶：病态倾斜桶（同版式截图海量同键）跳过不聚，防内存失控。 */
        private const val SINGLE_KEY_HARD_CAP = 300_000

        /** 组成员分批装载阈值 / getByIds 分块大小（< SQLite 变量上限）。 */
        private const val MEMBER_FLUSH_THRESHOLD = 2000
        private const val GET_BY_IDS_CHUNK = 500
    }
}
