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
    fun visualSimilarSameAspectClustered() {
        val items = listOf(
            item(1, phash = 0L, w = 800, h = 800),
            item(2, phash = 0b111L, w = 400, h = 400), // 距离 3 < 8，宽高比同(1.0)
        )
        val result = DuplicateClusterer.cluster(items, startGroupId = 5)
        assertEquals(setOf(1L, 2L), result.map { it.id }.toSet())
        assertEquals(1, result.map { it.groupId }.toSet().size)
    }

    @Test
    fun visualSimilarButDifferentAspectNotClustered() {
        // 核心修复：pHash 很近，但宽高比差很大（1.0 vs 2.0）→ 不聚
        val items = listOf(
            item(1, phash = 0L, w = 1000, h = 1000),
            item(2, phash = 0b11L, w = 2000, h = 1000),
        )
        val result = DuplicateClusterer.cluster(items, startGroupId = 1)
        assertTrue("宽高比差异大不应聚为重复：$result", result.isEmpty())
    }

    @Test
    fun visualDistantNotClustered() {
        val items = listOf(
            item(1, phash = 0L, w = 500, h = 500),
            item(2, phash = 0xFFFFL, w = 500, h = 500), // 距离 16 >= 8
        )
        assertTrue(DuplicateClusterer.cluster(items, startGroupId = 1).isEmpty())
    }

    @Test
    fun oversizeVisualGroupDropped() {
        // 30 张两两 pHash 相近、宽高比相同 → 连通分量 > 20 → 判定误聚，整组丢弃
        val items = (1..30).map { item(it.toLong(), phash = 0L, w = 600, h = 600) }
        val result = DuplicateClusterer.cluster(items, startGroupId = 1, maxVisualGroupSize = 20)
        assertTrue("超大视觉组应判定误聚丢弃：${result.size}", result.isEmpty())
    }

    @Test
    fun exactMembersExcludedFromVisual() {
        // 1、2 精确重复（同 xxhash）；3 与它们 pHash 也近，但 1、2 已被精确分组，不再参与视觉聚类
        val items = listOf(
            item(1, xxhash = "H", phash = 0L, w = 500, h = 500),
            item(2, xxhash = "H", phash = 0L, w = 500, h = 500),
            item(3, phash = 0L, w = 500, h = 500),
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
            item(1, xxhash = "A", phash = 1L, w = 100, h = 100),
            item(2, xxhash = "B", phash = 0xFFFFL, w = 999, h = 100),
        )
        assertTrue(DuplicateClusterer.cluster(items, startGroupId = 1).isEmpty())
    }

    private fun item(
        id: Long,
        xxhash: String? = null,
        phash: Long? = null,
        w: Int = 100,
        h: Int = 100,
        size: Long = 1_000,
    ) = Item(id = id, xxhash = xxhash, phash = phash, width = w, height = h, size = size)
}
