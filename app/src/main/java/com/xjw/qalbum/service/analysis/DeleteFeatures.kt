package com.xjw.qalbum.service.analysis

import kotlin.math.ln

/**
 * 删除偏好模型的特征提取（纯算法中层特征，不读语义）。
 *
 * 复用已有的 featureGrid（亮度/边缘网格）+ ImageEntity 元数据，**零额外计算/回填**。
 * 这些特征只能表达「类型 / 质量 / 来源 / 时间」，学不了「内容 / 审美」——这是纯算法的天花板，
 * 但足以把「漏网的普通图」按可能想删排出来。所有特征归一化到 [0,1]。
 */
object DeleteFeatures {
    const val DIM = 11

    private const val MAX_PIXELS = 20_000_000.0
    private const val MAX_BYTES = 10_000_000.0
    private const val SHARP_REF = 2000.0
    private const val AGE_WINDOW_MS = 180.0 * 24 * 3600 * 1000 // 半年

    fun extract(
        featureGrid: ByteArray?,
        blurScore: Float?,
        width: Int,
        height: Int,
        size: Long,
        sourceType: String,
        isAnimated: Boolean,
        mtime: Long,
        inDuplicateGroup: Boolean,
        now: Long,
    ): DoubleArray {
        val f = DoubleArray(DIM)
        // 0,1：亮度均值 / 边缘密度均值（缺 featureGrid 用中性 0.5）
        if (featureGrid != null && featureGrid.size >= 32) {
            var luma = 0
            var edge = 0
            for (i in 0 until 16) luma += featureGrid[i].toInt() and 0xFF
            for (i in 16 until 32) edge += featureGrid[i].toInt() and 0xFF
            f[0] = luma / (16.0 * 255)
            f[1] = edge / (16.0 * 255)
        } else {
            f[0] = 0.5
            f[1] = 0.5
        }
        // 2：分辨率（log 归一化）
        val pixels = width.toLong() * height
        f[2] = if (pixels > 0) (ln(1.0 + pixels) / ln(1.0 + MAX_PIXELS)).coerceIn(0.0, 1.0) else 0.0
        // 3：文件大小（log 归一化）
        f[3] = (ln(1.0 + size) / ln(1.0 + MAX_BYTES)).coerceIn(0.0, 1.0)
        // 4：清晰度（blurScore 越高越清晰；缺失中性 0.5）
        f[4] = blurScore?.let { (it / SHARP_REF).coerceIn(0.0, 1.0).toDouble() } ?: 0.5
        // 5,6：来源 one-hot
        f[5] = if (sourceType == "chatimg") 1.0 else 0.0
        f[6] = if (sourceType == "chatraw") 1.0 else 0.0
        // 7：动图
        f[7] = if (isAnimated) 1.0 else 0.0
        // 8：宽高比（ar/(1+ar)：<0.5 竖图，>0.5 横图）
        val ar = if (height > 0) width.toDouble() / height else 1.0
        f[8] = (ar / (1.0 + ar)).coerceIn(0.0, 1.0)
        // 9：旧度（距今越久越接近 1，半年封顶）
        val ageMs = (now - mtime).coerceAtLeast(0L)
        f[9] = (ageMs / AGE_WINDOW_MS).coerceIn(0.0, 1.0)
        // 10：是否属重复组
        f[10] = if (inDuplicateGroup) 1.0 else 0.0
        return f
    }
}
