package com.xjw.qalbum.ui.gallery

import com.xjw.qalbum.imageRow
import com.xjw.qalbum.ui.components.ScrollDirectory
import com.xjw.qalbum.ui.components.scrubGain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** 分组标题的范围（点标题全选用）、合并显示时的大小分组，以及快速滚动条的目录 */
class TimelineSectionsTest {

    @Test
    fun `大小分组的区间与分组键一一对应`() {
        val random = Random(19)
        val sizes = listOf(0L, 1, 10_239, 10_240, 99_999, 102_399, 102_400, 1_023_999, 1_024_000, 1_048_575, 1_048_576,
            3_500_000, 4_400_000, 10_485_759, 10_485_760, 500_000_000) + List(5_000) { random.nextLong(0, 30_000_000) }
        for (size in sizes) {
            val bucket = sizeBucketId(size)
            val section = sizeBucketSection(bucket)
            assertTrue("$size", size >= section.low && size < section.high)
            assertEquals("$size", bucket, sizeBucketId(section.low))
            if (section.high != Long.MAX_VALUE) assertEquals("$size", bucket, sizeBucketId(section.high - 1))
        }
    }

    @Test
    fun `合并显示时三个版本合计 4 MB 多的图归在 4 MB 组`() {
        val image = image(size = 3_355_443, variantBytes = 4_508_876, variantCount = 3)
        assertEquals("3 MB", sizeBucketLabel(FileSize(image)))
        assertEquals("4 MB", sizeBucketLabel(StackedSize(image)))
        val section = sizeBucketSection(sizeBucketId(StackedSize(image)))
        assertTrue(image.variantBytes >= section.low && image.variantBytes < section.high)
    }

    @Test
    fun `一天的范围覆盖当天任意时刻`() {
        val random = Random(7)
        repeat(2_000) {
            val time = random.nextLong(1_500_000_000_000, 1_800_000_000_000)
            val day = daySection(time)
            assertTrue(time >= day.from && time < day.until)
            assertEquals(dayKey(time), dayKey(day.from))
            assertEquals(dayKey(time), dayKey(day.until - 1))
            assertNotEquals(dayKey(time), dayKey(day.until))
        }
    }

    @Test
    fun `目录按比例定位到分组`() {
        val directory = ScrollDirectory(
            listOf(
                ScrollDirectory.Entry(10, "A", "a"),
                ScrollDirectory.Entry(30, "B", null),
                ScrollDirectory.Entry(60, "C", "c"),
            ),
        )
        assertEquals(100, directory.total)
        assertEquals(listOf(0, 0, 1, 1, 2, 2), listOf(0f, 0.099f, 0.1f, 0.399f, 0.4f, 1f).map(directory::entryAt))
        assertEquals("B", directory.labelAt(0.2f))
        assertEquals(listOf(0f to "a", 0.4f to "c"), directory.marks)
        assertEquals(-1, ScrollDirectory(emptyList()).entryAt(0.5f))
    }

    @Test
    fun `慢拖精细、快划按满比例`() {
        assertEquals(0.05f, scrubGain(0f), 1e-6f)
        assertEquals(0.05f, scrubGain(0.05f), 1e-6f)
        assertEquals(1f, scrubGain(5f), 1e-6f)
        assertTrue(scrubGain(0.3f) < scrubGain(0.8f))
        assertTrue(scrubGain(0.8f) < 1f)
    }

    @Test
    fun `日期目录跨年时按年份标刻度，只有一年时按月份`() {
        // date_bucket = year * 1000 + dayOfYear：2026-10-07、2026-04-10、2025-10-27
        val severalYears = dateDirectory(mapOf(2_026_280L to 10, 2_026_100L to 5, 2_025_300L to 20), ascending = false)
        assertEquals(listOf("2026年10月7日", "2026年4月10日", "2025年10月27日"), severalYears.entries.map { it.label })
        // 落点对齐用的标题键与图库日期标题一致
        assertEquals(listOf("d-2026280", "d-2026100", "d-2025300"), severalYears.entries.map { it.key })
        assertEquals(listOf("2026", null, "2025"), severalYears.entries.map { it.mark })
        val oneYear = dateDirectory(mapOf(2_026_280L to 10, 2_026_279L to 3, 2_026_100L to 5), ascending = true)
        assertEquals(listOf("4月", "10月", null), oneYear.entries.map { it.mark })
    }

    @Test
    fun `大小目录以 MB 档和「1 MB 以下」为刻度`() {
        val directory = sizeDirectory(mapOf(100_000L to 2, 10_004L to 3, 10_001L to 4, 1_005L to 5, 1_002L to 6, 0L to 1))
        assertEquals(listOf("10 MB 以上", "4 MB", "1 MB", "500 KB", "200 KB", "10 KB 以下"), directory.entries.map { it.label })
        assertEquals("s-10004", directory.entries[1].key)
        assertEquals(listOf("10 MB 以上", "4 MB", "1 MB", "1 MB 以下", null, null), directory.entries.map { it.mark })
        // 来源页「最小优先」
        val ascending = sizeDirectory(mapOf(10_004L to 3, 1_002L to 6, 0L to 1), ascending = true)
        assertEquals(listOf("10 KB 以下", "200 KB", "4 MB"), ascending.entries.map { it.label })
        assertEquals(listOf("1 MB 以下", null, "4 MB"), ascending.entries.map { it.mark })
    }

    private fun image(size: Long, variantBytes: Long, variantCount: Int) =
        imageRow("/p", "chatraw", size = size, variantCount = variantCount, variantBytes = variantBytes)
}
