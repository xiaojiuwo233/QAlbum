package com.xjw.qalbum.service.analysis

import kotlin.math.exp

/**
 * 删除偏好模型（在线逻辑回归，纯函数）。
 *
 * 从用户的清理/保留行为学习「这个用户想删什么」，给图打「删除概率」。
 * 权重**无符号约束**（某特征增大可能更想删或更想留，由数据定）。特征向量见 [DeleteFeatures]。
 * 冷启动（全 0 权重）概率恒 0.5；配合冷启动期不推荐，攒够样本再激活。
 */
data class DeletePreferenceModel(
    val weights: DoubleArray,
    val bias: Double,
) {
    fun probability(features: DoubleArray): Double {
        var z = bias
        val n = minOf(weights.size, features.size)
        for (i in 0 until n) z += weights[i] * features[i]
        return 1.0 / (1.0 + exp(-z))
    }

    /** 在线 SGD 更新；[deleted]=true 为正样本（用户删了），false 为负样本（保留）。 */
    fun update(features: DoubleArray, deleted: Boolean, lr: Double = DEFAULT_LR): DeletePreferenceModel {
        val p = probability(features)
        val y = if (deleted) 1.0 else 0.0
        val err = p - y
        val n = minOf(weights.size, features.size)
        val w = weights.copyOf()
        for (i in 0 until n) w[i] -= lr * err * features[i]
        return DeletePreferenceModel(w, bias - lr * err)
    }

    /** 序列化为逗号分隔字符串（持久化用）：weights... , bias。 */
    fun serialize(): String = (weights.toList() + bias).joinToString(",")

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DeletePreferenceModel) return false
        return bias == other.bias && weights.contentEquals(other.weights)
    }

    override fun hashCode(): Int = 31 * weights.contentHashCode() + bias.hashCode()

    companion object {
        const val DEFAULT_LR = 0.05
        const val DIM = DeleteFeatures.DIM

        val DEFAULT = DeletePreferenceModel(DoubleArray(DIM), 0.0)

        fun deserialize(s: String?): DeletePreferenceModel? {
            if (s.isNullOrBlank()) return null
            val parts = s.split(",").mapNotNull { it.trim().toDoubleOrNull() }
            if (parts.size != DIM + 1) return null
            return DeletePreferenceModel(parts.subList(0, DIM).toDoubleArray(), parts[DIM])
        }
    }
}
