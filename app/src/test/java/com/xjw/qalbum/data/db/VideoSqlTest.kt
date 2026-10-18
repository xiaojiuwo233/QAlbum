package com.xjw.qalbum.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 视频按大小分组：每个大小落在自己组的区间里，标题按区间写 */
class VideoSqlTest {

    private val mb = 1024L * 1024L

    @Test
    fun everySizeFallsInsideItsBucket() {
        listOf(0L, 1L, mb - 1, mb, 5 * mb - 1, 5 * mb, 37 * mb, 199 * mb, 200 * mb, 999 * mb, 1024 * mb, 50_000 * mb).forEach { size ->
            val bucket = VideoSql.sizeBucket(size)
            val section = VideoSql.sizeSection(bucket)
            assertTrue("$size → $bucket", size >= section.low && size < section.high)
        }
    }

    @Test
    fun labelsDescribeTheRange() {
        assertEquals("1 GB 以上", VideoSql.sizeLabel(VideoSql.sizeBucket(3000 * mb)))
        assertEquals("500 MB – 1 GB", VideoSql.sizeLabel(VideoSql.sizeBucket(700 * mb)))
        assertEquals("20–50 MB", VideoSql.sizeLabel(VideoSql.sizeBucket(21 * mb)))
        assertEquals("1–5 MB", VideoSql.sizeLabel(VideoSql.sizeBucket(mb)))
        assertEquals("1 MB 以下", VideoSql.sizeLabel(VideoSql.sizeBucket(1000)))
    }

    @Test
    fun theSqlKeyUsesTheSameThresholds() {
        val sql = VideoSql.sizeKey("size")
        assertTrue(sql.startsWith("CASE WHEN size >= ${1024 * mb} THEN 1024"))
        assertTrue(sql.contains("WHEN size >= $mb THEN 1 ELSE 0 END"))
    }
}
