package com.xjw.qalbum.service.analysis

import com.xjw.qalbum.data.db.entity.ImageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoringEngineTest {

    @Test
    fun duplicateNonBestReachesBalancedThreshold() {
        val image = image(
            duplicateGroupId = 1L,
            isDuplicateBest = false,
            width = 1080,
            height = 1920,
        )

        assertTrue(ScoringEngine.computeDeleteScore(image) >= BALANCED_THRESHOLD)
    }

    @Test
    fun largeDuplicateNonBestStillReachesBalancedThreshold() {
        val image = image(
            duplicateGroupId = 1L,
            isDuplicateBest = false,
            width = 3000,
            height = 2000,
            size = 3_000_000L,
        )

        assertTrue(ScoringEngine.computeDeleteScore(image) >= BALANCED_THRESHOLD)
    }

    @Test
    fun veryLowResolutionReachesBalancedThreshold() {
        val image = image(width = 120, height = 120)

        assertTrue(ScoringEngine.computeDeleteScore(image) >= BALANCED_THRESHOLD)
    }

    @Test
    fun lowResolutionNeedsAnotherSignalForBalancedThreshold() {
        val image = image(width = 400, height = 400, blurScore = 80f)

        assertTrue(ScoringEngine.computeDeleteScore(image) >= BALANCED_THRESHOLD)
    }

    @Test
    fun explicitKeepClearsDeleteScore() {
        val image = image(
            duplicateGroupId = 1L,
            isDuplicateBest = false,
            userKeep = true,
        )

        assertEquals(0f, ScoringEngine.computeDeleteScore(image), 0.0001f)
    }

    private fun image(
        width: Int = 1080,
        height: Int = 1920,
        size: Long = 512_000L,
        blurScore: Float? = 1000f,
        duplicateGroupId: Long? = null,
        isDuplicateBest: Boolean = false,
        userKeep: Boolean? = null,
    ): ImageEntity = ImageEntity(
        path = "/tmp/test.jpg",
        filename = "test.jpg",
        parentDir = "/tmp",
        sourceType = "chatimg",
        cacheKey = "test",
        size = size,
        mtime = 1L,
        firstSeen = 1L,
        lastSeen = 1L,
        mime = "image/jpeg",
        width = width,
        height = height,
        blurScore = blurScore,
        duplicateGroupId = duplicateGroupId,
        isDuplicateBest = isDuplicateBest,
        userKeep = userKeep,
    )

    private companion object {
        const val BALANCED_THRESHOLD = 0.5f
    }
}
