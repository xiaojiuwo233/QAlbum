package com.xjw.qalbum.ui.imageloading

import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

class TileMathTest {
    @Test
    fun sampleSizeIsThePowerOfTwoThatStaysAtLeastAsSharpAsTheScreen() {
        assertEquals(1, TileMath.sampleSize(2f))
        assertEquals(1, TileMath.sampleSize(1f))
        assertEquals(1, TileMath.sampleSize(0.6f))
        assertEquals(2, TileMath.sampleSize(0.5f))
        assertEquals(2, TileMath.sampleSize(0.3f))
        assertEquals(4, TileMath.sampleSize(0.2f))
        assertEquals(8, TileMath.sampleSize(0.1f))
    }

    @Test
    fun orientedRectsMapBackToRawPixels() {
        // 原图 4×2（横），各方向转正后取一块，换回原图上的位置
        val raw = IntSize(4, 2)
        fun toRaw(rect: IntRect, orientation: Int) = TileMath.toRaw(rect, orientation, raw.width, raw.height)
        assertEquals(IntRect(1, 0, 2, 1), toRaw(IntRect(1, 0, 2, 1), 1))
        assertEquals(IntRect(2, 0, 3, 1), toRaw(IntRect(1, 0, 2, 1), 2))
        assertEquals(IntRect(3, 1, 4, 2), toRaw(IntRect(0, 0, 1, 1), 3))
        assertEquals(IntRect(0, 1, 1, 2), toRaw(IntRect(0, 0, 1, 1), 4))
        // 顺时针转 90° 后是 2×4（竖）：最左一列来自原图最下面一行
        assertEquals(IntSize(2, 4), TileMath.orientedSize(raw.width, raw.height, 6))
        assertEquals(IntRect(0, 1, 4, 2), toRaw(IntRect(0, 0, 1, 4), 6))
        // 逆时针 90°：最左一列来自原图最上面一行
        assertEquals(IntRect(0, 0, 4, 1), toRaw(IntRect(0, 0, 1, 4), 8))
        // 转置：行列对调
        assertEquals(IntRect(0, 0, 4, 1), toRaw(IntRect(0, 0, 1, 4), 5))
        // 反转置
        assertEquals(IntRect(0, 1, 4, 2), toRaw(IntRect(0, 0, 1, 4), 7))
    }

    @Test
    fun visibleTilesComeNearestToTheCenterFirst() {
        val image = IntSize(1080, 12960)
        val tiles = TileMath.tilesCovering(IntRect(0, 0, 1080, 2424), 1, image)
        assertEquals(15, tiles.size)
        assertEquals(1 to 2, tiles.first())
        assertEquals(IntRect(1024, 0, 1080, 512), TileMath.tileRect(2, 0, 1, image))
        assertEquals(IntRect(0, 12800, 512, 12960), TileMath.tileRect(0, 25, 1, image))
    }

    @Test
    fun overlapAddsOneSampledPixelInsideTheImage() {
        val image = IntSize(2000, 2000)
        assertEquals(IntRect(510, 510, 1026, 1026), TileMath.withOverlap(IntRect(512, 512, 1024, 1024), 2, image))
        assertEquals(IntRect(0, 0, 1025, 1025), TileMath.withOverlap(IntRect(0, 0, 1024, 1024), 1, image))
    }
}
