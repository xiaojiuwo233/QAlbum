package com.xjw.qalbum.service.analysis

import kotlin.math.exp

/**
 * 从用户反馈在线学习 [SimilarityModel]（方案2 的训练逻辑，纯函数）。
 *
 * 信号约定（动机单一、干净）：
 * - **负样本**：用户「拆分」的组内两两 —— 明确表达「这些看起来像但不是重复」，是最有价值的困难负样本。
 * - 其余清理操作（全部保留 / 删除 / 仅留 1 张）动机混杂，**不**作训练信号。
 *
 * 稳定性约束（数学上防阈值单调坍缩）：
 * - **全部负样本对取平均梯度只走一步**：此前对 n 张组做 C(n,2) 步顺序 SGD（20 张 = 190 步），
 *   等效于对单次反馈完全收敛——SGD 平衡点在「组内加权分的一半」，能被拆分的组按构造分数都低
 *   （精确重复组 d≈0），一次手势即可把阈值压到近 0、视觉查重召回归零且跨会话持久。
 *   平均单步后，一次拆分对模型的总移动不超过 lr 量级。
 * - **[minThreshold] 绝对下界**：旧实现的「距离全 0 正锚点」权重梯度恒 0、只动阈值，
 *   平衡点仍是 s/2，锚不住坍缩；绝对下界才是硬保证，学习永远不会学出「谁都不相似」。
 */
object SimilarityLearner {

    /** 阈值绝对下界的默认值（默认档位阈值的一半）；调用方可传当前严格度档位 scoreThreshold 的一半。 */
    val DEFAULT_MIN_THRESHOLD: Double = SimilarityModel.DEFAULT.threshold / 2

    data class Feat(val blockHash: ByteArray?, val featureGrid: ByteArray?)

    fun learnFromSplit(
        model: SimilarityModel,
        feats: List<Feat>,
        lr: Double = SimilarityModel.DEFAULT_LR,
        minThreshold: Double = DEFAULT_MIN_THRESHOLD,
    ): SimilarityModel {
        var n = 0
        var gBlock = 0.0
        var gLuma = 0.0
        var gEdge = 0.0
        var gBias = 0.0
        for (i in feats.indices) {
            val aBlock = feats[i].blockHash ?: continue
            val aFeat = feats[i].featureGrid ?: continue
            for (j in i + 1 until feats.size) {
                val bBlock = feats[j].blockHash ?: continue
                val bFeat = feats[j].featureGrid ?: continue
                val dBlock = DuplicateClusterer.blockHashDistance(aBlock, bBlock) /
                    DuplicateClusterer.BLOCK_HASH_BITS.toDouble()
                val dLuma = DuplicateClusterer.lumaDistance(aFeat, bFeat)
                val dEdge = DuplicateClusterer.edgeDistance(aFeat, bFeat)
                // 逻辑回归负样本梯度（全部在**当前**模型处求值再平均）：err = σ(threshold − score) − 0
                val z = model.threshold - model.score(dBlock, dLuma, dEdge)
                val err = 1.0 / (1.0 + exp(-z))
                gBlock += err * dBlock
                gLuma += err * dLuma
                gEdge += err * dEdge
                gBias += err
                n++
            }
        }
        if (n == 0) return model
        return SimilarityModel(
            wBlock = (model.wBlock + lr * gBlock / n).coerceAtLeast(0.0),
            wLuma = (model.wLuma + lr * gLuma / n).coerceAtLeast(0.0),
            wEdge = (model.wEdge + lr * gEdge / n).coerceAtLeast(0.0),
            threshold = (model.threshold - lr * gBias / n).coerceAtLeast(minThreshold),
        )
    }
}
