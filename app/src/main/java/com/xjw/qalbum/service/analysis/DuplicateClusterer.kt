package com.xjw.qalbum.service.analysis

import kotlin.math.abs
import kotlin.math.max

/**
 * 重复聚类核心（纯函数，可单测）。
 *
 * 两类重复：
 * - **精确**：xxHash 完全相同 —— 一定同内容，可靠，不受任何视觉约束（同一图被转发上百次是合法大组）。
 * - **视觉**：多信号合议，避免 pHash 单打独斗把「截图 / 文档 / 纯色」这类低频结构雷同、但内容不同的图误聚。
 *   连边需**同时**满足：
 *   1) pHash 距离 < [visualThreshold]（粗筛 + 高 16 位分桶，避免 O(N²)）；
 *   2) **分块指纹（blockHash, 256bit）距离 < [blockThreshold]** —— 精筛，要求「局部逐格一致」，
 *      这是区分截图的主力（「整体相似」对截图永远成立，「逐格一致」只对真副本成立）；
 *   3) **宽高比接近**（[aspectTolerance]）；
 *   随后连通分量大小 > [maxVisualGroupSize] 的整组判定误聚丢弃（兜底）。
 *   纯色图（[Item.isBlank]）与尚未回填 blockHash 的图不参与视觉聚类。
 */
object DuplicateClusterer {

    data class Item(
        val id: Long,
        val xxhash: String?,
        val phash: Long?,
        val width: Int,
        val height: Int,
        val size: Long,
        /** 256bit（32 字节）分块指纹：视觉去重的精筛信号，要求「局部逐格一致」。null = 尚未回填，不参与视觉聚类。 */
        val blockHash: ByteArray? = null,
        /** 纯色/空白图：任何 hash 都雷同，直接排除出视觉聚类，避免误聚。 */
        val isBlank: Boolean = false,
    ) {
        // blockHash 是 ByteArray，data class 默认 equals/hashCode 不按内容比较；本类仅以 id 标识、
        // 不作为集合 key，故只按 id 判等。显式重写以免 IDE 对 Array 字段告警。
        override fun equals(other: Any?): Boolean = other is Item && other.id == id
        override fun hashCode(): Int = id.hashCode()
    }

    data class Assignment(val id: Long, val groupId: Long, val isBest: Boolean)

    const val DEFAULT_VISUAL_THRESHOLD = 8
    const val DEFAULT_BLOCK_THRESHOLD = 40
    const val DEFAULT_ASPECT_TOLERANCE = 0.12
    const val DEFAULT_MAX_VISUAL_GROUP = 20

    fun cluster(
        items: List<Item>,
        startGroupId: Long,
        visualThreshold: Int = DEFAULT_VISUAL_THRESHOLD,
        blockThreshold: Int = DEFAULT_BLOCK_THRESHOLD,
        aspectTolerance: Double = DEFAULT_ASPECT_TOLERANCE,
        maxVisualGroupSize: Int = DEFAULT_MAX_VISUAL_GROUP,
        /** 人工拆分形成的「禁止再聚」约束：imageId → 所属 cohort 集合；共享任一 cohort 的两图不连边。 */
        exclusions: Map<Long, Set<Long>> = emptyMap(),
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

        // 2) 视觉重复：未被精确分组、有 pHash + blockHash、且非纯色的图
        val visualItems = items.filter {
            it.id !in assignedExact && it.phash != null && it.blockHash != null && !it.isBlank
        }
        if (visualItems.isNotEmpty()) {
            val byId = visualItems.associateBy { it.id }
            val uf = UnionFind(visualItems.map { it.id })
            // 共享任一 cohort 的两图已被用户裁定「非重复」，禁止连边（人工拆分的持久化约束）
            fun sharesCohort(a: Long, b: Long): Boolean {
                val ca = exclusions[a] ?: return false
                val cb = exclusions[b] ?: return false
                return ca.any { it in cb }
            }
            // 高 16 位分桶，桶内两两比较，避免 O(N²)
            visualItems.groupBy { (it.phash!! ushr 48) and 0xFFFF }.values.forEach { bucket ->
                for (i in bucket.indices) {
                    for (j in i + 1 until bucket.size) {
                        val a = bucket[i]
                        val b = bucket[j]
                        if (hamming(a.phash!!, b.phash!!) < visualThreshold &&
                            blockHashDistance(a.blockHash!!, b.blockHash!!) < blockThreshold &&
                            aspectClose(a, b, aspectTolerance) &&
                            !sharesCohort(a.id, b.id)
                        ) {
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

    /** 两个分块指纹（等长 ByteArray）的汉明距离；长度不等视为最大距离（判定不相似）。 */
    fun blockHashDistance(a: ByteArray, b: ByteArray): Int {
        if (a.size != b.size) return Int.MAX_VALUE
        var d = 0
        for (i in a.indices) d += Integer.bitCount((a[i].toInt() xor b[i].toInt()) and 0xFF)
        return d
    }

    /** 宽高比是否接近（任一缺尺寸则视为不接近，保守不聚） */
    fun aspectClose(a: Item, b: Item, tolerance: Double): Boolean {
        if (a.width <= 0 || a.height <= 0 || b.width <= 0 || b.height <= 0) return false
        val arA = a.width.toDouble() / a.height
        val arB = b.width.toDouble() / b.height
        return abs(arA - arB) <= tolerance * max(arA, arB)
    }
}
