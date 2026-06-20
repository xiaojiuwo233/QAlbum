package com.xjw.qalbum.service.analysis

import kotlin.math.abs
import kotlin.math.max

/**
 * 重复聚类核心（纯函数，可单测）。
 *
 * 两类重复：
 * - **精确**：xxHash 完全相同 —— 一定同内容，可靠。
 * - **视觉**：pHash 距离近 —— 但 pHash 对「文字页 / 代码截图 / 纯色」这类低频结构雷同的图区分度差，
 *   仅靠距离会把大量**不相关**图经 union-find 传递聚成巨型组。故视觉聚类额外要求：
 *   1) 距离 < [visualThreshold]（收紧）；2) **宽高比接近**（[aspectTolerance]，挡掉「尺寸大不相同」的误聚）；
 *   3) 连通分量大小 ≤ [maxVisualGroupSize]（兜底，超大视觉组判定为误聚直接丢弃）。
 *
 * 精确重复不受这些限制（同一图被转发上百次是合法的大组）。
 */
object DuplicateClusterer {

    data class Item(
        val id: Long,
        val xxhash: String?,
        val phash: Long?,
        val width: Int,
        val height: Int,
        val size: Long,
    )

    data class Assignment(val id: Long, val groupId: Long, val isBest: Boolean)

    const val DEFAULT_VISUAL_THRESHOLD = 8
    const val DEFAULT_ASPECT_TOLERANCE = 0.12
    const val DEFAULT_MAX_VISUAL_GROUP = 20

    fun cluster(
        items: List<Item>,
        startGroupId: Long,
        visualThreshold: Int = DEFAULT_VISUAL_THRESHOLD,
        aspectTolerance: Double = DEFAULT_ASPECT_TOLERANCE,
        maxVisualGroupSize: Int = DEFAULT_MAX_VISUAL_GROUP,
    ): List<Assignment> {
        val assignments = mutableListOf<Assignment>()
        var nextGroup = startGroupId
        val assignedExact = HashSet<Long>()

        // 1) 精确重复：xxHash 相同（可靠），不受视觉约束限制（同图被转发上百次是合法大组）
        items.filter { it.xxhash != null }
            .groupBy { it.xxhash!! }
            .values
            .forEach { members ->
                if (members.size <= 1) return@forEach
                val best = members.maxByOrNull(::qualityKey)!!
                members.forEach {
                    assignments += Assignment(it.id, nextGroup, it.id == best.id)
                    assignedExact += it.id
                }
                nextGroup++
            }

        // 2) 视觉重复：未被精确分组、且有 pHash 的图
        val visualItems = items.filter { it.id !in assignedExact && it.phash != null }
        if (visualItems.isNotEmpty()) {
            val byId = visualItems.associateBy { it.id }
            val uf = UnionFind(visualItems.map { it.id })
            // 高 16 位分桶，桶内两两比较，避免 O(N²)
            visualItems.groupBy { (it.phash!! ushr 48) and 0xFFFF }.values.forEach { bucket ->
                for (i in bucket.indices) {
                    for (j in i + 1 until bucket.size) {
                        val a = bucket[i]
                        val b = bucket[j]
                        if (hamming(a.phash!!, b.phash!!) < visualThreshold && aspectClose(a, b, aspectTolerance)) {
                            uf.union(a.id, b.id)
                        }
                    }
                }
            }
            uf.components().values.forEach { ids ->
                if (ids.size <= 1) return@forEach
                if (ids.size > maxVisualGroupSize) return@forEach // 超大视觉组 = 误聚，整组丢弃
                val members = ids.mapNotNull { byId[it] }
                val best = members.maxByOrNull(::qualityKey) ?: return@forEach
                members.forEach { assignments += Assignment(it.id, nextGroup, it.id == best.id) }
                nextGroup++
            }
        }
        return assignments
    }

    /** 质量排序键（像素优先、再比文件大小）：用于选组内保留图（best） */
    private fun qualityKey(item: Item): Long = item.width.toLong() * item.height * 1000 + item.size

    private class UnionFind(initial: List<Long>) {
        private val parent = HashMap<Long, Long>().apply { initial.forEach { put(it, it) } }
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
        fun components(): Map<Long, List<Long>> = parent.keys.groupBy { find(it) }
    }

    fun hamming(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    /** 宽高比是否接近（任一缺尺寸则视为不接近，保守不聚） */
    fun aspectClose(a: Item, b: Item, tolerance: Double): Boolean {
        if (a.width <= 0 || a.height <= 0 || b.width <= 0 || b.height <= 0) return false
        val arA = a.width.toDouble() / a.height
        val arB = b.width.toDouble() / b.height
        return abs(arA - arB) <= tolerance * max(arA, arB)
    }
}
