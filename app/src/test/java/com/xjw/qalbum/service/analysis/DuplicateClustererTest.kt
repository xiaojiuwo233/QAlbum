package com.xjw.qalbum.service.analysis

import com.xjw.qalbum.service.analysis.DuplicateClusterer.Item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicateClustererTest {

    @Test
    fun exactXxhashClustersAndPicksLargestAsBest() {
        val items = listOf(
            item(1, xxhash = "H", w = 100, h = 100, size = 1_000),
            item(2, xxhash = "H", w = 200, h = 200, size = 4_000), // 最大 → best
        )
        val result = DuplicateClusterer.cluster(items, startGroupId = 1)
        assertEquals(setOf(1L, 2L), result.map { it.id }.toSet())
        assertEquals(1, result.map { it.groupId }.toSet().size) // 同一组
        assertEquals(2L, result.first { it.isBest }.id)
    }

    @Test
    fun visualSimilarWithMatchingBlockHashClustered() {
        // pHash 近 + blockHash 也近（真副本）+ 同宽高比 → 聚
        val items = listOf(
            item(1, phash = 0L, blockHash = blockHash(), w = 800, h = 800),
            item(2, phash = 0b111L, blockHash = blockHash(), w = 400, h = 400), // pHash 距离 3 < 8，blockHash 距离 0
        )
        val result = DuplicateClusterer.cluster(items, startGroupId = 5)
        assertEquals(setOf(1L, 2L), result.map { it.id }.toSet())
        assertEquals(1, result.map { it.groupId }.toSet().size)
    }

    @Test
    fun visualSimilarPhashButDifferentBlockHashNotClustered() {
        // 核心修复（截图场景）：pHash 很近、宽高比相同（同款手机截图），但 blockHash 差异大（内容不同）→ 不聚
        val items = listOf(
            item(1, phash = 0L, blockHash = blockHash(), w = 1080, h = 2400),
            item(2, phash = 0b111L, blockHash = blockHash(0..15), w = 1080, h = 2400), // pHash 距离 3<8，blockHash 距离 128
        )
        val result = DuplicateClusterer.cluster(items, startGroupId = 1)
        assertTrue("pHash 近但 blockHash 差异大不应聚为重复：$result", result.isEmpty())
    }

    @Test
    fun blankImagesNotClusteredVisually() {
        // 纯色图（isBlank）即使 pHash / blockHash 全同也不参与视觉聚类
        val items = listOf(
            item(1, phash = 0L, blockHash = blockHash(), w = 500, h = 500, isBlank = true),
            item(2, phash = 0L, blockHash = blockHash(), w = 500, h = 500, isBlank = true),
        )
        assertTrue(
            "纯色图不应进入视觉重复组",
            DuplicateClusterer.cluster(items, startGroupId = 1).isEmpty(),
        )
    }

    @Test
    fun visualSimilarButDifferentAspectNotClustered() {
        // pHash 近、blockHash 近，但宽高比差很大（1.0 vs 2.0）→ 不聚
        val items = listOf(
            item(1, phash = 0L, blockHash = blockHash(), w = 1000, h = 1000),
            item(2, phash = 0b11L, blockHash = blockHash(), w = 2000, h = 1000),
        )
        val result = DuplicateClusterer.cluster(items, startGroupId = 1)
        assertTrue("宽高比差异大不应聚为重复：$result", result.isEmpty())
    }

    @Test
    fun visualDistantPhashNotClustered() {
        // blockHash 相同，但 pHash 距离远（16 >= 8）→ 粗筛就挡住，不聚
        val items = listOf(
            item(1, phash = 0L, blockHash = blockHash(), w = 500, h = 500),
            item(2, phash = 0xFFFFL, blockHash = blockHash(), w = 500, h = 500),
        )
        assertTrue(DuplicateClusterer.cluster(items, startGroupId = 1).isEmpty())
    }

    @Test
    fun nullBlockHashNotClusteredVisually() {
        // blockHash 尚未回填（null）→ 缺精筛信号，保守不视觉聚类（等回填后再聚）
        val items = listOf(
            item(1, phash = 0L, blockHash = null, w = 500, h = 500),
            item(2, phash = 0L, blockHash = null, w = 500, h = 500),
        )
        assertTrue(DuplicateClusterer.cluster(items, startGroupId = 1).isEmpty())
    }

    @Test
    fun oversizeVisualGroupDropped() {
        // 30 张两两 pHash 相近、blockHash 相同、宽高比相同 → 连通分量 > 20 → 判定误聚，整组丢弃
        val items = (1..30).map { item(it.toLong(), phash = 0L, blockHash = blockHash(), w = 600, h = 600) }
        val result = DuplicateClusterer.cluster(items, startGroupId = 1, maxVisualGroupSize = 20)
        assertTrue("超大视觉组应判定误聚丢弃：${result.size}", result.isEmpty())
    }

    @Test
    fun exactMembersExcludedFromVisual() {
        // 1、2 精确重复（同 xxhash）；3 与它们 pHash / blockHash 也近，但 1、2 已被精确分组，不再参与视觉聚类
        val items = listOf(
            item(1, xxhash = "H", phash = 0L, blockHash = blockHash(), w = 500, h = 500),
            item(2, xxhash = "H", phash = 0L, blockHash = blockHash(), w = 500, h = 500),
            item(3, phash = 0L, blockHash = blockHash(), w = 500, h = 500),
        )
        val result = DuplicateClusterer.cluster(items, startGroupId = 1)
        // 1、2 一组；3 落单（无其他视觉成员）
        val groupOf = result.associate { it.id to it.groupId }
        assertEquals(groupOf[1L], groupOf[2L])
        assertTrue("3 不应被分配", 3L !in groupOf)
    }

    @Test
    fun singletonsNotAssigned() {
        val items = listOf(
            item(1, xxhash = "A", phash = 1L, blockHash = blockHash(), w = 100, h = 100),
            item(2, xxhash = "B", phash = 0xFFFFL, blockHash = blockHash(0..15), w = 999, h = 100),
        )
        assertTrue(DuplicateClusterer.cluster(items, startGroupId = 1).isEmpty())
    }

    @Test
    fun excludedCohortNotClusteredEvenIfIdentical() {
        // 用户拆分过的组：1、2 同属 cohort 100（排斥），即使 pHash / blockHash 完全相同也不再聚
        val items = listOf(
            item(1, phash = 0L, blockHash = blockHash(), w = 500, h = 500),
            item(2, phash = 0L, blockHash = blockHash(), w = 500, h = 500),
        )
        val exclusions = mapOf(1L to setOf(100L), 2L to setOf(100L))
        val result = DuplicateClusterer.cluster(items, startGroupId = 1, exclusions = exclusions)
        assertTrue("同一排斥 cohort 的图不应再聚：$result", result.isEmpty())
    }

    @Test
    fun differentCohortsStillCluster() {
        // 1、2 视觉相同但属不同 cohort（无共享）→ 不受排斥，仍聚
        val items = listOf(
            item(1, phash = 0L, blockHash = blockHash(), w = 500, h = 500),
            item(2, phash = 0L, blockHash = blockHash(), w = 500, h = 500),
        )
        val exclusions = mapOf(1L to setOf(100L), 2L to setOf(200L))
        val result = DuplicateClusterer.cluster(items, startGroupId = 1, exclusions = exclusions)
        assertEquals(1, result.map { it.groupId }.toSet().size)
    }

    /**
     * 构造 256bit（32 字节）分块指纹。默认全 0；[diffBytes] 指定的字节置 0xFF 用于制造差异
     * （每字节 8 bit，故 0..15 = 128 bit 差异）。
     */
    private fun blockHash(diffBytes: IntRange? = null): ByteArray =
        ByteArray(32).also { ba -> diffBytes?.forEach { ba[it] = 0xFF.toByte() } }

    private fun item(
        id: Long,
        xxhash: String? = null,
        phash: Long? = null,
        blockHash: ByteArray? = null,
        w: Int = 100,
        h: Int = 100,
        size: Long = 1_000,
        isBlank: Boolean = false,
    ) = Item(
        id = id,
        xxhash = xxhash,
        phash = phash,
        width = w,
        height = h,
        size = size,
        blockHash = blockHash,
        isBlank = isBlank,
    )
}
