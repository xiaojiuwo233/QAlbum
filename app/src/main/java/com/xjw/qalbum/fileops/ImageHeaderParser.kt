package com.xjw.qalbum.fileops

/**
 * 只读文件头取图片宽高，不解码。JPEG 按段跳到 SOF（EXIF 方向为 5~8 时宽高互换，与显示方向一致），
 * PNG / GIF / WebP / BMP 在前几十字节，HEIF / AVIF 走 meta → iprp → ipco → ispe。
 *
 * [read] 按（偏移, 长度）读字节，读到文件尾时可以返回更短的数组。解析失败返回 null。
 * 在特权进程里运行（见 FileOpsImpl.readImageInfo），也能在 JVM 单元测试里直接跑。
 */
object ImageHeaderParser {

    data class Size(val width: Int, val height: Int)

    fun parse(fileSize: Long, read: (offset: Long, length: Int) -> ByteArray): Size? {
        if (fileSize < 16) return null
        val head = read(0, 32)
        if (head.size < 16) return null
        return when {
            head.u8(0) == 0xFF && head.u8(1) == 0xD8 -> jpeg(fileSize, read)
            head.u8(0) == 0x89 && head.ascii(1, 3) == "PNG" ->
                if (head.size >= 24) Size(head.be32(16), head.be32(20)) else null
            head.ascii(0, 4) == "GIF8" -> Size(head.le16(6), head.le16(8))
            head.ascii(0, 4) == "RIFF" && head.ascii(8, 4) == "WEBP" -> webp(head)
            head.ascii(0, 2) == "BM" ->
                if (head.size >= 26) Size(head.le32(18), kotlin.math.abs(head.le32(22))) else null
            head.ascii(4, 4) == "ftyp" -> heif(fileSize, read)
            else -> null
        }?.takeIf { it.width in 1..MAX_SIDE && it.height in 1..MAX_SIDE }
    }

    // ---- JPEG ----

    private fun jpeg(fileSize: Long, read: (Long, Int) -> ByteArray): Size? {
        var offset = 2L
        var orientation = 1
        repeat(MAX_SEGMENTS) {
            if (offset + 4 > fileSize) return null
            val marker = read(offset, 4)
            if (marker.size < 4 || marker.u8(0) != 0xFF) return null
            val type = marker.u8(1)
            when {
                // 填充字节：FF FF ... 往后挪一个字节再看
                type == 0xFF -> { offset += 1; return@repeat }
                // 无长度的独立标记
                type == 0x01 || type in 0xD0..0xD8 -> { offset += 2; return@repeat }
                type == 0xD9 || type == 0xDA -> return null
            }
            val length = marker.be16(2)
            if (length < 2) return null
            if (type in SOF_MARKERS) {
                val sof = read(offset + 4, 5)
                if (sof.size < 5) return null
                val height = sof.be16(1)
                val width = sof.be16(3)
                return if (orientation in 5..8) Size(height, width) else Size(width, height)
            }
            if (type == 0xE1 && orientation == 1) {
                orientation = exifOrientation(read(offset + 4, minOf(length - 2, EXIF_PEEK)))
            }
            offset += 2 + length
        }
        return null
    }

    /** APP1 段里 TIFF IFD0 的 Orientation（0x0112）；读不到返回 1 */
    private fun exifOrientation(app1: ByteArray): Int {
        if (app1.size < 14 || app1.ascii(0, 4) != "Exif") return 1
        val tiff = 6
        val little = when (app1.ascii(tiff, 2)) {
            "II" -> true
            "MM" -> false
            else -> return 1
        }
        fun u16(at: Int) = if (little) app1.le16(at) else app1.be16(at)
        fun u32(at: Int) = if (little) app1.le32(at) else app1.be32(at)
        val ifd = tiff + u32(tiff + 4)
        if (ifd < tiff || ifd + 2 > app1.size) return 1
        val count = u16(ifd)
        for (i in 0 until count) {
            val entry = ifd + 2 + i * 12
            if (entry + 12 > app1.size) return 1
            if (u16(entry) == 0x0112) return u16(entry + 8).takeIf { it in 1..8 } ?: 1
        }
        return 1
    }

    // ---- WebP ----

