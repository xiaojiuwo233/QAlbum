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

    private companion object {
        const val BLOCK_COLS = 17
        const val BLOCK_ROWS = 16
    }
}
