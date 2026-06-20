package com.xjw.qalbum.ui.cleanup

import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.service.cleanup.CleanupRules
import com.xjw.qalbum.service.cleanup.ReviewBatchItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [buildReviewItems] / [suggestedSelection] / [reasonByImageId] 回归测试。
 * 守住：折叠重复组、组内保留/勾选重算、预选只含高把握项、缺图/空组丢弃。
 */
class BuildReviewItemsTest {

    @Test
    fun singleHighConfidenceDefaultSelected() {
        val items = buildReviewItems(
            listOf(ReviewBatchItem.Single(1, CleanupRules.REASON_EMOJI)),
            mapOf(1L to img(1)),
        ) { emptyList() }
        assertTrue((items.single() as ReviewItem.Single).defaultSelected)
    }

    @Test
    fun singleLowConfidenceNotDefaultSelected() {
        val items = buildReviewItems(
            listOf(ReviewBatchItem.Single(1, CleanupRules.REASON_LOW_RESOLUTION)),
            mapOf(1L to img(1)),
        ) { emptyList() }
        assertFalse((items.single() as ReviewItem.Single).defaultSelected)
    }

    @Test
    fun groupBuiltWithKeeperAndInferiorDeletes() {
        val keeper = img(1, source = "chatraw", w = 1200, h = 1200)
        val dup = img(2, source = "chatimg", w = 800, h = 800)
        val items = buildReviewItems(
            listOf(ReviewBatchItem.Group(7)),
            emptyMap(),
        ) { listOf(keeper, dup) }
        val group = items.single() as ReviewItem.Group
        assertEquals(1L, group.keeperId)
        assertEquals(setOf(2L), group.defaultDeleteIds)
        assertEquals(2, group.members.size)
    }

    @Test
    fun emptyGroupDropped() {
        val items = buildReviewItems(listOf(ReviewBatchItem.Group(7)), emptyMap()) { emptyList() }
        assertTrue(items.isEmpty())
    }

    @Test
    fun missingSingleImageDropped() {
        val items = buildReviewItems(
            listOf(ReviewBatchItem.Single(99, CleanupRules.REASON_BLUR)),
            emptyMap(),
        ) { emptyList() }
        assertTrue(items.isEmpty())
    }

    @Test
    fun suggestedSelectionPicksHighConfidenceAndDuplicateInferiorOnly() {
        val items = buildReviewItems(
            listOf(
                ReviewBatchItem.Single(1, CleanupRules.REASON_EMOJI),
                ReviewBatchItem.Single(2, CleanupRules.REASON_LOW_RESOLUTION),
                ReviewBatchItem.Group(7),
            ),
            mapOf(1L to img(1), 2L to img(2)),
        ) { listOf(img(10, source = "chatraw", w = 1200, h = 1200), img(11, source = "chatimg", w = 800, h = 800)) }
        // 表情包(1) + 重复劣质副本(11)；低分辨率(2) 不预选；重复保留图(10) 不预选
        assertEquals(setOf(1L, 11L), suggestedSelection(items))
    }

    @Test
    fun reasonMappingSinglesAndGroups() {
        val items = buildReviewItems(
            listOf(ReviewBatchItem.Single(1, CleanupRules.REASON_EMOJI), ReviewBatchItem.Group(7)),
            mapOf(1L to img(1)),
        ) { listOf(img(10, source = "chatraw"), img(11, source = "chatimg")) }
        val map = reasonByImageId(items)
        assertEquals(CleanupRules.REASON_EMOJI, map[1L])
        assertEquals(CleanupRules.REASON_DUPLICATE, map[10L])
        assertEquals(CleanupRules.REASON_DUPLICATE, map[11L])
    }

    private fun img(
        id: Long,
        source: String = "chatimg",
        w: Int = 100,
        h: Int = 100,
        status: String = "active",
    ) = ImageEntity(
        id = id,
        path = "/tmp/$id.jpg",
        filename = "$id.jpg",
        parentDir = "/tmp",
        sourceType = source,
        cacheKey = "$id",
        size = 1_000L,
        mtime = 1L,
        firstSeen = 1L,
        lastSeen = 1L,
        mime = "image/jpeg",
        width = w,
        height = h,
        duplicateGroupId = 7L,
        status = status,
    )
}
