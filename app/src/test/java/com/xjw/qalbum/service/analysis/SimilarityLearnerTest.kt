package com.xjw.qalbum.service.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimilarityLearnerTest {

    @Test
    fun learnFromSplitTightensThreshold() {
        // 被拆的两张特征都很近（默认会判相似），但用户拆分说「不是重复」→ 学习后阈值收紧
        val feats = listOf(
            SimilarityLearner.Feat(ByteArray(32), feat(luma = 10, edge = 10)),
            SimilarityLearner.Feat(ByteArray(32), feat(luma = 20, edge = 20)),
        )
        val before = SimilarityModel.DEFAULT
        val after = SimilarityLearner.learnFromSplit(before, feats, lr = 0.2)
        assertTrue("学习后阈值应收紧：${after.threshold} vs ${before.threshold}", after.threshold < before.threshold)
    }

    @Test
    fun repeatedSplitsNeverCollapseBelowMinThreshold() {
        // 反复拆分「距离全 0」的组（最恶劣负样本）：阈值不得跌破绝对下界，视觉查重不会被学到失效
        val feats = listOf(
            SimilarityLearner.Feat(ByteArray(32), feat()),
            SimilarityLearner.Feat(ByteArray(32), feat()),
        )
        var m = SimilarityModel.DEFAULT
        repeat(500) { m = SimilarityLearner.learnFromSplit(m, feats) }
        assertTrue(
            "阈值不应跌破下界：${m.threshold}",
            m.threshold >= SimilarityLearner.DEFAULT_MIN_THRESHOLD - 1e-9,
        )
    }

    @Test
    fun singleSplitMovesThresholdBounded() {
        // 大组（20 张 = 190 对）单次拆分：平均梯度单步，总移动不超过 lr 量级（此前 190 步会一次收敛）
        val feats = (0 until 20).map { SimilarityLearner.Feat(ByteArray(32), feat()) }
        val before = SimilarityModel.DEFAULT
        val after = SimilarityLearner.learnFromSplit(before, feats)
        assertTrue(
            "单次拆分阈值移动应有界：${before.threshold - after.threshold}",
            before.threshold - after.threshold <= SimilarityModel.DEFAULT_LR + 1e-9,
        )
    }

    @Test
    fun learnFromSplitSkipsMissingFeatures() {
        // 唯一的一对里含缺失特征 → 跳过，模型不变
        val feats = listOf(
            SimilarityLearner.Feat(null, null),
            SimilarityLearner.Feat(ByteArray(32), feat()),
        )
        val before = SimilarityModel.DEFAULT
        val after = SimilarityLearner.learnFromSplit(before, feats)
        assertEquals(before, after)
    }

    private fun feat(luma: Int = 0, edge: Int = 0): ByteArray =
        ByteArray(32).also {
            for (i in 0 until 16) it[i] = luma.toByte()
            for (i in 16 until 32) it[i] = edge.toByte()
        }
}
