package com.xjw.qalbum.ui.cleanup

import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.service.cleanup.CleanupRules
import com.xjw.qalbum.service.cleanup.ReviewBatchItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun pruneGroup_deletePath_dropsSurvivorWhenOneLeft() {
        val group = ReviewItem.Group(
            groupId = 7,
            keeperId = 1,
            members = listOf(img(1, source = "chatraw", w = 1200, h = 1200), img(2, source = "chatimg", w = 800, h = 800)),
            defaultDeleteIds = setOf(2L),
        )
        // 批量删除路径：删掉劣质副本(2)，幸存的保留图(1)是用户隐含保留 → 整组移除
        assertNull(pruneReviewItem(group, setOf(2L), keepSingleton = false))
    }

    @Test
    fun pruneGroup_keepPath_convertsSurvivorToSingle() {
        val group = ReviewItem.Group(
            groupId = 7,
            keeperId = 1,
            members = listOf(img(1, source = "chatraw", w = 1200, h = 1200), img(2, source = "chatimg", w = 800, h = 800)),
            defaultDeleteIds = setOf(2L),
        )
        // 保留/单张处理路径：处理掉保留图(1)，剩下的副本(2)尚未决定 → 转单图留在审核
        val pruned = pruneReviewItem(group, setOf(1L), keepSingleton = true)
        assertTrue(pruned is ReviewItem.Single)
        pruned as ReviewItem.Single
        assertEquals(2L, pruned.image.id)
        assertEquals(CleanupRules.REASON_DUPLICATE, pruned.reason)
        assertFalse(pruned.defaultSelected)
    }

    @Test
    fun pruneGroup_keepPath_dropsWhenEmpty() {
        val group = ReviewItem.Group(
            groupId = 7,
            keeperId = 1,
            members = listOf(img(1), img(2)),
            defaultDeleteIds = setOf(2L),
        )
        assertNull(pruneReviewItem(group, setOf(1L, 2L), keepSingleton = true))
    }

    @Test
    fun pruneGroup_recomputesWhenTwoOrMoreRemain() {
        val group = ReviewItem.Group(
            groupId = 7,
            keeperId = 1,
            members = listOf(
                img(1, source = "chatraw", w = 1200, h = 1200),
                img(2, source = "chatimg", w = 800, h = 800),
                img(3, source = "chatimg", w = 600, h = 600),
            ),
            defaultDeleteIds = setOf(2L, 3L),
        )
        // 3 图组删 1 张，剩 2 张仍是可对比的重复组
        val pruned = pruneReviewItem(group, setOf(3L), keepSingleton = true)
        assertTrue(pruned is ReviewItem.Group)
        pruned as ReviewItem.Group
        assertEquals(2, pruned.members.size)
        assertEquals(1L, pruned.keeperId)
    }

    @Test
    fun pruneSingle_removedReturnsNull() {
        val single = ReviewItem.Single(img(1), CleanupRules.REASON_EMOJI, defaultSelected = true)
        assertNull(pruneReviewItem(single, setOf(1L), keepSingleton = true))
        // 未处理则原样保留
        assertEquals(single, pruneReviewItem(single, setOf(2L), keepSingleton = true))
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