    private fun webp(head: ByteArray): Size? {
        if (head.size < 30) return null
        return when (head.ascii(12, 4)) {
            // 有损：帧头 3 字节 + 起始码 9D 01 2A，之后各 14 位宽高
            "VP8 " -> Size(head.le16(26) and 0x3FFF, head.le16(28) and 0x3FFF)
            // 无损：签名 0x2F 之后 14 位宽-1、14 位高-1
            "VP8L" -> {
                if (head.u8(20) != 0x2F) return null
                val bits = head.le32(21)
                Size((bits and 0x3FFF) + 1, ((bits ushr 14) and 0x3FFF) + 1)
            }
            // 扩展格式：画布宽-1、高-1 各 24 位
            "VP8X" -> Size(head.le24(24) + 1, head.le24(27) + 1)
            else -> null
        }
    }

    // ---- HEIF / AVIF ----

    private fun heif(fileSize: Long, read: (Long, Int) -> ByteArray): Size? {
        var offset = 0L
        repeat(MAX_BOXES) {
            val box = boxAt(offset, fileSize, read) ?: return null
            if (box.type == "meta") {
                // meta 是 FullBox：4 字节 version / flags 之后才是子 box
                return findIspe(box.body + 4, box.end, fileSize, read, depth = 0)
            }
            offset = box.end
        }
        return null
    }

    private class Box(val type: String, val body: Long, val end: Long)

    private fun boxAt(offset: Long, fileSize: Long, read: (Long, Int) -> ByteArray): Box? {
        if (offset + 8 > fileSize) return null
        val h = read(offset, 16)
        if (h.size < 8) return null
        var size = h.be32(0).toLong() and 0xFFFFFFFFL
        var header = 8L
        if (size == 1L) {
            if (h.size < 16) return null
            size = (h.be32(8).toLong() shl 32) or (h.be32(12).toLong() and 0xFFFFFFFFL)
            header = 16
        } else if (size == 0L) {
            size = fileSize - offset
        }
        if (size < header || offset + size > fileSize) return null
        return Box(h.ascii(4, 4), offset + header, offset + size)
    }

    /** 在 iprp / ipco 里找图像尺寸属性 ispe；有多个（原图与缩略图）时取最大的 */
    private fun findIspe(start: Long, end: Long, fileSize: Long, read: (Long, Int) -> ByteArray, depth: Int): Size? {
        var offset = start
        var best: Size? = null
        repeat(MAX_BOXES) {
            if (offset >= end) return best
            val box = boxAt(offset, minOf(end, fileSize), read) ?: return best
            when (box.type) {
                "iprp", "ipco" -> if (depth < 3) {
                    findIspe(box.body, box.end, fileSize, read, depth + 1)?.let { found ->
                        if (best == null || found.width.toLong() * found.height > best!!.width.toLong() * best!!.height) best = found
                    }
                }
                "ispe" -> {
                    val b = read(box.body, 12)
                    if (b.size >= 12) {
                        val found = Size(b.be32(4), b.be32(8))
                        if (best == null || found.width.toLong() * found.height > best!!.width.toLong() * best!!.height) best = found
                    }
                }
            }
            offset = box.end
        }
        return best
    }

    // ---- 字节工具 ----

    private fun ByteArray.u8(i: Int) = if (i < size) this[i].toInt() and 0xFF else 0
    private fun ByteArray.be16(i: Int) = (u8(i) shl 8) or u8(i + 1)
    private fun ByteArray.le16(i: Int) = u8(i) or (u8(i + 1) shl 8)
    private fun ByteArray.le24(i: Int) = u8(i) or (u8(i + 1) shl 8) or (u8(i + 2) shl 16)
    private fun ByteArray.be32(i: Int) = (u8(i) shl 24) or (u8(i + 1) shl 16) or (u8(i + 2) shl 8) or u8(i + 3)
    private fun ByteArray.le32(i: Int) = u8(i) or (u8(i + 1) shl 8) or (u8(i + 2) shl 16) or (u8(i + 3) shl 24)
    private fun ByteArray.ascii(i: Int, n: Int): String =
        if (i + n <= size) String(this, i, n, Charsets.ISO_8859_1) else ""

    /** SOF0~SOF15，不含 DHT(C4)、JPG(C8)、DAC(CC) */
    private val SOF_MARKERS = setOf(0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF)
    private const val MAX_SEGMENTS = 64
    private const val MAX_BOXES = 64
    private const val EXIF_PEEK = 4096
    private const val MAX_SIDE = 65_535
}
