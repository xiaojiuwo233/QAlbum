package com.xjw.qalbum.service.analysis

import com.xjw.qalbum.data.db.entity.ImageEntity

/**
 * 图片评分引擎（Phase 2 基础版本）
 *
 * 两个独立维度：
 * - **info_score**（信息价值 0..1）：用户应当保留它的理由有多强
 * - **delete_score**（删除推荐度 0..1）：建议自动清理它的理由有多强
 *
 * Phase 2 仅基于 hash / 质量 / 尺寸做评分；OCR / AI 标签会在 Phase 3+ 接入。
 * 评分逻辑刻意"保守"——宁可保留，不要误删。
 */
object ScoringEngine {

    fun computeInfoScore(image: ImageEntity): Float {
        var score = 0f

        // 用户明确标记
        if (image.userKeep == true) score += 0.5f
        if (image.isProtected) score += 0.5f

        // 高分辨率小加分
        val pixels = image.width.toLong() * image.height.toLong()
        if (pixels >= 2_000_000) score += 0.05f
        else if (pixels >= 800_000) score += 0.03f

        // 文件较大（可能是高质量图）
        if (image.size > 1024 * 1024) score += 0.05f

        return score.coerceIn(0f, 1f)
    }

    fun computeDeleteScore(image: ImageEntity): Float {
        // 用户明确保留 / 已被保护 → 直接归零
        if (image.userKeep == true || image.isProtected) return 0f

        var score = 0f

        // ---- 强信号 ----
        // 重复组非最优保留 → 高风险候选
        if (image.duplicateGroupId != null && !image.isDuplicateBest) score += 0.45f

        // 缩略图（chatthumb）：如果同变体组里有更高清版本，价值就低
        if (image.sourceType == "chatthumb") score += 0.20f

        // 损坏 → 强候选
        if (image.isCorrupt) score += 0.55f

        // 空白 / 纯色
        if (image.isBlank) score += 0.40f

        // ---- 中信号 ----
        // 极低分辨率 (<100x100)
        if (image.width > 0 && image.width < 100 && image.height < 100) score += 0.25f

        // 模糊（Laplacian 方差很低）
        image.blurScore?.let { v ->
            if (v < 100f) score += 0.20f
            else if (v < 300f) score += 0.10f
        }

        // 文件很小（< 4KB）通常是表情包 / 占位图
        if (image.size in 1..4096) score += 0.10f

        // 信息价值反向减分（保护伞）
        val info = computeInfoScore(image)
        score -= info * 0.5f

        return score.coerceIn(0f, 1f)
    }
}
