package com.xjw.qalbum.service.analysis

import kotlin.math.exp

/**
 * 可学习的视觉相似度模型（在线度量学习）。
 *
 * 相似度 = 各特征距离的加权和；越小越相似：
 *   score = wBlock·dBlock + wLuma·dLuma + wEdge·dEdge
 *   isSimilar = score < threshold
 *
 * - **方案1（被动）**：[DEFAULT] 提供手工调好的默认权重 / 阈值，冷启动即生效。
 * - **方案2（自进化）**：[update] 用一条带标签样本做逻辑回归 SGD，从用户反馈在线调权重。
 *   信号约定（干净、动机单一）：正样本 = xxHash 精确重复；负样本 = 用户「拆分」的组内对。
 *   其余清理操作动机混杂，不作训练标签。
 *
 * 所有距离均归一化到 [0,1]。权重约束非负（距离越大越不相似）。模型只有 4 个标量，可直接持久化。
 */
data class SimilarityModel(
    val wBlock: Double,
    val wLuma: Double,
    val wEdge: Double,
    val threshold: Double,
) {
    /** 加权距离分：越小越相似。 */
    fun score(dBlock: Double, dLuma: Double, dEdge: Double): Double =
        wBlock * dBlock + wLuma * dLuma + wEdge * dEdge

    /** 是否判定为相似（重复）。 */
    fun isSimilar(dBlock: Double, dLuma: Double, dEdge: Double): Boolean =
        score(dBlock, dLuma, dEdge) < threshold

    /**
     * 在线更新（逻辑回归 SGD），返回新模型。[similar] = true 为正样本（相似），false 为负样本。
     * 学习率 [lr] 默认较小以抗反馈噪声。
     */
    fun update(
        dBlock: Double,
        dLuma: Double,
        dEdge: Double,
        similar: Boolean,
        lr: Double = DEFAULT_LR,
    ): SimilarityModel {
        // 逻辑回归：z = threshold - score，P(similar) = σ(z)，y = 标签。梯度 ∂L/∂z = P - y。
        val z = threshold - score(dBlock, dLuma, dEdge)
        val p = 1.0 / (1.0 + exp(-z))
        val y = if (similar) 1.0 else 0.0
        val err = p - y
        // threshold 对应偏置（∂z/∂threshold=+1）；权重对应 -距离（∂z/∂w=-d）。权重 / 阈值约束非负。
        return SimilarityModel(
            wBlock = (wBlock + lr * err * dBlock).coerceAtLeast(0.0),
            wLuma = (wLuma + lr * err * dLuma).coerceAtLeast(0.0),
            wEdge = (wEdge + lr * err * dEdge).coerceAtLeast(0.0),
            threshold = (threshold - lr * err).coerceAtLeast(0.0),
        )
    }

    companion object {
        const val DEFAULT_LR = 0.05

        /** 默认权重：亮度 / 边缘权重高于 blockHash —— 它们才是区分文字截图的关键。阈值偏紧（宁漏勿误）。 */
        val DEFAULT = SimilarityModel(wBlock = 1.0, wLuma = 1.5, wEdge = 1.5, threshold = 0.35)
    }
}
