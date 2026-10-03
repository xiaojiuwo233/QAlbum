package com.xjw.qalbum.service.analysis

import kotlin.math.abs
import kotlin.math.max

/**
 * 重复聚类核心（纯函数，可单测）。
 *
 * 两类重复：
 * - **精确**：xxHash 完全相同 —— 一定同内容，可靠，不受任何视觉约束。
 * - **视觉**：多信号**加权合议**（[SimilarityModel]），避免把「截图 / 文档」这类结构雷同、内容不同的图误聚。
 *   连边需满足硬门槛（pHash 距离 < [visualThreshold]、宽高比接近、非纯色、未被人工拆分排斥）+
 *   模型判定相似：`model.isSimilar(dBlock, dLuma, dEdge)`，其中
 *   - dBlock = blockHash 归一化距离（整体梯度结构）
 *   - dLuma  = 亮度网格归一化距离（深/浅背景、明暗布局）
 *   - dEdge  = 边缘网格归一化距离（文字密度 / 图表 vs 文字）
 *   随后连通分量 > [maxVisualGroupSize] 整组判定误聚丢弃（兜底）。
 *   缺任一视觉特征（pHash/blockHash/featureGrid）或纯色图不参与视觉聚类。
 *
 * 候选对生成用 **pHash 多带 LSH**（[BAND_COUNT] 个 [BAND_BITS] bit 带，各带独立分桶，桶内两两）：
 * 单带高 16 位分桶时，桶键恰落在 DCT 最高频（重压缩最易翻转）的位上，d=3 的真近重对同桶概率仅
 * 约 41%、d=7 只剩 12%；4×16 带按鸽笼原理保证 d≤3 必有一带完全相同（召回 100%），d=4..7 也有
 * 多带兜底。8×8 带虽可对 d≤7 全保证，但 8bit 键空间在百万级图上单桶配对量不可行，不取。
 *
 * 桶内两两比较**廉价门槛在前**（已连通跳过 → pHash 汉明 → 宽高比 → 人工排斥），全部通过才计算
 * 三种特征距离并请模型合议——倾斜大桶（聊天截图库常见）里绝大多数配对在第一二步就被淘汰。
 */
object DuplicateClusterer {

    data class Item(
        val id: Long,
        val xxhash: String?,
        val phash: Long?,
        val width: Int,
        val height: Int,
        val size: Long,
        /** 256bit（32 字节）分块指纹（整体梯度结构）。 */
        val blockHash: ByteArray? = null,
        /** 32 字节特征网格：前 16 = 4×4 亮度网格，后 16 = 4×4 边缘密度网格。 */
        val featureGrid: ByteArray? = null,
        /** 纯色/空白图：排除出视觉聚类。 */
        val isBlank: Boolean = false,
        val isAnimated: Boolean = false,
        val mtime: Long = 0,
    ) {
        // 含 ByteArray，按 id 判等即可（不作集合 key）。
        override fun equals(other: Any?): Boolean = other is Item && other.id == id
        override fun hashCode(): Int = id.hashCode()
    }

    data class Assignment(
        val id: Long, val groupId: Long, val isBest: Boolean,
        val expectedSize: Long = 0, val expectedMtime: Long = 0,
    )

    const val DEFAULT_VISUAL_THRESHOLD = 8
    const val DEFAULT_ASPECT_TOLERANCE = 0.12
    const val DEFAULT_MAX_VISUAL_GROUP = 20
    const val BLOCK_HASH_BITS = 256
    const val GRID_BYTES = 16

    /** pHash 多带 LSH：64bit 拆 [BAND_COUNT] 个 [BAND_BITS] bit 带。 */
    const val BAND_COUNT = 4
    const val BAND_BITS = 16
    const val BAND_KEY_SPACE = 1 shl BAND_BITS

    /** 第 [band] 带的桶键（0 ≤ band < [BAND_COUNT]）。 */
    fun bandKey(phash: Long, band: Int): Int = ((phash ushr (band * BAND_BITS)) and 0xFFFFL).toInt()

