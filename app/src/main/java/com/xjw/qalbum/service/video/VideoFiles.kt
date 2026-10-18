package com.xjw.qalbum.service.video

/** 认视频文件：先看后缀，没有认得的后缀时看文件头（QQ 缓存里的文件常常没有后缀） */
object VideoFiles {

    private val EXTENSIONS = mapOf(
        "mp4" to "video/mp4", "m4v" to "video/x-m4v", "mov" to "video/quicktime", "3gp" to "video/3gpp", "3g2" to "video/3gpp2",
        "mkv" to "video/x-matroska", "webm" to "video/webm", "avi" to "video/x-msvideo", "flv" to "video/x-flv",
        "ts" to "video/mp2t", "m2ts" to "video/mp2t", "mts" to "video/mp2t", "wmv" to "video/x-ms-wmv", "asf" to "video/x-ms-asf",
        "mpg" to "video/mpeg", "mpeg" to "video/mpeg", "vob" to "video/mpeg", "f4v" to "video/mp4", "rmvb" to "application/vnd.rn-realmedia-vbr",
    )

    /** 这些后缀肯定不是视频，不用读文件头 */
    private val NOT_VIDEO = setOf(
        "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "txt", "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "zip", "rar",
        "7z", "apk", "amr", "silk", "slk", "mp3", "m4a", "aac", "wav", "flac", "ogg", "json", "xml", "db", "log", "tmp", "ini",
    )

    /** 读文件头判断的最小大小：太小的不可能是能看的视频 */
    const val MIN_PROBE_SIZE = 16L * 1024

    /** 需要读文件头的字节数 */
    const val HEADER_BYTES = 32

    fun extension(path: String): String {
        val name = path.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) "" else name.substring(dot + 1).lowercase()
    }

    /** 后缀是视频 */
    fun hasVideoExtension(path: String): Boolean = extension(path) in EXTENSIONS

    /** 要不要读文件头：没有视频后缀、也不是明显的别的类型、又够大 */
    fun needsProbe(path: String, size: Long): Boolean =
        size >= MIN_PROBE_SIZE && extension(path).let { it !in EXTENSIONS && it !in NOT_VIDEO }

    fun mimeOf(path: String): String = EXTENSIONS[extension(path)] ?: "video/mp4"

    /** 按文件头认视频容器（MP4 / MOV / 3GP、Matroska / WebM、AVI、FLV、ASF、MPEG） */
    fun isVideoHeader(header: ByteArray?): Boolean {
        val h = header ?: return false
        if (h.size < 12) return false
        val box = String(h, 4, 4, Charsets.ISO_8859_1)
        if (box == "ftyp") {
            val brand = String(h, 8, 4, Charsets.ISO_8859_1).trim().lowercase()
            return brand !in STILL_OR_AUDIO_BRANDS
        }
        if (box == "moov" || box == "mdat" || box == "wide" || box == "free" || box == "skip") return true
        if (h.startsWith(0x1A, 0x45, 0xDF, 0xA3)) return true
        if (String(h, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(h, 8, 4, Charsets.ISO_8859_1) == "AVI ") return true
        if (h.startsWith(0x46, 0x4C, 0x56, 0x01)) return true
        if (h.startsWith(0x30, 0x26, 0xB2, 0x75, 0x8E, 0x66, 0xCF, 0x11)) return true
        if (h.startsWith(0x00, 0x00, 0x01, 0xBA) || h.startsWith(0x00, 0x00, 0x01, 0xB3)) return true
        return false
    }

    /** ftyp 里表示静态图片（HEIF / AVIF）或纯音频（M4A）的品牌 */
    private val STILL_OR_AUDIO_BRANDS = setOf(
        "heic", "heix", "heim", "heis", "hevc", "hevx", "hevm", "hevs", "mif1", "msf1", "avif", "avis", "m4a", "m4b", "m4p", "f4a",
    )

    private fun ByteArray.startsWith(vararg bytes: Int): Boolean =
        size >= bytes.size && bytes.indices.all { this[it] == bytes[it].toByte() }
}
