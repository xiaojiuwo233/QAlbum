package com.xjw.qalbum.service.indexer

import java.io.InputStream

/**
 * 通过文件头 magic bytes 识别图片类型
 *
 * QQ chatpic 中的文件名是 `Cache_{hash}`，**没有扩展名**，
 * 只能通过文件头字节判断是否为图片以及具体格式。
 *
 * 性能：仅读取前 16 字节，每张图开销 < 1ms。
 */
object MagicBytesDetector {

    enum class ImageType(val mime: String, val isAnimated: Boolean) {
        JPEG("image/jpeg", false),
        PNG("image/png", false),
        GIF("image/gif", true),
        WEBP("image/webp", false),     // 静态 WebP；动画 WebP 通过 VP8X chunk 区分
        WEBP_ANIM("image/webp", true),
        HEIC("image/heic", false),
        HEIF("image/heif", false),
        BMP("image/bmp", false),
    }

    /** 读取流前 16 字节做识别；调用方需自行关闭流 */
    fun detect(stream: InputStream): ImageType? {
        val header = ByteArray(16)
        val read = stream.read(header)
        if (read < 4) return null
        return detect(header, read)
    }

    /** 对字节数组做识别 */
    fun detect(bytes: ByteArray, length: Int = bytes.size): ImageType? {
        if (length < 4) return null

        // JPEG: FF D8 FF
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) {
            return ImageType.JPEG
        }

        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
        ) {
            return ImageType.PNG
        }

        // GIF: 47 49 46 38 (GIF8)
        if (bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() &&
            bytes[2] == 0x46.toByte() && bytes[3] == 0x38.toByte()
        ) {
            return ImageType.GIF
        }

        // WebP: "RIFF" .... "WEBP"
        if (length >= 12 &&
            bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()
        ) {
            // 进一步区分静态 / 动画：若紧跟 VP8X chunk 且 flag 含 animation bit 则为动画
            // VP8X 在 offset 12 起：'V','P','8','X'，后面 8 字节中 flag 第一字节 bit 1 = 0x02 表示动画
            if (length >= 21 &&
                bytes[12] == 'V'.code.toByte() && bytes[13] == 'P'.code.toByte() &&
                bytes[14] == '8'.code.toByte() && bytes[15] == 'X'.code.toByte()
            ) {
                val flags = bytes[20].toInt()
                if (flags and 0x02 != 0) return ImageType.WEBP_ANIM
            }
            return ImageType.WEBP
        }

        // BMP: 42 4D
        if (bytes[0] == 0x42.toByte() && bytes[1] == 0x4D.toByte()) {
            return ImageType.BMP
        }

        // HEIC / HEIF: offset 4 起 "ftypheic"/"ftypheix"/"ftypmif1" 等
        if (length >= 12 &&
            bytes[4] == 'f'.code.toByte() && bytes[5] == 't'.code.toByte() &&
            bytes[6] == 'y'.code.toByte() && bytes[7] == 'p'.code.toByte()
        ) {
            val brand = String(bytes, 8, 4)
            return when (brand) {
                "heic", "heix", "hevc", "hevx" -> ImageType.HEIC
                "mif1", "msf1", "heim", "heis", "hevm", "hevs" -> ImageType.HEIF
                else -> null
            }
        }

        return null
    }
}
