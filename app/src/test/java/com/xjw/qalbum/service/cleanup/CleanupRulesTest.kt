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
        // 诉求 1：纯低分辨率清晰图，在默认（平衡）档就应该被扫到。
        // 用非正方形样本，避免与「表情包」维度（平衡档默认开启）重叠。
        val signal = signal(width = 700, height = 400, blurScore = 1000f, size = 200_000) // 280,000 像素，清晰，非正方形
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

    // ---- 表情包维度 ----

    @Test
    fun emojiClassifiedWhenEnabled() {
        val signal = signal(width = 640, height = 640)
        val config = baseConfig.copy(emojiEnabled = true)
        assertEquals(CleanupRules.REASON_EMOJI, CleanupRules.classify(signal, config))
    }

    @Test
    fun emojiNotClassifiedWhenDisabled() {
        val signal = signal(width = 640, height = 640)
        val config = baseConfig.copy(emojiEnabled = false)
        assertNull(CleanupRules.classify(signal, config))
    }

    @Test
    fun emojiSamplesAllClassified() {
        // 用户提供的真实样本（近似正方形、长边 ≤ 716）都应识别为表情包
        val config = baseConfig.copy(emojiEnabled = true)
        listOf(240 to 240, 515 to 524, 570 to 540, 640 to 640, 716 to 716).forEach { (w, h) ->
            assertEquals(
                "$w×$h 应识别为表情包",
                CleanupRules.REASON_EMOJI,
                CleanupRules.classify(signal(width = w, height = h), config),
            )
        }
    }

    @Test
    fun nonSquareImageNotClassifiedAsEmoji() {
        // 长宽差距大（0.5），不算表情包，避免误伤
        val signal = signal(width = 800, height = 400)
        val config = baseConfig.copy(emojiEnabled = true)
        assertNull(CleanupRules.classify(signal, config))
    }

    @Test
    fun largeSquareImageNotClassifiedAsEmoji() {
        // 1080×1080 正方形照片，长边超上限，不算表情包
        val signal = signal(width = 1080, height = 1080)
        val config = baseConfig.copy(emojiEnabled = true)
        assertNull(CleanupRules.classify(signal, config))
    }

    @Test
    fun tinyIconNotClassifiedAsEmoji() {
        // 32×32 小图标，短边低于下限，不算表情包
        val signal = signal(width = 32, height = 32)
        val config = baseConfig.copy(emojiEnabled = true)
        assertNull(CleanupRules.classify(signal, config))
    }

    @Test
    fun emojiTakesPriorityOverLowResolution() {
        // 小正方形图同时满足低分辨率与表情包时，归「表情包」
        val signal = signal(width = 240, height = 240) // 57,600 像素，也属低分
        val config = baseConfig.copy(
            emojiEnabled = true,
            lowResolutionEnabled = true,
            lowResolutionMaxPixels = 300_000,
        )
        assertEquals(CleanupRules.REASON_EMOJI, CleanupRules.classify(signal, config))
    }

    @Test
    fun corruptTakesPriorityOverEmoji() {
        val signal = signal(width = 240, height = 240, isCorrupt = true)
        val config = baseConfig.copy(emojiEnabled = true, corruptEnabled = true)
        assertEquals(CleanupRules.REASON_CORRUPT, CleanupRules.classify(signal, config))
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
        emojiEnabled = false,
    )

    private fun signal(
        id: Long = 1L,
        width: Int = 1080,
        height: Int = 1920,
        blurScore: Float? = 1000f,
        size: Long = 500_000,
        mtime: Long = 0L,
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
        mtime = mtime,
        isBlank = isBlank,
        isCorrupt = isCorrupt,
        duplicateGroupId = duplicateGroupId,
        isDuplicateBest = isDuplicateBest,
        deleteScore = deleteScore,
    )
}
