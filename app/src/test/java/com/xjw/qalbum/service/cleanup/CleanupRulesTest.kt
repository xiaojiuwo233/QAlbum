package com.xjw.qalbum.service.cleanup

import com.xjw.qalbum.data.db.dao.CleanupSignalRow
import com.xjw.qalbum.domain.model.CleanupConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CleanupRulesTest {

    @Test
    fun lowResolutionImageClassifiedWhenEnabled() {
        val signal = signal(width = 400, height = 400) // 160,000 像素
        val config = baseConfig.copy(lowResolutionEnabled = true, lowResolutionMaxPixels = 300_000)
        assertEquals(CleanupRules.REASON_LOW_RESOLUTION, CleanupRules.classify(signal, config))
    }

    @Test
    fun lowResolutionImageNotClassifiedWhenDisabled() {
        val signal = signal(width = 400, height = 400)
        val config = baseConfig.copy(lowResolutionEnabled = false)
        assertNull(CleanupRules.classify(signal, config))
    }

    @Test
    fun highResolutionImageNotClassifiedAsLowResolution() {
        val signal = signal(width = 2000, height = 2000) // 4,000,000 像素
        val config = baseConfig.copy(lowResolutionEnabled = true, lowResolutionMaxPixels = 300_000)
        assertNull(CleanupRules.classify(signal, config))
    }

    @Test
    fun blurryImageClassifiedWhenEnabled() {
        val signal = signal(width = 2000, height = 2000, blurScore = 50f)
        val config = baseConfig.copy(blurEnabled = true, blurMaxScore = 100f)
        assertEquals(CleanupRules.REASON_BLUR, CleanupRules.classify(signal, config))
    }

    @Test
    fun sharpImageNotClassifiedAsBlur() {
        val signal = signal(width = 2000, height = 2000, blurScore = 500f)
        val config = baseConfig.copy(blurEnabled = true, blurMaxScore = 100f)
        assertNull(CleanupRules.classify(signal, config))
    }

    @Test
    fun blurScoreNullNotClassifiedAsBlur() {
        // 未做质量分析（blurScore 为 null）不应被当作模糊
        val signal = signal(width = 2000, height = 2000, blurScore = null)
        val config = baseConfig.copy(blurEnabled = true, blurMaxScore = 100f)
        assertNull(CleanupRules.classify(signal, config))
    }

    @Test
    fun tinyFileClassifiedWhenEnabled() {
        val signal = signal(width = 2000, height = 2000, size = 10_240) // 10 KB
        val config = baseConfig.copy(tinyFileEnabled = true, tinyFileMaxBytes = 30_720)
        assertEquals(CleanupRules.REASON_TINY_FILE, CleanupRules.classify(signal, config))
    }

    @Test
    fun duplicateNonBestClassifiedWhenEnabled() {
        val signal = signal(width = 2000, height = 2000, duplicateGroupId = 1L, isDuplicateBest = false)
        val config = baseConfig.copy(duplicateEnabled = true)
        assertEquals(CleanupRules.REASON_DUPLICATE, CleanupRules.classify(signal, config))
    }

    @Test
    fun duplicateBestNotClassified() {
        val signal = signal(width = 2000, height = 2000, duplicateGroupId = 1L, isDuplicateBest = true)
        val config = baseConfig.copy(duplicateEnabled = true)
        assertNull(CleanupRules.classify(signal, config))
    }

    @Test
    fun corruptTakesPriorityOverLowResolution() {
        val signal = signal(width = 400, height = 400, isCorrupt = true)
        val config = baseConfig.copy(
            corruptEnabled = true,
            lowResolutionEnabled = true,
            lowResolutionMaxPixels = 300_000,
        )
        assertEquals(CleanupRules.REASON_CORRUPT, CleanupRules.classify(signal, config))
    }

    @Test
    fun cleanImageNotClassified() {
        val signal = signal(width = 2000, height = 2000, blurScore = 800f, size = 2_000_000)
        assertNull(CleanupRules.classify(signal, CleanupConfig.AGGRESSIVE))
    }

    @Test
    fun balancedPresetCatchesPlainLowResolution() {
        // 诉求 1：纯低分辨率清晰图，在默认（平衡）档就应该被扫到
        val signal = signal(width = 500, height = 500, blurScore = 1000f, size = 200_000) // 250,000 像素，清晰
        assertEquals(CleanupRules.REASON_LOW_RESOLUTION, CleanupRules.classify(signal, CleanupConfig.BALANCED))
    }

    @Test
    fun conservativePresetSkipsPlainLowResolution() {
        val signal = signal(width = 500, height = 500, blurScore = 1000f, size = 200_000)
        assertNull(CleanupRules.classify(signal, CleanupConfig.CONSERVATIVE))
    }

    @Test
    fun unanalyzedImageNotClassifiedAsLowResolution() {
        // width/height = 0（未做质量分析）不应被当作低分辨率
        val signal = signal(width = 0, height = 0)
        val config = baseConfig.copy(lowResolutionEnabled = true, lowResolutionMaxPixels = 300_000)
        assertNull(CleanupRules.classify(signal, config))
    }

    private val baseConfig = CleanupConfig(
        duplicateEnabled = false,
        lowResolutionEnabled = false,
        lowResolutionMaxPixels = 300_000,
        blurEnabled = false,
        blurMaxScore = 100f,
        tinyFileEnabled = false,
        tinyFileMaxBytes = 30_720,
        blankEnabled = false,
        corruptEnabled = false,
    )

    private fun signal(
        id: Long = 1L,
        width: Int = 1080,
        height: Int = 1920,
        blurScore: Float? = 1000f,
        size: Long = 500_000,
        isBlank: Boolean = false,
        isCorrupt: Boolean = false,
        duplicateGroupId: Long? = null,
        isDuplicateBest: Boolean = false,
        deleteScore: Float = 0f,
    ) = CleanupSignalRow(
        id = id,
        width = width,
        height = height,
        blurScore = blurScore,
        size = size,
        isBlank = isBlank,
        isCorrupt = isCorrupt,
        duplicateGroupId = duplicateGroupId,
        isDuplicateBest = isDuplicateBest,
        deleteScore = deleteScore,
    )
}
