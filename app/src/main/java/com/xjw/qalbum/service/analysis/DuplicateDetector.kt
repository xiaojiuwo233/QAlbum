package com.xjw.qalbum.service.analysis

import com.xjw.qalbum.data.db.dao.ImageDao
import timber.log.Timber

/**
 * 重复检测器
 *
 * 两种维度：
 * 1. **精确重复**：xxHash 完全相同 —— 一定是同一个内容
 * 2. **视觉相似**：pHash 距离 < 阈值 —— 不同文件但内容近似
 *
 * 重要：**重复组**与**变体组**是两个独立维度。
 * - 变体组：同一 `cache_key` 跨 chatimg/chatraw/chatthumb 出现，表示"同一张图的不同分辨率"
 * - 重复组：不同 cache_key 但内容相同/相似，表示"不同来源的同一张图"
 *
 * 排除规则：同变体组内的多个文件**不**参与"重复检测"——它们本来就是同一张图。
 */
class DuplicateDetector(private val imageDao: ImageDao) {

    /**
     * 基于 xxHash 做精确重复聚类
     * - 同一 xxhash 的图片视为重复组（排除同变体组内的）
     * - 主图按 (像素数, size) 取最大
     */
    suspend fun clusterExact() {
        val groups = imageDao.xxhashGroups()
        if (groups.isEmpty()) {
            Timber.i("DuplicateDetector.clusterExact: no exact-duplicate hashes")
            return
        }
        var nextGroupId = imageDao.maxDuplicateGroupId() + 1
        var assigned = 0

        for (row in groups) {
            val ids = row.ids.split(',').mapNotNull { it.toLongOrNull() }
            if (ids.size <= 1) continue

            // 把同一 variant_group 视为单个逻辑图，过滤后再判定
            val members = ids.mapNotNull { imageDao.getById(it) }
            val unique = members
                .distinctBy { it.variantGroup ?: -it.id }   // 同变体组合并；无变体组则用自身 id
            if (unique.size <= 1) continue   // 同变体组内的多文件不算"重复"

            val best = members.maxByOrNull { it.width.toLong() * it.height * 1000 + it.size }!!
            members.forEach { m ->
                imageDao.assignDuplicate(m.id, nextGroupId, isBest = m.id == best.id)
                assigned++
            }
            nextGroupId++
        }
        Timber.i("DuplicateDetector.clusterExact: assigned $assigned rows into ${nextGroupId - 1} groups")
    }

    /**
     * 基于 pHash 做视觉相似聚类（仅对每个变体组的主图）
     * 用"高 16 位分桶"做近邻初筛，再桶内两两比较，避免 O(N²)
     */
    suspend fun clusterVisual(threshold: Int = 10) {
        val rows = imageDao.primaryPhashes().mapNotNull { row ->
            val p = row.phash ?: return@mapNotNull null
            row.id to p
        }
        if (rows.size < 2) return

        // 高 16 位分桶
        val buckets = rows.groupBy { (_, hash) -> (hash ushr 48) and 0xFFFF }
        var nextGroupId = imageDao.maxDuplicateGroupId() + 1
        val unionFind = UnionFind(rows.map { it.first })

        for ((_, bucket) in buckets) {
            for (i in bucket.indices) {
                for (j in i + 1 until bucket.size) {
                    val (idA, hashA) = bucket[i]
                    val (idB, hashB) = bucket[j]
                    if (HashEngine.hammingDistance(hashA, hashB) < threshold) {
                        unionFind.union(idA, idB)
                    }
                }
            }
        }

        // 把 UnionFind 中的连通分量写回数据库
        var assigned = 0
        for ((_, ids) in unionFind.components()) {
            if (ids.size <= 1) continue
            val members = ids.mapNotNull { imageDao.getById(it) }
            val best = members.maxByOrNull { it.width.toLong() * it.height * 1000 + it.size } ?: continue
            members.forEach { m ->
                if (m.duplicateGroupId == null) {
                    imageDao.assignDuplicate(m.id, nextGroupId, isBest = m.id == best.id)
                    assigned++
                }
            }
            nextGroupId++
        }
        Timber.i("DuplicateDetector.clusterVisual: assigned $assigned new visual-duplicate rows")
    }

    /** 简易 Union-Find */
    private class UnionFind(initial: List<Long>) {
        private val parent = HashMap<Long, Long>().apply {
            initial.forEach { put(it, it) }
        }

        fun find(x: Long): Long {
            var node = x
            while (parent[node] != node) {
                parent[node] = parent[parent[node]] ?: node
                node = parent[node] ?: node
            }
            return node
        }

        fun union(a: Long, b: Long) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[ra] = rb
        }

        fun components(): Map<Long, List<Long>> =
            parent.keys.groupBy { find(it) }
    }
}
