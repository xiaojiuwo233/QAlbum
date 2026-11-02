package com.xjw.qalbum.ui.dashboard

import com.xjw.qalbum.data.db.dao.ImageDao.CleanedStatsRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 首页「已清理」：压缩省下的空间也算，张数分开说删除和压缩 */
class CleanedStatsTest {

    @Test
    fun `压缩省下的空间也算已清理`() {
        val stats = CleanedStats(deleted = CleanedStatsRow(3, 1_000), compressed = CleanedStatsRow(2, 500))
        assertEquals(1_500, stats.bytes)
        assertFalse(stats.isEmpty)
        assertTrue(CleanedStats().isEmpty)
        // 只压缩过也显示
        assertFalse(CleanedStats(compressed = CleanedStatsRow(1, 10)).isEmpty)
    }

    @Test
    fun `张数分开说删除和压缩`() {
        val deleted = CleanedStatsRow(12_345, 1)
        val compressed = CleanedStatsRow(56, 1)
        assertEquals("已清理 1.2万 张", cleanedCountText(CleanedStats(deleted = deleted), today = false))
        assertEquals("已清理 1.2万 张 · 压缩 56 张", cleanedCountText(CleanedStats(deleted, compressed), today = false))
        assertEquals("已压缩 56 张", cleanedCountText(CleanedStats(compressed = compressed), today = false))
        assertEquals("今日 0 张", cleanedCountText(CleanedStats(), today = true))
        assertEquals("今日压缩 56 张", cleanedCountText(CleanedStats(compressed = compressed), today = true))
        assertEquals("今日 1.2万 张 · 压缩 56 张", cleanedCountText(CleanedStats(deleted, compressed), today = true))
    }
}
