package com.xjw.qalbum.service.analysis

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimilarityModelTest {

    @Test
    fun defaultJudgesIdenticalAsSimilar() {
        assertTrue(SimilarityModel.DEFAULT.isSimilar(0.0, 0.0, 0.0))
    }

    @Test
    fun defaultJudgesVeryDifferentAsNotSimilar() {
        // 亮度 / 边缘差异大（典型：blockHash 近但内容不同的截图）→ 不相似
        assertFalse(SimilarityModel.DEFAULT.isSimilar(0.1, 0.5, 0.5))
    }

    @Test
    fun learnsToRejectAfterNegativeFeedback() {
        // 默认判相似的点（score 0.325 < 0.35）；反复负反馈后应学会判「不相似」
        var m = SimilarityModel.DEFAULT
        val dB = 0.1; val dL = 0.1; val dE = 0.05
        assertTrue("前提：默认应判相似", m.isSimilar(dB, dL, dE))
        repeat(300) { m = m.update(dB, dL, dE, similar = false) }
        assertFalse("反复负反馈后应判不相似", m.isSimilar(dB, dL, dE))
    }

    @Test
    fun learnsToAcceptAfterPositiveFeedback() {
        // 默认判不相似的点（score 1.2 > 0.35）；反复正反馈后应学会判「相似」
        var m = SimilarityModel.DEFAULT
        val dB = 0.3; val dL = 0.3; val dE = 0.3
        assertFalse("前提：默认应判不相似", m.isSimilar(dB, dL, dE))
        repeat(400) { m = m.update(dB, dL, dE, similar = true) }
        assertTrue("反复正反馈后应判相似", m.isSimilar(dB, dL, dE))
    }

    @Test
    fun weightsStayNonNegative() {
        var m = SimilarityModel.DEFAULT
        repeat(200) { m = m.update(0.6, 0.6, 0.6, similar = true) } // 正样本会压低权重
        assertTrue("权重不应为负", m.wBlock >= 0.0 && m.wLuma >= 0.0 && m.wEdge >= 0.0)
        assertTrue("阈值不应为负", m.threshold >= 0.0)
    }
}
