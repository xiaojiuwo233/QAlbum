package com.xjw.qalbum.service.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 认视频：后缀优先，没有后缀的看文件头；HEIF 图片、M4A 音频虽然也是 ftyp 开头，但不是视频 */
class VideoFilesTest {

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
    fun extensionsDecideWithoutReadingTheFile() {
        assertTrue(VideoFiles.hasVideoExtension("/a/b/clip.MP4"))
        assertTrue(VideoFiles.hasVideoExtension("/a/b/movie.mkv"))
        assertFalse(VideoFiles.hasVideoExtension("/a/b/photo.jpg"))
        assertFalse(VideoFiles.hasVideoExtension("/a/b.mp4/noext"))
        assertEquals("video/quicktime", VideoFiles.mimeOf("x.mov"))
        assertEquals("video/mp4", VideoFiles.mimeOf("Cache_123"))
    }

    @Test
    fun onlyLargeFilesWithoutAKnownTypeAreProbed() {
        assertTrue(VideoFiles.needsProbe("/v/Cache_1a2b", 1_000_000))
        assertTrue(VideoFiles.needsProbe("/v/abc.unknown", 1_000_000))
        assertFalse(VideoFiles.needsProbe("/v/Cache_1a2b", 100))
        assertFalse(VideoFiles.needsProbe("/v/a.mp4", 1_000_000))
        assertFalse(VideoFiles.needsProbe("/v/a.jpg", 1_000_000))
        assertFalse(VideoFiles.needsProbe("/v/voice.amr", 1_000_000))
    }

    @Test
    fun containersAreRecognisedByTheirHeader() {
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
    fun picturesAudioAndOtherFilesAreNotVideos() {
        assertFalse(VideoFiles.isVideoHeader(ftyp("heic")))
        assertFalse(VideoFiles.isVideoHeader(ftyp("mif1")))
        assertFalse(VideoFiles.isVideoHeader(ftyp("avif")))
        assertFalse(VideoFiles.isVideoHeader(ftyp("M4A ")))
        assertFalse(VideoFiles.isVideoHeader(header(0xFF, 0xD8, 0xFF, 0xE0)))
        assertFalse(VideoFiles.isVideoHeader(header("RIFF", 0, 0, 0, 0, "WEBP")))
        assertFalse(VideoFiles.isVideoHeader(null))
        assertFalse(VideoFiles.isVideoHeader(ByteArray(4)))
    }
}
