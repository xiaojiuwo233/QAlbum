package com.xjw.qalbum.service.analysis

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分块指纹纯算法测试（不依赖 Android Bitmap）。
 * Bitmap → 灰度的胶水（[HashEngine.computeBlockHashFromBitmap]）随 pHash 惯例不在 JVM 单测覆盖。
 */
class HashEngineTest {

    @Test
    fun blockHashAllEqualGrayIsAllZero() {
        val gray = IntArray(BLOCK_COLS * BLOCK_ROWS) { 128 }
        val h = HashEngine.blockHashFromGray(gray, BLOCK_COLS, BLOCK_ROWS)
        assertEquals(32, h.size)
        assertTrue("全相同灰度应得全 0 指纹", h.all { it.toInt() == 0 })
    }

    @Test
    fun blockHashStrictlyIncreasingRowsIsAllOne() {
        // 每行从左到右严格递增 → 每次「左 < 右」为真 → 所有 bit = 1
        val gray = IntArray(BLOCK_COLS * BLOCK_ROWS) { i -> (i % BLOCK_COLS) * 10 }
        val h = HashEngine.blockHashFromGray(gray, BLOCK_COLS, BLOCK_ROWS)
        assertTrue("每行递增应得全 1 指纹", h.all { (it.toInt() and 0xFF) == 0xFF })
    }

    @Test
    fun blockHashIsDeterministic() {
        val gray = IntArray(BLOCK_COLS * BLOCK_ROWS) { it % 7 }
        assertArrayEquals(
            HashEngine.blockHashFromGray(gray, BLOCK_COLS, BLOCK_ROWS),
            HashEngine.blockHashFromGray(gray, BLOCK_COLS, BLOCK_ROWS),
        )
    }

    @Test
    fun lumaGridAllEqualIsThatValue() {
        val gray = IntArray(8 * 8) { 100 }
        val grid = HashEngine.lumaGridFromGray(gray, srcSide = 8, gridN = 4)
        assertEquals(16, grid.size)
        assertTrue("全相同灰度每格应等于该值", grid.all { (it.toInt() and 0xFF) == 100 })
    }

    @Test
    fun lumaGridDistinguishesDarkAndBright() {
        // 左半暗(0)、右半亮(200)：4×4 网格每行左 2 格≈0、右 2 格≈200（区分深/浅背景的关键）
        val srcSide = 8
        val gray = IntArray(srcSide * srcSide) { i -> if ((i % srcSide) < srcSide / 2) 0 else 200 }
        val grid = HashEngine.lumaGridFromGray(gray, srcSide, gridN = 4)
        assertEquals(0, grid[0].toInt() and 0xFF)   // 行 0 第 0 格（左）
        assertEquals(200, grid[3].toInt() and 0xFF) // 行 0 第 3 格（右）
    }

    @Test
    fun edgeGridZeroWhenFlat() {
        val grid = HashEngine.edgeGridFromGray(IntArray(8 * 8) { 128 }, srcSide = 8, gridN = 4)
        assertEquals(16, grid.size)
        assertTrue("平坦图无边缘", grid.all { (it.toInt() and 0xFF) == 0 })
    }

    @Test
    fun edgeGridHighOnStripes() {
        // 竖条纹(列交替 0/255)→ 强水平梯度 → 网格高边缘密度
        val srcSide = 8
        val gray = IntArray(srcSide * srcSide) { i -> if ((i % srcSide) % 2 == 0) 0 else 255 }
        val grid = HashEngine.edgeGridFromGray(gray, srcSide, gridN = 4)
        assertTrue("条纹应得高边缘密度", grid.any { (it.toInt() and 0xFF) > 100 })
    }

    private companion object {
        const val BLOCK_COLS = 17
        const val BLOCK_ROWS = 16
    }
}
