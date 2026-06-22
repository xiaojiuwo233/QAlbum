package com.xjw.qalbum.service.analysis

/**
 * 从用户反馈在线学习 [SimilarityModel]（方案2 的训练逻辑，纯函数）。
 *
 * 信号约定（动机单一、干净）：
 * - **负样本**：用户「拆分」的组内两两 —— 明确表达「这些看起来像但不是重复」，是最有价值的困难负样本。
 * - **正锚点**：每个负样本配若干「距离全 0 → 相似」的锚点，锚住「完全相同=重复」这一不变事实，
 *   防止模型被负样本一路压到「谁都不相似」而塌缩。
 *
 * 其余清理操作（全部保留 / 删除 / 仅留 1 张）动机混杂，**不**作训练信号。
 */
object SimilarityLearner {

    data class Feat(val blockHash: ByteArray?, val featureGrid: ByteArray?)

    fun learnFromSplit(
        model: SimilarityModel,
        feats: List<Feat>,
        lr: Double = SimilarityModel.DEFAULT_LR,
        anchorsPerNeg: Int = 1,
    ): SimilarityModel {
        var m = model
        for (i in feats.indices) {
            val aBlock = feats[i].blockHash
            val aFeat = feats[i].featureGrid
            if (aBlock == null || aFeat == null) continue
            for (j in i + 1 until feats.size) {
                val bBlock = feats[j].blockHash ?: continue
                val bFeat = feats[j].featureGrid ?: continue
                val dBlock = DuplicateClusterer.blockHashDistance(aBlock, bBlock) /
                    DuplicateClusterer.BLOCK_HASH_BITS.toDouble()
                val dLuma = DuplicateClusterer.lumaDistance(aFeat, bFeat)
                val dEdge = DuplicateClusterer.edgeDistance(aFeat, bFeat)
                m = m.update(dBlock, dLuma, dEdge, similar = false, lr = lr) // 负样本：用户判定非重复
                repeat(anchorsPerNeg) {
                    m = m.update(0.0, 0.0, 0.0, similar = true, lr = lr)     // 正锚：相同=相似，防塌缩
                }
            }
        }
        return m
    }
}
