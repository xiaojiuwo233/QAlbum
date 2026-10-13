package com.xjw.qalbum.service.analysis

import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.data.db.entity.OcrTextEntity

/**
 * 图片评分引擎
 *
 * 两个独立维度：
 * - **info_score**（信息价值 0..1）：用户应当保留它的理由有多强
 * - **delete_score**（删除推荐度 0..1）：建议自动清理它的理由有多强
 *
 * Phase 2 基础版基于 hash / 质量 / 尺寸；
 * Phase 3 起接入 OCR 信号（含手机号 / 金额 / 地址 / 证件号 / 订单号）。
 * Phase 4 会再接入 AI 标签。
 *
 * 评分逻辑刻意"保守"——宁可保留，不要误删。
 */
object ScoringEngine {

    fun computeInfoScore(image: ImageEntity, ocr: OcrTextEntity? = null): Float {
        var score = 0f

        // 用户明确标记
        if (image.userKeep == true) score += 0.5f
        if (image.isArchived) score += 0.5f

        // 高分辨率小加分
        val pixels = CleanupSignals.pixelCount(image)
        if (pixels >= 2_000_000) score += 0.05f
        else if (pixels >= 800_000) score += 0.03f

        // 文件较大（可能是高质量图）
        if (image.size > 1024 * 1024) score += 0.05f

        // OCR 文本信号 —— 越敏感、文字越多，越值得保留
        ocr?.let {
            // 文本长度加分（最多 0.15）
            score += (it.text.length.coerceAtMost(500) / 500f) * 0.15f
            // 敏感信息：每命中一项 +0.10~0.20
            if (it.hasIdNumber) score += 0.20f       // 身份证 / 证件号
            if (it.hasOrderNo) score += 0.10f        // 订单号 / 票据
            if (it.hasAmount) score += 0.15f         // 金额（订单 / 转账 / 收据）
            if (it.hasPhone) score += 0.10f          // 手机号（联系方式）
            if (it.hasAddress) score += 0.08f        // 地址
        }

        return score.coerceIn(0f, 1f)
    }

    fun computeDeleteScore(image: ImageEntity, ocr: OcrTextEntity? = null): Float {
        // 用户明确保留 / 已归档 → 直接归零
        if (image.userKeep == true || image.isArchived) return 0f

        var score = 0f

        // ---- 强信号 ----
        if (image.duplicateGroupId != null && !image.isDuplicateBest) score += 0.65f
        if (image.isCorrupt) score += 0.75f
        if (image.isBlank) score += 0.65f

        // ---- 中信号 ----
        if (CleanupSignals.isVeryLowResolution(image)) {
            score += 0.50f
        } else if (CleanupSignals.isLowResolution(image)) {
            score += 0.25f
        }
        image.blurScore?.let { v ->
            if (v < 100f) score += 0.25f
            else if (v < 300f) score += 0.12f
        }
        if (image.size in 1..4096) score += 0.15f

        // OCR 文本：完全无文本的图，价值略低；有文本则不加分（具体保留价值由 info_score 抵消）
        if (ocr != null && ocr.text.isBlank()) score += 0.05f

        // 信息价值反向减分
        val info = computeInfoScore(image, ocr)
        score -= info * 0.6f

        return score.coerceIn(0f, 1f)
    }
}
