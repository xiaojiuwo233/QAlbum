package com.xjw.qalbum.service.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeletePreferenceModelTest {

    @Test
    fun defaultIsNeutralHalf() {
        val p = DeletePreferenceModel.DEFAULT.probability(DoubleArray(DeleteFeatures.DIM) { 0.5 })
        assertEquals(0.5, p, 0.001)
    }

    @Test
    fun learnsToPredictDeleteAfterPositiveFeedback() {
        val feat = DoubleArray(DeleteFeatures.DIM).also { it[0] = 1.0 }
        var m = DeletePreferenceModel.DEFAULT
        repeat(150) { m = m.update(feat, deleted = true) }
        assertTrue("反复删该模式 → 删除概率应升高", m.probability(feat) > 0.8)
    }

    @Test
    fun learnsToPredictKeepAfterNegativeFeedback() {
        val feat = DoubleArray(DeleteFeatures.DIM).also { it[1] = 1.0 }
        var m = DeletePreferenceModel.DEFAULT
        repeat(150) { m = m.update(feat, deleted = false) }
        assertTrue("反复保留该模式 → 删除概率应降低", m.probability(feat) < 0.2)
    }

    @Test
    fun serializeRoundTrip() {
        val m = DeletePreferenceModel(DoubleArray(DeleteFeatures.DIM) { it * 0.1 }, 0.5)
        assertEquals(m, DeletePreferenceModel.deserialize(m.serialize()))
    }

    @Test
    fun deserializeRejectsWrongShape() {
        assertEquals(null, DeletePreferenceModel.deserialize("1,2,3"))
        assertEquals(null, DeletePreferenceModel.deserialize(null))
    }

    @Test
    fun deserializeAcceptsLegacyHeadlessCsv() {
        // 既有用户数据是无版本头的裸 CSV：维度恰为 DIM+1 时必须继续可用（学习成果不丢）
        val m = DeletePreferenceModel(DoubleArray(DeleteFeatures.DIM) { it * 0.1 }, 0.5)
        val legacy = (m.weights.toList() + m.bias).joinToString(",")
        assertEquals(m, DeletePreferenceModel.deserialize(legacy))
    }

    @Test
    fun deserializeMigratesSmallerDimByZeroPadding() {
        // 特征追加式演进：旧串维度小于当前 DIM → 前缀保留、新维度补 0，而不是整体丢弃
        val oldDim = DeleteFeatures.DIM - 1
        val serialized = "v1;$oldDim;" + ((0 until oldDim).map { 0.2 } + 0.7).joinToString(",")
        val m = DeletePreferenceModel.deserialize(serialized)!!
        assertEquals(0.2, m.weights[0], 1e-9)
        assertEquals(0.0, m.weights[DeleteFeatures.DIM - 1], 1e-9)
        assertEquals(0.7, m.bias, 1e-9)
    }

    @Test
    fun deserializeRejectsNonFiniteValues() {
        val bad = "v1;${DeleteFeatures.DIM};" +
            (List(DeleteFeatures.DIM) { "NaN" } + "0.5").joinToString(",")
        assertEquals(null, DeletePreferenceModel.deserialize(bad))
    }

    @Test
    fun coldModelDetected() {
        assertTrue(DeletePreferenceModel.DEFAULT.isCold)
        val warmed = DeletePreferenceModel.DEFAULT.update(DoubleArray(DeleteFeatures.DIM) { 1.0 }, deleted = true)
        assertTrue(!warmed.isCold)
    }
}
