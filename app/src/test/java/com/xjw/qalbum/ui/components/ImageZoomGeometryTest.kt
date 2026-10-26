package com.xjw.qalbum.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    private val phone = Size(1080f, 2424f)

    @Test
    fun onlyImagesMuchTallerThanTheScreenAreLong() {
        assertTrue(ImageZoomGeometry.isLong(phone, Size(1080f, 12960f)))
        assertTrue(ImageZoomGeometry.isLong(phone, Size(1080f, 3500f)))
        assertFalse(ImageZoomGeometry.isLong(phone, Size(1080f, 3000f)))
        assertFalse(ImageZoomGeometry.isLong(phone, Size(1080f, 2400f)))
        assertFalse(ImageZoomGeometry.isLong(phone, Size(4000f, 3000f)))
        // 横屏（平板）也至少要高宽比 3
        assertFalse(ImageZoomGeometry.isLong(Size(2400f, 1600f), Size(1000f, 2500f)))
        assertTrue(ImageZoomGeometry.isLong(Size(2400f, 1600f), Size(1000f, 3200f)))
    }

    @Test
    fun longImageFillsTheWidthFromTheTop() {
        val image = Size(1080f, 12960f)
        val scale = ImageZoomGeometry.fillWidthScale(phone, image)
        assertEquals(1080f / 202f, scale, 0.001f)
        val top = ImageZoomGeometry.topAligned(scale, phone, image)
        assertEquals(0f, top.x, 0.001f)
        assertEquals((2424f * scale - 2424f) / 2f, top.y, 0.5f)
        assertEquals(1f, ImageZoomGeometry.fillWidthScale(phone, Size(4000f, 3000f)), 0.001f)
    }

    @Test
    fun horizontalDragGoesToThePagerAtTheEdge() {
        val long = Size(1080f, 12960f)
        val fill = ImageZoomGeometry.fillWidthScale(phone, long)
        // 长图宽度占满时左右拖不动：横滑翻页
        assertFalse(ImageZoomGeometry.canPanX(Offset.Zero, fill, phone, long, 50f))
        assertFalse(ImageZoomGeometry.canPanX(Offset.Zero, fill, phone, long, -50f))
        // 普通图放大后：拖到右边缘再往右就交给翻页，往左还能拖
        val photo = Size(4000f, 3000f)
        val maxX = ImageZoomGeometry.maxPan(2.5f, phone, photo).x
        assertTrue(maxX > 0f)
        assertFalse(ImageZoomGeometry.canPanX(Offset(maxX, 0f), 2.5f, phone, photo, 50f))
        assertTrue(ImageZoomGeometry.canPanX(Offset(maxX, 0f), 2.5f, phone, photo, -50f))
    }
}
