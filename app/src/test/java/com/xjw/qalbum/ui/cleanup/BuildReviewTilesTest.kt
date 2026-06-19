package com.xjw.qalbum.ui.cleanup

import com.xjw.qalbum.data.db.entity.CleanupCandidateEntity
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.service.cleanup.CleanupRules
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [buildReviewTiles] 回归测试，重点守住「审核格子 image.id 全局唯一」这个不变量——
 * 它一旦被破坏，审核网格 LazyGrid 的 key `"tile-${image.id}"` 就会重复并崩溃
 * （IllegalArgumentException: Key "tile-979" was already used）。
 */
class BuildReviewTilesTest {

    @Test
    fun `同一张图既属重复组又是模糊候选时只出现一格`() = runBlocking {
        // 979：重复组 1 的成员，同时又因「模糊」单独成为候选 —— 正是线上崩溃的数据形态
        val a = img(979, group = 1)
        val b = img(980, group = 1)
        val candidates = listOf(
            candidate(979, CleanupRules.REASON_DUPLICATE),
            candidate(979, CleanupRules.REASON_BLUR),
        )
        val tiles = buildReviewTiles(
            candidates = candidates,
            candidateImages = mapOf(979L to a),
            candidateIds = setOf(979L),
            groupImages = { listOf(a, b) },
        )

        assertNoDuplicateTileKeys(tiles)
        // 979 与 980 各一格，且 979 归入重复组（而非模糊）
        assertEquals(setOf(979L, 980L), tiles.map { it.image.id }.toSet())
        val tile979 = tiles.first { it.image.id == 979L }
        assertEquals(1L, tile979.duplicateGroupId)
        assertEquals(CleanupRules.REASON_DUPLICATE, tile979.reason)
    }

    @Test
    fun `同一重复组的多个候选只展开一次`() = runBlocking {
        val a = img(1, group = 7)
        val b = img(2, group = 7)
        val candidates = listOf(
            candidate(1, CleanupRules.REASON_DUPLICATE),
            candidate(2, CleanupRules.REASON_DUPLICATE),
        )
        var groupCalls = 0
        val tiles = buildReviewTiles(
            candidates = candidates,
            candidateImages = mapOf(1L to a, 2L to b),
            candidateIds = setOf(1L, 2L),
            groupImages = { groupCalls++; listOf(a, b) },
        )

        assertNoDuplicateTileKeys(tiles)
        assertEquals(2, tiles.size)
        assertEquals(1, groupCalls) // 同组只查/展开一次
    }

    @Test
    fun `同一张图重复出现在候选里只成一格`() = runBlocking {
        val a = img(42)
        val candidates = listOf(
            candidate(42, CleanupRules.REASON_BLUR),
            candidate(42, CleanupRules.REASON_LOW_RESOLUTION),
        )
        val tiles = buildReviewTiles(
            candidates = candidates,
            candidateImages = mapOf(42L to a),
            candidateIds = setOf(42L),
            groupImages = { emptyList() },
        )

        assertNoDuplicateTileKeys(tiles)
        assertEquals(1, tiles.size)
    }

    @Test
    fun `重复组展开跳过非活跃图`() = runBlocking {
        val a = img(1, group = 3)
        val deleted = img(2, group = 3, status = "deleted")
        val tiles = buildReviewTiles(
            candidates = listOf(candidate(1, CleanupRules.REASON_DUPLICATE)),
            candidateImages = mapOf(1L to a),
            candidateIds = setOf(1L),
            groupImages = { listOf(a, deleted) },
        )

        assertNoDuplicateTileKeys(tiles)
        assertEquals(setOf(1L), tiles.map { it.image.id }.toSet())
    }

    private fun assertNoDuplicateTileKeys(tiles: List<ReviewTile>) {
        val ids = tiles.map { it.image.id }
        assertTrue("审核格子 image.id 必须唯一，否则 LazyGrid key 冲突崩溃：$ids", ids.size == ids.toSet().size)
    }

    private fun img(id: Long, group: Long? = null, status: String = "active") = ImageEntity(
        id = id,
        path = "/tmp/$id.jpg",
        filename = "$id.jpg",
        parentDir = "/tmp",
        sourceType = "chatimg",
        cacheKey = "$id",
        size = 1_000L,
        mtime = 1L,
        firstSeen = 1L,
        lastSeen = 1L,
        mime = "image/jpeg",
        width = 100,
        height = 100,
        duplicateGroupId = group,
        status = status,
    )

    private fun candidate(imageId: Long, reason: String) = CleanupCandidateEntity(
        batchId = 1L,
        imageId = imageId,
        reason = reason,
        riskLevel = "low",
        score = 0.5f,
    )
}
