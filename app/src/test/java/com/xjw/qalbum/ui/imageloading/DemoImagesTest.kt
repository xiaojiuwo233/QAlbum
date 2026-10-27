package com.xjw.qalbum.ui.imageloading

import coil3.size.Dimension
import coil3.size.Scale
import coil3.size.Size
import org.junit.Assert.assertEquals
import org.junit.Test

/** 演示模式示意图的大小：比例同原图，格子铺满、看图放进，长图画宽一些 */
class DemoImagesTest {
    private fun box(w: Int, h: Int) = Size(Dimension(w), Dimension(h))

    @Test
    fun `格子裁切时铺满`() {
        assertEquals(400 to 300, DemoImages.pixelSize(4000, 3000, box(300, 300), Scale.FILL))
    }

    @Test
    fun `看图时放进屏幕`() {
        assertEquals(1080 to 810, DemoImages.pixelSize(4000, 3000, box(1080, 2400), Scale.FIT))
    }

    @Test
    fun `长图至少按 1080 宽画，长边不超过 4096`() {
        assertEquals(1080 to 3600, DemoImages.pixelSize(1000, 3333, box(720, 2400), Scale.FIT).let { (w, h) -> w to (h / 100 * 100) })
        assertEquals(410 to 4096, DemoImages.pixelSize(1000, 10000, box(240, 2400), Scale.FIT))
    }

    @Test
    fun `不知道尺寸时按方图，不限大小时长边 1440`() {
        assertEquals(300 to 300, DemoImages.pixelSize(0, 0, box(300, 300), Scale.FILL))
        assertEquals(1440 to 1080, DemoImages.pixelSize(4000, 3000, Size.ORIGINAL, Scale.FIT))
    }
}
