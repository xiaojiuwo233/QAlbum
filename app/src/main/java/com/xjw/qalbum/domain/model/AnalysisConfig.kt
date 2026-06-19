package com.xjw.qalbum.domain.model

import kotlinx.serialization.Serializable

/**
 * 分析配置：每个逐图分析阶段的「是否开启」与「每次数量上限」。
 *
 * 阶段：
 * - HASH：所有图片的必经基础阶段（xxHash + pHash + 质量），无开关，只有数量。
 * - OCR / AI 标签 / 向量：可分别开关、分别设上限。
 *
 * 数量语义：`limit <= 0`（[UNLIMITED]）表示「无限」——该阶段会一直处理直到所有未完成图片做完。
 * 管线对每个阶段做**分块循环**消费，故「无限」也只占恒定内存（不会一次性把 200w 行读进内存）。
 *
 * 各 `*Enabled` 仅控制本阶段是否纳入分析；AI 标签 / 向量另需全局 AI 服务可用（endpoint 已配）。
 *
 * 字段带默认值（缺字段时安全回退），与 [CleanupConfig] 的 JSON 持久化范式一致。
 */
@Serializable
data class AnalysisConfig(
    val hashLimit: Int = DEFAULT_HASH_LIMIT,
    val ocrEnabled: Boolean = true,
    val ocrLimit: Int = DEFAULT_OCR_LIMIT,
    val aiTagEnabled: Boolean = true,
    val aiLimit: Int = DEFAULT_AI_LIMIT,
    val embeddingEnabled: Boolean = true,
    val embeddingLimit: Int = DEFAULT_EMBEDDING_LIMIT,
) {
    companion object {
        /** 数量为该值（或更小）即视为「无限」 */
        const val UNLIMITED = 0

        const val DEFAULT_HASH_LIMIT = UNLIMITED        // hash 默认无限：基础阶段尽量跑完
        const val DEFAULT_OCR_LIMIT = 500
        const val DEFAULT_AI_LIMIT = 100
        const val DEFAULT_EMBEDDING_LIMIT = 1000

        /** 单阶段数量上限（输入框 clamp 用）：再大也按无限处理，避免误填天文数字 */
        const val MAX_LIMIT = 2_000_000

        val DEFAULT = AnalysisConfig()
    }
}

/**
 * 把用户填写的数量归一化：
 * - `<= 0` → [UNLIMITED]（无限）
 * - 否则 clamp 到 (0, MAX_LIMIT]
 */
fun normalizeAnalysisLimit(raw: Int): Int =
    if (raw <= 0) AnalysisConfig.UNLIMITED else raw.coerceAtMost(AnalysisConfig.MAX_LIMIT)
