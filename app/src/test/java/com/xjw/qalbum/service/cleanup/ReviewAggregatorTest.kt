package com.xjw.qalbum.service.cleanup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewAggregatorTest {

    @Test
    fun singlesTakenUpToMax() {
        val candidates = (1..5).map { c(it.toLong(), reason = "模糊") }
        val items = ReviewAggregator.aggregate(candidates, maxItems = 3)
        assertEquals(3, items.size)
        assertTrue(items.all { it is ReviewBatchItem.Single })
    }

    @Test
    fun duplicateGroupCountsAsOneItem() {
        val candidates = listOf(
            c(1, group = 7, reason = "重复"),
            c(2, group = 7, reason = "重复"),
            c(3, group = 7, reason = "重复"),
            c(10, reason = "模糊"),
            c(11, reason = "低分辨率"),
        )
        val items = ReviewAggregator.aggregate(candidates, maxItems = 10)
        assertEquals(3, items.size) // Group(7) + 2 Single
        assertEquals(ReviewBatchItem.Group(7), items[0])
    }

    @Test
    fun sameGroupExpandedOnce() {
        val candidates = listOf(c(1, group = 7, reason = "重复"), c(2, group = 7, reason = "重复"))
        val items = ReviewAggregator.aggregate(candidates, maxItems = 10)
        assertEquals(listOf(ReviewBatchItem.Group(7)), items)
    }

    @Test
    fun maxItemsCountsGroupAsOneItem() {
        // 关键：重复组算 1 项，所以 maxItems=2 时是 Group(7) + 第一个单图，第二个单图被截
        val candidates = listOf(
            c(1, group = 7, reason = "重复"),
            c(2, group = 7, reason = "重复"),
            c(10, reason = "模糊"),
            c(11, reason = "低分辨率"),
        )
        val items = ReviewAggregator.aggregate(candidates, maxItems = 2)
        assertEquals(2, items.size)
        assertEquals(ReviewBatchItem.Group(7), items[0])
        assertEquals(ReviewBatchItem.Single(10, "模糊"), items[1])
    }

    @Test
    fun groupMemberWithNonDuplicateReasonStillGrouped() {
        // 属重复组的图即使因「模糊」入选，也归组，避免又单独成项
        val candidates = listOf(c(5, group = 9, reason = "模糊"))
        val items = ReviewAggregator.aggregate(candidates, maxItems = 10)
        assertEquals(listOf(ReviewBatchItem.Group(9)), items)
    }

    @Test
    fun orderPreserved() {
        val candidates = listOf(
            c(10, reason = "模糊"),
            c(1, group = 7, reason = "重复"),
            c(20, reason = "表情包"),
        )
        val items = ReviewAggregator.aggregate(candidates, maxItems = 10)
        assertEquals(
            listOf(
                ReviewBatchItem.Single(10, "模糊"),
                ReviewBatchItem.Group(7),
                ReviewBatchItem.Single(20, "表情包"),
            ),
            items,
        )
    }

    @Test
    fun duplicateSingleImageIdDedup() {
        val candidates = listOf(c(42, reason = "模糊"), c(42, reason = "低分辨率"))
        val items = ReviewAggregator.aggregate(candidates, maxItems = 10)
        assertEquals(listOf(ReviewBatchItem.Single(42, "模糊")), items)
    }

    private fun c(imageId: Long, group: Long? = null, reason: String) =
        ReviewAggregator.Candidate(imageId, group, reason)
}
