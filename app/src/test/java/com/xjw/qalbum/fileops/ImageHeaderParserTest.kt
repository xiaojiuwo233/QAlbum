package com.xjw.qalbum.fileops

import com.xjw.qalbum.fileops.ImageHeaderParser.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

/** 读文件头取宽高：PNG、GIF、WebP、BMP、JPEG（含 EXIF 方向）、HEIF，读不出时返回 null */
class ImageHeaderParserTest {

    @Test
    fun `PNG 从 IHDR 读宽高`() {
        val png = bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A) + be32(13) + ascii("IHDR") +
            be32(1080) + be32(2400) + ByteArray(13)
        assertEquals(Size(1080, 2400), parse(png))
    }

    @Test
    fun `GIF 读逻辑屏幕宽高`() {
        assertEquals(Size(320, 240), parse(ascii("GIF89a") + le16(320) + le16(240) + ByteArray(16)))
    }

    @Test
    fun `WebP 有损 无损 扩展三种格式`() {
        val lossy = riff("VP8 ", bytes(0x10, 0x02, 0x00, 0x9D, 0x01, 0x2A) + le16(640) + le16(480))
        assertEquals(Size(640, 480), parse(lossy))

        val bits = (1000 - 1) or ((750 - 1) shl 14)
        val lossless = riff("VP8L", bytes(0x2F) + le32(bits))
        assertEquals(Size(1000, 750), parse(lossless))

        val extended = riff("VP8X", ByteArray(4) + le24(4000 - 1) + le24(3000 - 1))
        assertEquals(Size(4000, 3000), parse(extended))
    }

    @Test
    fun `BMP 自上而下存储时高度为负数`() {
        val bmp = ascii("BM") + ByteArray(16) + le32(200) + le32(-100) + ByteArray(8)
        assertEquals(Size(200, 100), parse(bmp))
    }

    @Test
    fun `JPEG 跳过 APP0 DQT 和填充字节找到 SOF`() {
        val jpeg = bytes(0xFF, 0xD8) +
            segment(0xE0, ascii("JFIF") + ByteArray(10)) +
            segment(0xDB, ByteArray(65)) +
            bytes(0xFF, 0xFF) +
            sof(0xC0, width = 4000, height = 3000) +
            sos()
        assertEquals(Size(4000, 3000), parse(jpeg))
    }

    @Test
    fun `JPEG 的 EXIF 方向为 6 时宽高互换 大端和小端都能读`() {
        for (little in listOf(false, true)) {
            val jpeg = bytes(0xFF, 0xD8) + segment(0xE1, exif(orientation = 6, little = little)) +
                sof(0xC2, width = 4000, height = 3000) + sos()
            assertEquals("little=$little", Size(3000, 4000), parse(jpeg))
        }
        val upright = bytes(0xFF, 0xD8) + segment(0xE1, exif(orientation = 1, little = false)) +
            sof(0xC0, width = 4000, height = 3000) + sos()
        assertEquals(Size(4000, 3000), parse(upright))
    }

    @Test
    fun `JPEG 在 SOF 之前遇到图像数据或文件结束时放弃`() {
        assertNull(parse(bytes(0xFF, 0xD8) + segment(0xDB, ByteArray(65)) + sos() + ByteArray(32)))
        assertNull(parse(bytes(0xFF, 0xD8) + segment(0xE0, ByteArray(14))))
    }

    @Test
    fun `HEIF 在 ipco 里取最大的 ispe`() {
        val ipco = box("ipco", fullBox("ispe", be32(512) + be32(384)) + fullBox("ispe", be32(4032) + be32(3024)))
        val meta = fullBox("meta", box("hdlr", ByteArray(24)) + box("iprp", ipco))
        val heic = box("ftyp", ascii("heic") + be32(0) + ascii("mif1") + ascii("heic")) + meta + box("mdat", ByteArray(64))
        assertEquals(Size(4032, 3024), parse(heic))
    }

    @Test
    fun `不认识的格式 太短或宽高为零时返回 null`() {
        assertNull(parse(ascii("not an image at all, just text")))
        assertNull(parse(bytes(0xFF, 0xD8, 0xFF)))
        assertNull(parse(ascii("GIF89a") + le16(0) + le16(240) + ByteArray(16)))
    }

    private fun parse(data: ByteArray) = ImageHeaderParser.parse(data.size.toLong()) { offset, length ->
        val from = offset.coerceAtMost(data.size.toLong()).toInt()
        data.copyOfRange(from, minOf(data.size, from + length))
    }

    private fun segment(type: Int, body: ByteArray) = bytes(0xFF, type) + be16(body.size + 2) + body

    private fun sof(type: Int, width: Int, height: Int) =
        segment(type, bytes(8) + be16(height) + be16(width) + bytes(3, 1, 0x22, 0, 2, 0x11, 1, 3, 0x11, 1))

    private fun sos() = segment(0xDA, ByteArray(10)) + ByteArray(16) + bytes(0xFF, 0xD9)

    /** APP1：Exif 头 + TIFF 头 + IFD0 里一项 Orientation（SHORT） */
    private fun exif(orientation: Int, little: Boolean): ByteArray {
        fun u16(v: Int) = if (little) le16(v) else be16(v)
        fun u32(v: Int) = if (little) le32(v) else be32(v)
        val tiff = ascii(if (little) "II" else "MM") + u16(42) + u32(8)
        val ifd = u16(1) + u16(0x0112) + u16(3) + u32(1) + u16(orientation) + u16(0) + u32(0)
        return ascii("Exif") + bytes(0, 0) + tiff + ifd
    }

    private fun riff(chunk: String, payload: ByteArray): ByteArray {
        val body = ascii("WEBP") + ascii(chunk) + le32(payload.size) + payload + ByteArray(8)
        return ascii("RIFF") + le32(body.size) + body
    }

    private fun box(type: String, body: ByteArray) = be32(body.size + 8) + ascii(type) + body
    private fun fullBox(type: String, body: ByteArray) = box(type, ByteArray(4) + body)

    private fun bytes(vararg values: Any): ByteArray = ByteArrayOutputStream().apply {
        values.forEach { write(if (it is Char) it.code else it as Int) }
    }.toByteArray()

    private fun ascii(text: String) = text.toByteArray(Charsets.ISO_8859_1)
    private fun be16(v: Int) = bytes(v ushr 8 and 0xFF, v and 0xFF)
    private fun be32(v: Int) = bytes(v ushr 24 and 0xFF, v ushr 16 and 0xFF, v ushr 8 and 0xFF, v and 0xFF)
    private fun le16(v: Int) = bytes(v and 0xFF, v ushr 8 and 0xFF)
    private fun le24(v: Int) = bytes(v and 0xFF, v ushr 8 and 0xFF, v ushr 16 and 0xFF)
    private fun le32(v: Int) = bytes(v and 0xFF, v ushr 8 and 0xFF, v ushr 16 and 0xFF, v ushr 24 and 0xFF)
}
