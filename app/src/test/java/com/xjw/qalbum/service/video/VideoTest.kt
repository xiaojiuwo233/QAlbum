package com.xjw.qalbum.service.video

import com.xjw.qalbum.data.db.VideoSql
import com.xjw.qalbum.ui.video.formatDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 视频页：认视频（后缀优先，没有后缀的看文件头；HEIF 图片、M4A 音频虽然也是 ftyp 开头，但不是视频）、
 * 按大小分组、时长显示。
 */
class VideoTest {

    private val mb = 1024L * 1024L

    private fun header(vararg parts: Any): ByteArray {
        val bytes = ArrayList<Byte>()
        parts.forEach { part ->
            when (part) {
                is String -> part.toByteArray(Charsets.ISO_8859_1).forEach { bytes += it }
                is Int -> bytes += part.toByte()
            }
        }
        while (bytes.size < 32) bytes += 0
        return bytes.toByteArray()
    }

    private fun ftyp(brand: String) = header(0, 0, 0, 0x20, "ftyp", brand)

    @Test
    fun `有后缀的按后缀认，不读文件`() {
        assertTrue(VideoFiles.hasVideoExtension("/a/b/clip.MP4"))
        assertTrue(VideoFiles.hasVideoExtension("/a/b/movie.mkv"))
        assertFalse(VideoFiles.hasVideoExtension("/a/b/photo.jpg"))
        assertFalse(VideoFiles.hasVideoExtension("/a/b.mp4/noext"))
        assertEquals("video/quicktime", VideoFiles.mimeOf("x.mov"))
        assertEquals("video/mp4", VideoFiles.mimeOf("Cache_123"))
    }

    @Test
    fun `只有较大且类型不明的文件才读文件头`() {
        assertTrue(VideoFiles.needsProbe("/v/Cache_1a2b", 1_000_000))
        assertTrue(VideoFiles.needsProbe("/v/abc.unknown", 1_000_000))
        assertFalse(VideoFiles.needsProbe("/v/Cache_1a2b", 100))
        assertFalse(VideoFiles.needsProbe("/v/a.mp4", 1_000_000))
        assertFalse(VideoFiles.needsProbe("/v/a.jpg", 1_000_000))
        assertFalse(VideoFiles.needsProbe("/v/voice.amr", 1_000_000))
    }

    @Test
    fun `常见视频容器按文件头认出`() {
        assertTrue(VideoFiles.isVideoHeader(ftyp("isom")))
        assertTrue(VideoFiles.isVideoHeader(ftyp("mp42")))
        assertTrue(VideoFiles.isVideoHeader(ftyp("qt  ")))
        assertTrue(VideoFiles.isVideoHeader(ftyp("3gp5")))
        assertTrue(VideoFiles.isVideoHeader(header(0, 0, 0, 8, "mdat")))
        assertTrue(VideoFiles.isVideoHeader(header(0x1A, 0x45, 0xDF, 0xA3)))
        assertTrue(VideoFiles.isVideoHeader(header("RIFF", 0, 0, 0, 0, "AVI ")))
        assertTrue(VideoFiles.isVideoHeader(header("FLV", 1)))
    }

    @Test
    fun `图片、音频和其他文件不算视频`() {
        assertFalse(VideoFiles.isVideoHeader(ftyp("heic")))
        assertFalse(VideoFiles.isVideoHeader(ftyp("mif1")))
        assertFalse(VideoFiles.isVideoHeader(ftyp("avif")))
        assertFalse(VideoFiles.isVideoHeader(ftyp("M4A ")))
        assertFalse(VideoFiles.isVideoHeader(header(0xFF, 0xD8, 0xFF, 0xE0)))
        assertFalse(VideoFiles.isVideoHeader(header("RIFF", 0, 0, 0, 0, "WEBP")))
        assertFalse(VideoFiles.isVideoHeader(null))
        assertFalse(VideoFiles.isVideoHeader(ByteArray(4)))
    }

    @Test
    fun `每个大小都落在自己组的区间里，标题按区间写`() {
        listOf(0L, 1L, mb - 1, mb, 5 * mb - 1, 5 * mb, 37 * mb, 199 * mb, 200 * mb, 999 * mb, 1024 * mb, 50_000 * mb).forEach { size ->
            val bucket = VideoSql.sizeBucket(size)
            val section = VideoSql.sizeSection(bucket)
            assertTrue("$size → $bucket", size >= section.low && size < section.high)
        }
        assertEquals("1 GB 以上", VideoSql.sizeLabel(VideoSql.sizeBucket(3000 * mb)))
        assertEquals("500 MB – 1 GB", VideoSql.sizeLabel(VideoSql.sizeBucket(700 * mb)))
        assertEquals("20–50 MB", VideoSql.sizeLabel(VideoSql.sizeBucket(21 * mb)))
        assertEquals("1–5 MB", VideoSql.sizeLabel(VideoSql.sizeBucket(mb)))
        assertEquals("1 MB 以下", VideoSql.sizeLabel(VideoSql.sizeBucket(1000)))
    }

    @Test
    fun `SQL 分组键与界面用同样的分界`() {
        val sql = VideoSql.sizeKey("size")
        assertTrue(sql.startsWith("CASE WHEN size >= ${1024 * mb} THEN 1024"))
        assertTrue(sql.contains("WHEN size >= $mb THEN 1 ELSE 0 END"))
    }

    @Test
    fun `时长取整到秒，超过一小时才显示小时`() {
        assertNull(formatDuration(-1))
        assertNull(formatDuration(0))
        assertEquals("0:01", formatDuration(600))
        assertEquals("1:05", formatDuration(65_000))
        assertEquals("1:02:03", formatDuration(3_723_000))
    }
}
