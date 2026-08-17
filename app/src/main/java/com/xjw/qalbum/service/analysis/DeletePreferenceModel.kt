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

    /** 序列化（持久化用）：`v1;维度;w0,...,wN,bias`。版本 + 维度头使特征演进可迁移（见 [deserialize]）。 */
    fun serialize(): String = "v1;${weights.size};" + (weights.toList() + bias).joinToString(",")

    /**
     * 是否仍是冷启动模型（全 0 权重 + 0 偏置）：其 probability 恒为 σ(0)=0.5，恰好压在推荐阈值上，
     * 推荐器应对冷模型直接不出推荐，而不是让候选池全体并列命中。
     */
    val isCold: Boolean get() = bias == 0.0 && weights.all { it == 0.0 }

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

        /**
         * 反序列化。兼容两种输入：
         * - `v1;dim;csv` 头（[serialize] 现行格式）：声明维度小于当前 [DIM] 时旧权重前缀保留、
         *   新维度补 0（[DeleteFeatures] 追加式演进不再静默丢弃学习成果）；大于当前 DIM 视为不兼容；
         * - 无头 legacy CSV（既有用户数据）：长度必须恰为 DIM+1。
         * 任一数值非有限（NaN/Infinity 一旦入串会跨会话往返污染模型）即整体拒绝。
         * 返回 null 表示不可用，调用方回退 [DEFAULT]（冷启动，建议同步清零学习样本计数）。
         */
        fun deserialize(s: String?): DeletePreferenceModel? {
            if (s.isNullOrBlank()) return null
            val body: String
            val declaredDim: Int
            if (s.startsWith("v1;")) {
                val seg = s.split(';', limit = 3)
                if (seg.size != 3) return null
                declaredDim = seg[1].toIntOrNull() ?: return null
                body = seg[2]
            } else {
                declaredDim = DIM
                body = s
            }
            if (declaredDim <= 0 || declaredDim > DIM) return null
            val parts = body.split(",").map { it.trim().toDoubleOrNull() ?: return null }
            if (parts.size != declaredDim + 1 || parts.any { !it.isFinite() }) return null
            val w = DoubleArray(DIM)
            for (i in 0 until declaredDim) w[i] = parts[i]
            return DeletePreferenceModel(w, parts[declaredDim])
        }
    }
}