    fun cluster(
        items: List<Item>,
        startGroupId: Long,
        visualThreshold: Int = DEFAULT_VISUAL_THRESHOLD,
        model: SimilarityModel = SimilarityModel.DEFAULT,
        aspectTolerance: Double = DEFAULT_ASPECT_TOLERANCE,
        maxVisualGroupSize: Int = DEFAULT_MAX_VISUAL_GROUP,
        exclusions: Map<Long, Set<Long>> = emptyMap(),
    ): List<Assignment> {
        val assignments = mutableListOf<Assignment>()
        var nextGroup = startGroupId
        val assignedExact = HashSet<Long>()

        // 1) 精确重复：xxHash 相同（可靠），不受视觉约束限制
        items.filter { it.xxhash != null }
            .groupBy { it.xxhash!! to it.size }
            .values
            .forEach { members ->
                if (members.size <= 1) return@forEach
                if (members.indices.any { i -> members.take(i).any { sharesCohort(exclusions, it.id, members[i].id) } }) return@forEach
                val best = members.maxWithOrNull(qualityComparator)!!
                members.forEach {
                    assignments += Assignment(it.id, nextGroup, it.id == best.id, it.size, it.mtime)
                    assignedExact += it.id
                }
                nextGroup++
            }

        // 2) 视觉重复：未被精确分组、有 pHash + blockHash + featureGrid、且非纯色的图
        val visualItems = items.filter {
            it.id !in assignedExact && isVisualEligible(it.phash, it.blockHash, it.featureGrid, it.isBlank, it.isAnimated)
        }
        if (visualItems.isNotEmpty()) {
            val byId = visualItems.associateBy { it.id }
            val uf = LazyUnionFind()
            matchAllBands(visualItems, uf, visualThreshold, model, aspectTolerance, exclusions)
            uf.components().values.forEach { ids ->
                if (ids.size <= 1) return@forEach
                if (ids.size > maxVisualGroupSize) return@forEach // 超大视觉组 = 误聚，整组丢弃
                val members = ids.mapNotNull { byId[it] }
                if (members.size <= 1) return@forEach
                for (group in partitionVisualComponent(members, visualThreshold, model, aspectTolerance, exclusions)) {
                    val best = group.maxWithOrNull(qualityComparator)!!
                    group.forEach { assignments += Assignment(it.id, nextGroup, it.id == best.id, it.size, it.mtime) }
                    nextGroup++
                }
            }
        }
        return assignments
    }

    /** 是否具备参与视觉聚类的全部前提（缺任一特征 / 纯色 → 保守不聚）。 */
    fun isVisualEligible(
        phash: Long?, blockHash: ByteArray?, featureGrid: ByteArray?, isBlank: Boolean, isAnimated: Boolean = false,
    ): Boolean = phash != null && blockHash?.size == BLOCK_HASH_BITS / 8 &&
        featureGrid?.size == GRID_BYTES * 2 && !isBlank && !isAnimated

    /** 相似关系不具传递性；最终组内任意两张都必须通过直接比较。 */
    fun partitionVisualComponent(
        members: List<Item>,
        visualThreshold: Int,
        model: SimilarityModel,
        aspectTolerance: Double = DEFAULT_ASPECT_TOLERANCE,
        exclusions: Map<Long, Set<Long>> = emptyMap(),
    ): List<List<Item>> {
        val groups = ArrayList<MutableList<Item>>()
        for (item in members.sortedWith(qualityComparator.reversed())) {
            if (!isVisualEligible(item.phash, item.blockHash, item.featureGrid, item.isBlank, item.isAnimated)) continue
            val group = groups.firstOrNull { group ->
                group.all { isDirectVisualMatch(it, item, visualThreshold, model, aspectTolerance, exclusions) }
            }
            if (group == null) groups.add(arrayListOf(item)) else group.add(item)
        }
        return groups.filter { it.size > 1 }
    }

    fun isDirectVisualMatch(
        a: Item, b: Item, visualThreshold: Int, model: SimilarityModel,
        aspectTolerance: Double = DEFAULT_ASPECT_TOLERANCE,
        exclusions: Map<Long, Set<Long>> = emptyMap(),
    ): Boolean {
        if (!isVisualEligible(a.phash, a.blockHash, a.featureGrid, a.isBlank, a.isAnimated) ||
            !isVisualEligible(b.phash, b.blockHash, b.featureGrid, b.isBlank, b.isAnimated)) return false
        if (hamming(a.phash!!, b.phash!!) >= visualThreshold || !aspectClose(a, b, aspectTolerance) ||
            sharesCohort(exclusions, a.id, b.id)) return false
        return model.isSimilar(
            blockHashDistance(a.blockHash!!, b.blockHash!!) / BLOCK_HASH_BITS.toDouble(),
            lumaDistance(a.featureGrid!!, b.featureGrid!!),
            edgeDistance(a.featureGrid, b.featureGrid),
        )
    }

    /** 对 [items] 按全部带各建一次桶并做桶内配对，连边累积进 [uf]。 */
    fun matchAllBands(
        items: List<Item>,
        uf: LazyUnionFind,
        visualThreshold: Int,
        model: SimilarityModel,
        aspectTolerance: Double = DEFAULT_ASPECT_TOLERANCE,
        exclusions: Map<Long, Set<Long>> = emptyMap(),
        checkActive: () -> Unit = {},
    ) {
        for (band in 0 until BAND_COUNT) {
            items.groupBy { bandKey(it.phash!!, band) }.values.forEach { bucket ->
                checkActive()
                matchBucket(bucket, uf, visualThreshold, model, aspectTolerance, exclusions, checkActive)
            }
        }
    }

