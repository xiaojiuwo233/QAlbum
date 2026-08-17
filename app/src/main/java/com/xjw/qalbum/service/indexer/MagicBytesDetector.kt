package com.xjw.qalbum.service.indexer

import java.io.InputStream

/**
 * 通过文件头 magic bytes 识别图片类型
 *
 * QQ chatpic 中的文件名是 `Cache_{hash}`，**没有扩展名**，
 * 只能通过文件头字节判断是否为图片以及具体格式。
 *
 * 性能：仅读取前 32 字节，每张图开销 < 1ms。
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
        AVIF("image/avif", false),      // 系统解码需 API 31+，低版本设备解码失败不代表文件损坏
        AVIF_ANIM("image/avif", true),  // avis brand：AVIF 图像序列（动图）
        BMP("image/bmp", false),
    }

    /** 读取流前 32 字节做识别（含 ftyp 兼容 brand 窗口）；调用方需自行关闭流 */
    fun detect(stream: InputStream): ImageType? {
        val header = ByteArray(32)
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
                "avif" -> ImageType.AVIF
                "avis" -> ImageType.AVIF_ANIM
                // mif1/msf1 是通用壳 brand（旧版 libheif 编码的 AVIF 也用它）：
                // 兼容 brand 列表含 avif 时按 AVIF 归类，避免误判 HEIF 后在
                // 不支持 AVIF 解码的设备上走「损坏」判定链
                "mif1", "msf1", "heim", "heis", "hevm", "hevs" ->
                    if (hasCompatibleBrand(bytes, length, "avif")) ImageType.AVIF else ImageType.HEIF
                else -> null
            }
        }

        return null
    }

    /** ftyp box 的兼容 brand 列表：offset 16 起每 4 字节一项，止于 box 尾（bytes[0..3] 为 box 大小）或已读长度 */
    private fun hasCompatibleBrand(bytes: ByteArray, length: Int, brand: String): Boolean {
        val boxSize = ((bytes[0].toInt() and 0xFF) shl 24) or
            ((bytes[1].toInt() and 0xFF) shl 16) or
            ((bytes[2].toInt() and 0xFF) shl 8) or
            (bytes[3].toInt() and 0xFF)
        val end = if (boxSize in 16..length) boxSize else length
        var offset = 16
        while (offset + 4 <= end) {
            if (bytes[offset] == brand[0].code.toByte() &&
                bytes[offset + 1] == brand[1].code.toByte() &&
                bytes[offset + 2] == brand[2].code.toByte() &&
                bytes[offset + 3] == brand[3].code.toByte()
            ) {
                return true
            }
            offset += 4
        }
        return false
    }
}
