package com.xjw.qalbum.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.max
import kotlin.math.min

internal object ImageZoomGeometry {
    /** 长图：高宽比至少 3，并且比视口细长得多（1.4 倍以上）。整张放下太窄看不清 */
    private const val LONG_MIN_ASPECT = 3f
    private const val LONG_OVER_VIEWPORT = 1.4f

    fun clamp(offset: Offset, scale: Float, viewport: Size, image: Size): Offset {
        if (scale <= 1f || viewport.width <= 0f || viewport.height <= 0f) return Offset.Zero
        val maxPan = maxPan(scale, viewport, image)
        return Offset(offset.x.coerceIn(-maxPan.x, maxPan.x), offset.y.coerceIn(-maxPan.y, maxPan.y))
    }

    fun zoomOffset(offset: Offset, centroid: Offset, center: Offset, ratio: Float, pan: Offset = Offset.Zero): Offset =
        (offset - (centroid - center)) * ratio + (centroid - center) + pan

    /** 整张放进视口时的大小（[image] 只看宽高比；不知道时按视口） */
    fun fitted(viewport: Size, image: Size): Size {
        if (image.width <= 0f || image.height <= 0f || !image.width.isFinite() || !image.height.isFinite()) return viewport
        val fit = min(viewport.width / image.width, viewport.height / image.height)
        return Size(image.width * fit, image.height * fit)
    }

    /** 放大到 [scale] 时能平移的最大距离（左右、上下各自） */
    fun maxPan(scale: Float, viewport: Size, image: Size): Offset {
        val fitted = fitted(viewport, image)
        return Offset(
            ((fitted.width * scale - viewport.width) / 2f).coerceAtLeast(0f),
            ((fitted.height * scale - viewport.height) / 2f).coerceAtLeast(0f),
        )
    }

    /** 往 [dx] 的方向（正 = 手指往右）还能不能横着拖；拖到边了就把横滑交给翻页 */
    fun canPanX(offset: Offset, scale: Float, viewport: Size, image: Size, dx: Float): Boolean {
        val maxX = maxPan(scale, viewport, image).x
        if (maxX < 0.5f) return false
        return if (dx > 0f) offset.x < maxX - 0.5f else offset.x > -maxX + 0.5f
    }

    fun isLong(viewport: Size, image: Size): Boolean {
        if (viewport.width <= 0f || viewport.height <= 0f || image.width <= 0f || image.height <= 0f) return false
        return image.height / image.width >= max(LONG_MIN_ASPECT, viewport.height / viewport.width * LONG_OVER_VIEWPORT)
    }

    /** 宽度占满视口要放大的倍数（相对整张放下） */
    fun fillWidthScale(viewport: Size, image: Size): Float {
        val fitted = fitted(viewport, image)
        return if (fitted.width > 0f) max(1f, viewport.width / fitted.width) else 1f
    }

    /** 放大到 [scale] 时图片顶边贴着视口顶边（左右居中） */
    fun topAligned(scale: Float, viewport: Size, image: Size): Offset =
        Offset(0f, maxPan(scale, viewport, image).y)
}
