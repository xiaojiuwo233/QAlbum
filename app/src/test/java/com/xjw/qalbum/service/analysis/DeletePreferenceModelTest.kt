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
}
