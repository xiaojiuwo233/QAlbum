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
        assertEquals(1, result.map { it.groupId }.toSet().size)
        assertEquals(2L, result.first { it.isBest }.id)
    }

    @Test
    fun visualSimilarAllFeaturesCloseClustered() {
        // pHash 近 + blockHash 近 + 特征网格近（真副本）+ 同宽高比 → 聚
        val items = listOf(
            item(1, phash = 0L, blockHash = blockHash(), featureGrid = feature(), w = 800, h = 800),
            item(2, phash = 0b111L, blockHash = blockHash(), featureGrid = feature(), w = 400, h = 400),
        )
        val result = DuplicateClusterer.cluster(items, startGroupId = 5)
        assertEquals(setOf(1L, 2L), result.map { it.id }.toSet())
        assertEquals(1, result.map { it.groupId }.toSet().size)
    }

    @Test
    fun visualSimilarButDifferentLumaNotClustered() {
        // 核心：blockHash 近、pHash 近、宽高比同，但亮度网格差异大（深背景 vs 浅背景）→ 不聚
        val items = listOf(
            item(1, phash = 0L, blockHash = blockHash(), featureGrid = feature(luma = 0), w = 500, h = 500),
            item(2, phash = 0b111L, blockHash = blockHash(), featureGrid = feature(luma = 255), w = 500, h = 500),
        )
        val result = DuplicateClusterer.cluster(items, startGroupId = 1)
        assertTrue("亮度差异大不应聚为重复：$result", result.isEmpty())
    }

    @Test
    fun visualSimilarButDifferentEdgeNotClustered() {
        // blockHash 近、亮度近，但边缘密度差异大（密集文字 vs 稀疏图表）→ 不聚
        val items = listOf(
            item(1, phash = 0L, blockHash = blockHash(), featureGrid = feature(edge = 10), w = 500, h = 500),
            item(2, phash = 0b111L, blockHash = blockHash(), featureGrid = feature(edge = 255), w = 500, h = 500),
        )
        val result = DuplicateClusterer.cluster(items, startGroupId = 1)
        assertTrue("边缘密度差异大不应聚为重复：$result", result.isEmpty())
    }

    @Test
    fun visualDifferentBlockHashNotClustered() {
        // blockHash 差异大（dBlock 大）→ 模型判不相似 → 不聚
        val items = listOf(
            item(1, phash = 0L, blockHash = blockHash(), featureGrid = feature(), w = 500, h = 500),
            item(2, phash = 0b111L, blockHash = blockHash(0..15), featureGrid = feature(), w = 500, h = 500),
        )
        val result = DuplicateClusterer.cluster(items, startGroupId = 1)
        assertTrue("blockHash 差异大不应聚：$result", result.isEmpty())
    }

    @Test
    fun visualSimilarButDifferentAspectNotClustered() {
        // 各视觉特征近，但宽高比差很大（1.0 vs 2.0）→ 硬门槛挡住，不聚
        val items = listOf(
            item(1, phash = 0L, blockHash = blockHash(), featureGrid = feature(), w = 1000, h = 1000),
            item(2, phash = 0b11L, blockHash = blockHash(), featureGrid = feature(), w = 2000, h = 1000),
        )
        assertTrue(DuplicateClusterer.cluster(items, startGroupId = 1).isEmpty())
    }

    @Test
    fun visualDistantPhashNotClustered() {
        // 视觉特征近，但 pHash 距离远（16 >= 8）→ 硬门槛挡住，不聚
        val items = listOf(
            item(1, phash = 0L, blockHash = blockHash(), featureGrid = feature(), w = 500, h = 500),
            item(2, phash = 0xFFFFL, blockHash = blockHash(), featureGrid = feature(), w = 500, h = 500),
        )
        assertTrue(DuplicateClusterer.cluster(items, startGroupId = 1).isEmpty())
    }

    @Test
    fun nullBlockHashNotClusteredVisually() {
        val items = listOf(
            item(1, phash = 0L, blockHash = null, featureGrid = feature(), w = 500, h = 500),
            item(2, phash = 0L, blockHash = null, featureGrid = feature(), w = 500, h = 500),
        )
        assertTrue(DuplicateClusterer.cluster(items, startGroupId = 1).isEmpty())
    }

    @Test
    fun nullFeatureGridNotClusteredVisually() {
        // 特征网格尚未回填（null）→ 缺精筛信号，保守不聚
        val items = listOf(
            item(1, phash = 0L, blockHash = blockHash(), featureGrid = null, w = 500, h = 500),
            item(2, phash = 0L, blockHash = blockHash(), featureGrid = null, w = 500, h = 500),
        )
        assertTrue(DuplicateClusterer.cluster(items, startGroupId = 1).isEmpty())
    }

    @Test
    fun blankImagesNotClusteredVisually() {
        val items = listOf(
            item(1, phash = 0L, blockHash = blockHash(), featureGrid = feature(), w = 500, h = 500, isBlank = true),
            item(2, phash = 0L, blockHash = blockHash(), featureGrid = feature(), w = 500, h = 500, isBlank = true),
        )
        assertTrue(DuplicateClusterer.cluster(items, startGroupId = 1).isEmpty())
    }

    @Test
    fun oversizeVisualGroupDropped() {
        // 30 张全特征相近 → 连通分量 > 20 → 判定误聚，整组丢弃
        val items = (1..30).map {
            item(it.toLong(), phash = 0L, blockHash = blockHash(), featureGrid = feature(), w = 600, h = 600)
        }
        val result = DuplicateClusterer.cluster(items, startGroupId = 1, maxVisualGroupSize = 20)
        assertTrue("超大视觉组应判定误聚丢弃：${result.size}", result.isEmpty())
    }

    @Test
    fun exactMembersExcludedFromVisual() {
        val items = listOf(
            item(1, xxhash = "H", phash = 0L, blockHash = blockHash(), featureGrid = feature(), w = 500, h = 500),
            item(2, xxhash = "H", phash = 0L, blockHash = blockHash(), featureGrid = feature(), w = 500, h = 500),
            item(3, phash = 0L, blockHash = blockHash(), featureGrid = feature(), w = 500, h = 500),
        )
        val result = DuplicateClusterer.cluster(items, startGroupId = 1)
        val groupOf = result.associate { it.id to it.groupId }
        assertEquals(groupOf[1L], groupOf[2L])
        assertTrue("3 不应被分配", 3L !in groupOf)
    }

    @Test
    fun excludedCohortNotClusteredEvenIfIdentical() {
        // 用户拆分过的组：1、2 同属 cohort 100，即使各特征完全相同也不再聚
        val items = listOf(
            item(1, phash = 0L, blockHash = blockHash(), featureGrid = feature(), w = 500, h = 500),
            item(2, phash = 0L, blockHash = blockHash(), featureGrid = feature(), w = 500, h = 500),
        )
        val exclusions = mapOf(1L to setOf(100L), 2L to setOf(100L))
        val result = DuplicateClusterer.cluster(items, startGroupId = 1, exclusions = exclusions)
        assertTrue("同一排斥 cohort 的图不应再聚：$result", result.isEmpty())
    }

    @Test
    fun differentCohortsStillCluster() {
        val items = listOf(
            item(1, phash = 0L, blockHash = blockHash(), featureGrid = feature(), w = 500, h = 500),
            item(2, phash = 0L, blockHash = blockHash(), featureGrid = feature(), w = 500, h = 500),
        )
        val exclusions = mapOf(1L to setOf(100L), 2L to setOf(200L))
        val result = DuplicateClusterer.cluster(items, startGroupId = 1, exclusions = exclusions)
        assertEquals(1, result.map { it.groupId }.toSet().size)
    }

    @Test
    fun singletonsNotAssigned() {
        val items = listOf(
            item(1, xxhash = "A", phash = 1L, blockHash = blockHash(), featureGrid = feature(), w = 100, h = 100),
            item(2, xxhash = "B", phash = 0xFFFFL, blockHash = blockHash(0..15), featureGrid = feature(luma = 200), w = 999, h = 100),
        )
        assertTrue(DuplicateClusterer.cluster(items, startGroupId = 1).isEmpty())
    }

    /** 256bit（32 字节）分块指纹。默认全 0；[diffBytes] 指定字节置 0xFF（0..15 = 128 bit 差异）。 */
    private fun blockHash(diffBytes: IntRange? = null): ByteArray =
        ByteArray(32).also { ba -> diffBytes?.forEach { ba[it] = 0xFF.toByte() } }

    /** 32 字节特征网格：前 16 = 亮度网格（全 [luma]），后 16 = 边缘网格（全 [edge]）。 */
    private fun feature(luma: Int = 0, edge: Int = 0): ByteArray =
        ByteArray(32).also { ba ->
            for (i in 0 until 16) ba[i] = luma.toByte()
            for (i in 16 until 32) ba[i] = edge.toByte()
        }

    private fun item(
        id: Long,
        xxhash: String? = null,
        phash: Long? = null,
        blockHash: ByteArray? = null,
        featureGrid: ByteArray? = null,
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
        featureGrid = featureGrid,
        isBlank = isBlank,
    )
}