    /**
     * 单桶内两两配对判定，通过者 union 进 [uf]。廉价检查在前：已连通（多带重复配对天然去重）、
     * pHash 汉明门槛、宽高比、人工排斥；全过后才计算 blockHash/亮度/边缘三种距离 + 模型合议。
     * 要求桶内条目均已通过 [isVisualEligible]。
     */
    fun matchBucket(
        bucket: List<Item>,
        uf: LazyUnionFind,
        visualThreshold: Int,
        model: SimilarityModel,
        aspectTolerance: Double = DEFAULT_ASPECT_TOLERANCE,
        exclusions: Map<Long, Set<Long>> = emptyMap(),
        checkActive: () -> Unit = {},
    ) {
        if (bucket.size <= 1) return
        for (i in bucket.indices) {
            val a = bucket[i]
            for (j in i + 1 until bucket.size) {
                if (j and 1023 == 0) checkActive()
                val b = bucket[j]
                if (uf.connected(a.id, b.id)) continue
                if (isDirectVisualMatch(a, b, visualThreshold, model, aspectTolerance, exclusions)) uf.union(a.id, b.id)
            }
        }
    }

    /** 共享任一 cohort 的两图已被用户裁定「非重复」，禁止连边（人工拆分的持久化约束）。 */
    fun sharesCohort(exclusions: Map<Long, Set<Long>>, a: Long, b: Long): Boolean {
        val ca = exclusions[a] ?: return false
        val cb = exclusions[b] ?: return false
        return ca.any { it in cb }
    }

    /** 分辨率优先、文件大小次之、平手取较小 id；避免加权求和让大文件胜过更高分辨率。 */
    val qualityComparator: Comparator<Item> = compareBy<Item> { it.width.toLong() * it.height }
        .thenBy { it.size }.thenByDescending { it.id }

    /**
     * 惰性并查集：只为真正连过边的 id 建条目。全库预注册 200w 个装箱 Long 条目（约 160MB）
     * 是旧实现 OOM 的来源之一；重复子集远小于全库，惰性建条目后内存与「实际连边的图」成正比。
     */
    class LazyUnionFind {
        private val parent = HashMap<Long, Long>()

        val size: Int get() = parent.size

        fun find(x: Long): Long {
            var node = parent[x] ?: return x
            if (node == x) return x
            // 迭代式路径压缩
            var root = node
            while (true) {
                val p = parent[root] ?: break
                if (p == root) break
                root = p
            }
            var cur = x
            while (cur != root) {
                val next = parent[cur] ?: break
                parent[cur] = root
                cur = next
            }
            return root
        }

        /** 两 id 是否已在同一连通分量（任一从未连过边 → false，不建条目）。 */
        fun connected(a: Long, b: Long): Boolean {
            if (a !in parent || b !in parent) return false
            return find(a) == find(b)
        }

        fun union(a: Long, b: Long) {
            parent.putIfAbsent(a, a)
            parent.putIfAbsent(b, b)
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[ra] = rb
        }

        /** 根 → 成员列表（仅含连过边的 id；单例天然不出现）。 */
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

    /** featureGrid 前 16 字节（亮度网格）归一化 L1 距离 [0,1]。 */
    fun lumaDistance(a: ByteArray, b: ByteArray): Double = gridL1(a, b, 0)

    /** featureGrid 后 16 字节（边缘网格）归一化 L1 距离 [0,1]。 */
    fun edgeDistance(a: ByteArray, b: ByteArray): Double = gridL1(a, b, GRID_BYTES)

    private fun gridL1(a: ByteArray, b: ByteArray, offset: Int): Double {
        if (a.size < offset + GRID_BYTES || b.size < offset + GRID_BYTES) return 1.0
        var sum = 0
        for (i in 0 until GRID_BYTES) {
            sum += abs((a[offset + i].toInt() and 0xFF) - (b[offset + i].toInt() and 0xFF))
        }
        return sum / (GRID_BYTES * 255.0)
    }

    /** 宽高比是否接近（任一缺尺寸则视为不接近，保守不聚） */
    fun aspectClose(a: Item, b: Item, tolerance: Double): Boolean {
        if (a.width <= 0 || a.height <= 0 || b.width <= 0 || b.height <= 0) return false
        val arA = a.width.toDouble() / a.height
        val arB = b.width.toDouble() / b.height
        return abs(arA - arB) <= tolerance * max(arA, arB)
    }
}
