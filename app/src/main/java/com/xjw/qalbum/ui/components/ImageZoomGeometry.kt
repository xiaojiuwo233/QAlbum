package com.xjw.qalbum.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.min

internal object ImageZoomGeometry {
    fun clamp(offset: Offset, scale: Float, viewport: Size, image: Size): Offset {
        if (scale <= 1f || viewport.width <= 0f || viewport.height <= 0f) return Offset.Zero
        val fitted = if (image.width > 0f && image.height > 0f && image.width.isFinite() && image.height.isFinite()) {
            val fit = min(viewport.width / image.width, viewport.height / image.height)
            Size(image.width * fit, image.height * fit)
        } else viewport
        val maxX = ((fitted.width * scale - viewport.width) / 2f).coerceAtLeast(0f)
        val maxY = ((fitted.height * scale - viewport.height) / 2f).coerceAtLeast(0f)
        return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
    }

    fun zoomOffset(offset: Offset, centroid: Offset, center: Offset, ratio: Float, pan: Offset = Offset.Zero): Offset =
        (offset - (centroid - center)) * ratio + (centroid - center) + pan
}
