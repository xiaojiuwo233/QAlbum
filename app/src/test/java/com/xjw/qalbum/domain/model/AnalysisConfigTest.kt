package com.xjw.qalbum.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** 校验分析数量归一化：留空 / 0 / 负数 → 无限；正数保留；超大值 clamp 到上限。 */
class AnalysisConfigTest {

    @Test
    fun zeroOrNegativeIsUnlimited() {
        assertEquals(AnalysisConfig.UNLIMITED, normalizeAnalysisLimit(0))
        assertEquals(AnalysisConfig.UNLIMITED, normalizeAnalysisLimit(-5))
    }

    @Test
    fun positiveKept() {
        assertEquals(500, normalizeAnalysisLimit(500))
        assertEquals(100, normalizeAnalysisLimit(100))
    }

    @Test
    fun clampsToMax() {
        assertEquals(AnalysisConfig.MAX_LIMIT, normalizeAnalysisLimit(Int.MAX_VALUE))
    }

    @Test
    fun defaultsMatchSpec() {
        // hash 默认无限；ocr 500 / ai 100 / 向量 1000
        val d = AnalysisConfig.DEFAULT
        assertEquals(AnalysisConfig.UNLIMITED, d.hashLimit)
        assertEquals(500, d.ocrLimit)
        assertEquals(100, d.aiLimit)
        assertEquals(1000, d.embeddingLimit)
    }
}
