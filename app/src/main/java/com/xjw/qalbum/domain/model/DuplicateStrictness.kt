package com.xjw.qalbum.domain.model

/**
 * 重复检测严格度档位。映射到视觉聚类的两个阈值：
 * - [visualThreshold]：pHash 距离上限（粗筛）
 * - [blockThreshold]：256bit 分块指纹距离上限（精筛，截图去重主力）
 *
 * 越严格 → 阈值越小 → 越「宁漏勿误」。改档后需重新查重才生效。
 */
enum class DuplicateStrictness(val visualThreshold: Int, val blockThreshold: Int) {
    LOOSE(visualThreshold = 10, blockThreshold = 56),    // 找得多，可能误聚
    STANDARD(visualThreshold = 8, blockThreshold = 40),  // 推荐
    STRICT(visualThreshold = 6, blockThreshold = 26);    // 宁漏勿误

    companion object {
        val DEFAULT = STANDARD

        fun fromName(raw: String?): DuplicateStrictness =
            raw?.let { runCatching { valueOf(it) }.getOrNull() } ?: DEFAULT
    }
}
