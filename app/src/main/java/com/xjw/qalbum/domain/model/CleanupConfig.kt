package com.xjw.qalbum.domain.model

import kotlinx.serialization.Serializable

/**
 * 清理规则配置：每个清理维度的开关 + 门限。
 *
 * 方案 A：清理候选的准入完全由本配置驱动（命中任一启用维度即入候选），
 * delete_score 不再决定准入，仅用于候选排序。
 *
 * 门限语义：
 * - [lowResolutionMaxPixels]：总像素（宽 × 高）小于此值算「低分辨率」
 * - [blurMaxScore]：清晰度（Laplacian 方差）小于此值算「模糊」
 * - [tinyFileMaxBytes]：文件大小小于此值算「极小文件」
 *
 * 重复 / 空白 / 损坏只有开关，没有门限（判定本身是布尔事实）。
 *
 * 字段带默认值（= 平衡档），保证 JSON 反序列化缺字段时安全回退。
 */
@Serializable
data class CleanupConfig(
    val duplicateEnabled: Boolean = true,
    val lowResolutionEnabled: Boolean = true,
    val lowResolutionMaxPixels: Long = DEFAULT_LOW_RES_MAX_PIXELS,
    val blurEnabled: Boolean = true,
    val blurMaxScore: Float = DEFAULT_BLUR_MAX_SCORE,
    val tinyFileEnabled: Boolean = false,
    val tinyFileMaxBytes: Long = DEFAULT_TINY_FILE_MAX_BYTES,
    val blankEnabled: Boolean = true,
    val corruptEnabled: Boolean = true,
    val emojiEnabled: Boolean = true,
    val emojiSquareRatio: Float = DEFAULT_EMOJI_SQUARE_RATIO,
    val emojiMaxSide: Int = DEFAULT_EMOJI_MAX_SIDE,
    val emojiMinSide: Int = DEFAULT_EMOJI_MIN_SIDE,
) {
    companion object {
        // 默认门限（同时也是「平衡」档采用的门限）
        const val DEFAULT_LOW_RES_MAX_PIXELS = 300_000L     // ≈ 548×548
        const val DEFAULT_BLUR_MAX_SCORE = 100f
        const val DEFAULT_TINY_FILE_MAX_BYTES = 30_720L     // 30 KB

        // 「激进」档放宽后的门限
        const val AGGRESSIVE_LOW_RES_MAX_PIXELS = 500_000L
        const val AGGRESSIVE_BLUR_MAX_SCORE = 300f
        const val AGGRESSIVE_TINY_FILE_MAX_BYTES = 102_400L // 100 KB

        // 门限可调范围（供设置页滑块使用）
        const val MIN_LOW_RES_MAX_PIXELS = 50_000L          // ≈ 224×224
        const val MAX_LOW_RES_MAX_PIXELS = 1_000_000L       // 1 MP
        const val MIN_BLUR_MAX_SCORE = 30f
        const val MAX_BLUR_MAX_SCORE = 600f
        const val MIN_TINY_FILE_MAX_BYTES = 4_096L          // 4 KB
        const val MAX_TINY_FILE_MAX_BYTES = 307_200L        // 300 KB

        // 表情包门限：近似正方形（短边/长边 ≥ ratio）+ 长边上限 + 短边下限。
        // 覆盖样本 240² / 515×524 / 570×540 / 640² / 716²；挡住 1080² 等正方形照片。
        const val DEFAULT_EMOJI_SQUARE_RATIO = 0.90f
        const val DEFAULT_EMOJI_MAX_SIDE = 768
        const val DEFAULT_EMOJI_MIN_SIDE = 64
        const val MIN_EMOJI_SQUARE_RATIO = 0.70f
        const val MAX_EMOJI_SQUARE_RATIO = 0.99f
        const val MIN_EMOJI_MAX_SIDE = 256
        const val MAX_EMOJI_MAX_SIDE = 1024
        const val MIN_EMOJI_MIN_SIDE = 16
        const val MAX_EMOJI_MIN_SIDE = 256

        /** 保守：只清理高置信度类别（重复 / 损坏 / 空白），不碰低分辨率 / 模糊 / 极小文件 */
        val CONSERVATIVE = CleanupConfig(
            duplicateEnabled = true,
            lowResolutionEnabled = false,
            lowResolutionMaxPixels = DEFAULT_LOW_RES_MAX_PIXELS,
            blurEnabled = false,
            blurMaxScore = DEFAULT_BLUR_MAX_SCORE,
            tinyFileEnabled = false,
            tinyFileMaxBytes = DEFAULT_TINY_FILE_MAX_BYTES,
            blankEnabled = true,
            corruptEnabled = true,
            emojiEnabled = false,
        )

        /** 平衡（默认）：在保守基础上加入低分辨率 + 模糊 + 表情包，覆盖大多数明显多余的图 */
        val BALANCED = CONSERVATIVE.copy(
            lowResolutionEnabled = true,
            blurEnabled = true,
            emojiEnabled = true,
        )

        /** 激进：全维度开启，门限放宽，需仔细审核 */
        val AGGRESSIVE = CleanupConfig(
            duplicateEnabled = true,
            lowResolutionEnabled = true,
            lowResolutionMaxPixels = AGGRESSIVE_LOW_RES_MAX_PIXELS,
            blurEnabled = true,
            blurMaxScore = AGGRESSIVE_BLUR_MAX_SCORE,
            tinyFileEnabled = true,
            tinyFileMaxBytes = AGGRESSIVE_TINY_FILE_MAX_BYTES,
            blankEnabled = true,
            corruptEnabled = true,
            emojiEnabled = true,
        )

        /** 首次使用时的默认配置 = 平衡 */
        val DEFAULT = BALANCED
    }
}

/**
 * 三档快捷预设：点击即把对应 [config] 套用到当前清理配置；用户之后可逐项微调。
 */
enum class CleanupPreset(val displayName: String, val config: CleanupConfig) {
    CONSERVATIVE("保守", CleanupConfig.CONSERVATIVE),
    BALANCED("平衡", CleanupConfig.BALANCED),
    AGGRESSIVE("激进", CleanupConfig.AGGRESSIVE);

    companion object {
        /** 找出与给定配置完全一致的预设；用户微调过则返回 null（即「自定义」状态） */
        fun match(config: CleanupConfig): CleanupPreset? = entries.firstOrNull { it.config == config }
    }
}
