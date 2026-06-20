package com.xjw.qalbum.service.cleanup

import com.xjw.qalbum.data.db.dao.CleanupSignalRow
import com.xjw.qalbum.domain.model.CleanupConfig

/**
 * 清理规则引擎（纯函数）。
 *
 * 根据 [CleanupConfig] 判定一张图命中哪个清理维度。
 * 优先级（高 → 低）：损坏 > 空白 > 重复 > 表情包 > 低分辨率 > 模糊 > 极小文件。
 * 返回命中的原因标签；都不命中返回 null（不纳入清理）。
 *
 * 注意：收藏 / 用户保留 / 敏感信息保护在 DAO 查询层排除，本函数不处理。
 */
object CleanupRules {
    const val REASON_CORRUPT = "损坏"
    const val REASON_BLANK = "空白/纯色"
    const val REASON_DUPLICATE = "重复"
    const val REASON_LOW_RESOLUTION = "低分辨率"
    const val REASON_BLUR = "模糊"
    const val REASON_TINY_FILE = "极小文件"
    const val REASON_EMOJI = "表情包"

    fun classify(signal: CleanupSignalRow, config: CleanupConfig): String? {
        if (config.corruptEnabled && signal.isCorrupt) return REASON_CORRUPT
        if (config.blankEnabled && signal.isBlank) return REASON_BLANK
        if (config.duplicateEnabled && signal.duplicateGroupId != null && !signal.isDuplicateBest) {
            return REASON_DUPLICATE
        }
        if (config.emojiEnabled && isEmoji(signal, config)) return REASON_EMOJI
        if (config.lowResolutionEnabled && isLowResolution(signal, config.lowResolutionMaxPixels)) {
            return REASON_LOW_RESOLUTION
        }
        if (config.blurEnabled && signal.blurScore != null && signal.blurScore < config.blurMaxScore) {
            return REASON_BLUR
        }
        if (config.tinyFileEnabled && signal.size in 1 until config.tinyFileMaxBytes) return REASON_TINY_FILE
        return null
    }

    /** 总像素大于 0 且小于门限才算低分辨率；宽高为 0（未做质量分析）不算 */
    private fun isLowResolution(signal: CleanupSignalRow, maxPixels: Long): Boolean {
        val pixels = signal.width.toLong() * signal.height.toLong()
        return pixels in 1 until maxPixels
    }

    /**
     * 表情包判定：近似正方形（短边/长边 ≥ ratio）+ 长边 ≤ 上限 + 短边 ≥ 下限。
     * 宽高为 0（未做质量分析）不算。覆盖 240²~716² 等样本，挡住 1080² 照片与极小图标。
     */
    fun isEmoji(signal: CleanupSignalRow, config: CleanupConfig): Boolean {
        val w = signal.width
        val h = signal.height
        if (w <= 0 || h <= 0) return false
        val shortSide = minOf(w, h)
        val longSide = maxOf(w, h)
        if (longSide > config.emojiMaxSide) return false
        if (shortSide < config.emojiMinSide) return false
        return shortSide.toFloat() / longSide >= config.emojiSquareRatio
    }
}
