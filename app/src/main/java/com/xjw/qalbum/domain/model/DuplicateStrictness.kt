package com.xjw.qalbum.domain.model

/**
 * 重复检测严格度档位。映射到视觉聚类参数：
 * - [visualThreshold]：pHash 距离上限（粗筛硬门槛）
 * - [scoreThreshold]：加权相似度分阈值（score < 阈值 才判相似）；越小越「宁漏勿误」
 *
 * service 层据此组装 [com.xjw.qalbum.service.analysis.SimilarityModel] 的基础阈值（学习会在此基础上调整）。
 * 改档后需重新查重才生效。
 */
enum class DuplicateStrictness(val visualThreshold: Int, val scoreThreshold: Double) {
    LOOSE(visualThreshold = 10, scoreThreshold = 0.50),   // 找得多，可能误聚
    STANDARD(visualThreshold = 8, scoreThreshold = 0.35), // 推荐
    STRICT(visualThreshold = 6, scoreThreshold = 0.22);   // 宁漏勿误

    companion object {
        val DEFAULT = STANDARD

        fun fromName(raw: String?): DuplicateStrictness =
            raw?.let { runCatching { valueOf(it) }.getOrNull() } ?: DEFAULT
    }
}
