package com.xjw.qalbum.ui.imageloading

import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import kotlin.math.max
import kotlin.math.min

/**
 * 看图分块解码的坐标换算（纯计算，不碰 Android 类）。图上的坐标都是按 EXIF 方向转正之后的原图像素；
 * 方向编号同 EXIF（1 正常、2 水平翻转、3 转 180°、4 垂直翻转、5 转置、6 顺时针 90°、7 反转置、8 逆时针 90°）。
 */
internal object TileMath {
    /** 每块解出来的边长（像素） */
    const val TILE_PX = 512

    /** 这个方向会不会对调宽高 */
    fun transposes(orientation: Int): Boolean = orientation in 5..8

    /** 转正之后的宽高 */
    fun orientedSize(rawWidth: Int, rawHeight: Int, orientation: Int): IntSize =
        if (transposes(orientation)) IntSize(rawHeight, rawWidth) else IntSize(rawWidth, rawHeight)

    /** 转正之后图上的 [rect] 在原始像素里的范围（解码按原始像素来） */
    fun toRaw(rect: IntRect, orientation: Int, rawWidth: Int, rawHeight: Int): IntRect {
        fun map(x: Int, y: Int): Pair<Int, Int> = when (orientation) {
            2 -> rawWidth - x to y
            3 -> rawWidth - x to rawHeight - y
            4 -> x to rawHeight - y
            5 -> y to x
            6 -> y to rawHeight - x
            7 -> rawWidth - y to rawHeight - x
            8 -> rawWidth - y to x
            else -> x to y
        }
        val (x1, y1) = map(rect.left, rect.top)
        val (x2, y2) = map(rect.right, rect.bottom)
        return IntRect(min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2))
    }

    /** 屏幕上每个原图像素占 [screenPerImage] 个屏幕像素时的采样率：2 的幂，解出来不比屏幕粗 */
    fun sampleSize(screenPerImage: Float): Int {
        if (screenPerImage <= 0f || !screenPerImage.isFinite()) return 1
        val ratio = (1f / screenPerImage).toInt()
        return if (ratio <= 1) 1 else Integer.highestOneBit(ratio)
    }

    /** 采样率 [sample] 下一块在原图上的边长 */
    fun tileSpan(sample: Int): Int = TILE_PX * sample

    /** 采样率 [sample] 下第 [col] 列、第 [row] 行那一块在图上的范围 */
    fun tileRect(col: Int, row: Int, sample: Int, image: IntSize): IntRect {
        val span = tileSpan(sample)
        return IntRect(col * span, row * span, min(image.width, (col + 1) * span), min(image.height, (row + 1) * span))
    }

    /** 和 [visible]（图上坐标）相交的块，按离 [visible] 中心由近到远 */
    fun tilesCovering(visible: IntRect, sample: Int, image: IntSize): List<Pair<Int, Int>> {
        val area = visible.intersect(IntRect(0, 0, image.width, image.height))
        if (area.width <= 0 || area.height <= 0) return emptyList()
        val span = tileSpan(sample)
        val cols = (area.left / span)..((area.right - 1) / span)
        val rows = (area.top / span)..((area.bottom - 1) / span)
        val cx = (area.left + area.right) / 2f
        val cy = (area.top + area.bottom) / 2f
        return cols.flatMap { c -> rows.map { r -> c to r } }.sortedBy { (c, r) ->
            val dx = (c + 0.5f) * span - cx
            val dy = (r + 0.5f) * span - cy
            dx * dx + dy * dy
        }
    }

    /** 解码时比 [tile] 四边各多解 [sample] 个原图像素（解出来多一圈），拼接处插值不露缝 */
    fun withOverlap(tile: IntRect, sample: Int, image: IntSize): IntRect = IntRect(
        max(0, tile.left - sample),
        max(0, tile.top - sample),
        min(image.width, tile.right + sample),
        min(image.height, tile.bottom + sample),
    )
}
