package com.xjw.qalbum.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageZoomGeometryTest {
    private val portraitViewport = Size(400f, 800f)

    @Test
    fun landscapePhotoCannotPanVerticallyIntoEmptyLetterbox() {
        val offset = ImageZoomGeometry.clamp(Offset(1000f, 1000f), 2f, portraitViewport, Size(1600f, 900f))
        assertEquals(200f, offset.x, 0.001f)
        assertEquals(0f, offset.y, 0.001f)
    }

    @Test
    fun pinchPreservesTheFocalPoint() {
        val offset = ImageZoomGeometry.zoomOffset(Offset.Zero, Offset(300f, 400f), Offset(200f, 400f), 2f)
        assertEquals(-100f, offset.x, 0.001f)
        assertEquals(0f, offset.y, 0.001f)
    }

    @Test
    fun returningToOriginalSizeResetsPan() {
        assertEquals(Offset.Zero, ImageZoomGeometry.clamp(Offset(100f, 100f), 1f, portraitViewport, Size(1000f, 1000f)))
    }
}
