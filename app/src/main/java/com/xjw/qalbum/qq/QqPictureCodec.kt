package com.xjw.qalbum.qq

import com.google.protobuf.CodedInputStream
import com.google.protobuf.WireFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal fun opaqueKey(vararg parts: String): String = MessageDigest.getInstance("SHA-256")
    .digest(parts.joinToString("\u0000").toByteArray()).joinToString("") { "%02x".format(it) }

internal fun md5Hex(value: String): String = MessageDigest.getInstance("MD5")
    .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

data class QqCipherHeader(val random: String, val hmac: String, val kdf: String) {
    fun password(uid: String): ByteArray = md5Hex(md5Hex(uid) + random).toByteArray()

    companion object {
        const val SIZE = 1024
        fun decode(header: ByteArray): QqCipherHeader {
            require(header.size == SIZE) { "QQ 数据库文件头不完整" }
            val magic = "QQ_NT DB".toByteArray()
            val offset = (0..64).firstOrNull { start -> magic.indices.all { header[start + it] == magic[it] } }
                ?: error("尚不支持此 QQ 数据库格式")
            val length = ByteBuffer.wrap(header, offset + 8, 4).order(ByteOrder.LITTLE_ENDIAN).int
            require(length in 1..(SIZE - offset - 12)) { "QQ 数据库文件头无效" }
            val input = CodedInputStream.newInstance(header, offset + 12, length)
            var random: String? = null
            var hmac = "HMAC_SHA1"
            var kdf = "PBKDF2_HMAC_SHA512"
            while (!input.isAtEnd) {
                val tag = input.readTag()
                if (tag == 0) break
                if (WireFormat.getTagWireType(tag) == WireFormat.WIRETYPE_LENGTH_DELIMITED) {
                    val field = WireFormat.getTagFieldNumber(tag)
                    val value = input.readBytes().toStringUtf8()
                    if (field == 2) random = value
                    if (field == 4 && value in setOf("HMAC_SHA1", "HMAC_SHA256", "HMAC_SHA512")) hmac = value
                    if (value in setOf("PBKDF2_HMAC_SHA1", "PBKDF2_HMAC_SHA256", "PBKDF2_HMAC_SHA512")) kdf = value
                } else if (!input.skipField(tag)) break
            }
            require(!random.isNullOrBlank() && random!!.length <= 64) { "QQ 数据库密钥参数缺失" }
            return QqCipherHeader(random!!, hmac, kdf)
        }
    }
}

data class QqPictureElement(val elementId: String, val md5s: Set<String>, val paths: Set<String>)
internal data class QqDecodedElements(val pictures: List<QqPictureElement>, val hasForward: Boolean)

/** 只读取图片消息段和转发类型标记；正文、链接、文件、视频均不进入来源索引。 */
object QqPictureCodec {
    const val MAX_MESSAGE_BYTES = 1024 * 1024

    fun decode(blob: ByteArray): List<QqPictureElement> = decodeElements(blob).pictures

    internal fun decodeElements(blob: ByteArray): QqDecodedElements {
        require(blob.size <= MAX_MESSAGE_BYTES) { "消息段过大" }
        val input = CodedInputStream.newInstance(blob)
        val pictures = mutableListOf<QqPictureElement>()
        var hasForward = false
        while (!input.isAtEnd) {
            val tag = input.readTag()
            if (tag == 0) break
            // 9.2.66 实测是重复 40800 消息段；旧版资料中也有 48000，均直接包含 MsgElement。
            if (WireFormat.getTagFieldNumber(tag) in setOf(40800, 48000) && WireFormat.getTagWireType(tag) == 2) {
                val element = decodeElementInfo(input.readByteArray())
                element.picture?.let(pictures::add)
                hasForward = hasForward || element.hasForward
            } else if (!input.skipField(tag)) break
        }
        return QqDecodedElements(pictures, hasForward)
    }

    internal fun decodeElement(bytes: ByteArray, offset: Int, length: Int): QqPictureElement? =
        decodeElementInfo(bytes, offset, length).picture

    private data class ElementInfo(val hasForward: Boolean, val picture: QqPictureElement?)

    private fun decodeElementInfo(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): ElementInfo {
        val input = CodedInputStream.newInstance(bytes, offset, length)
        var type = 0
        var id = ""
        val md5s = linkedSetOf<String>()
        val paths = linkedSetOf<String>()
        var forwardApp = false
        while (!input.isAtEnd) {
            val tag = input.readTag()
            if (tag == 0) break
            val field = WireFormat.getTagFieldNumber(tag)
            when {
                field == 45001 && WireFormat.getTagWireType(tag) == 0 -> id = input.readUInt64().toString()
                field == 45002 && WireFormat.getTagWireType(tag) == 0 -> type = input.readInt32()
                (field == 45406 || field == 45424) && WireFormat.getTagWireType(tag) == 2 -> {
                    decodeMd5(input.readByteArray())?.let(md5s::add)
                }
                field == 45403 && WireFormat.getTagWireType(tag) == 2 -> paths += input.readStringRequireUtf8()
                field == 47901 && WireFormat.getTagWireType(tag) == 2 -> {
                    // 新式合并转发可使用 Ark 卡片；只匹配 app 标识，忽略卡片中的展示正文。
                    val metadata = input.readByteArray().toString(Charsets.UTF_8)
                    forwardApp = try {
                        val app = (Json.parseToJsonElement(metadata) as? JsonObject)?.get("app") as? JsonPrimitive
                        app?.content == "com.tencent.multimsg"
                    } catch (_: IllegalArgumentException) { false }
                }
                else -> if (!input.skipField(tag)) break
            }
        }
        return ElementInfo(type == 16 || (type == 10 && forwardApp), if (type == 2 && md5s.isNotEmpty()) QqPictureElement(id, md5s, paths) else null)
    }

    private fun decodeMd5(bytes: ByteArray): String? {
        if (bytes.size == 16) return bytes.joinToString("") { "%02x".format(it) }
        val text = bytes.toString(Charsets.US_ASCII).trim()
        return text.takeIf { it.matches(Regex("[0-9a-fA-F]{32}")) }?.lowercase(Locale.ROOT)
    }
}

object QqCachePaths {
    private val convertedThumbnail = Regex("^(Cache_-?[0-9a-f]+(?:_hd)?)\\.thumb_[0-9]+\\.tmp\\.from_(?:orig|[0-9]+)_avif_aio\\.ready$")
    private val table = LongArray(256) { index ->
        var value = index.toLong()
        repeat(8) { value = if (value and 1L != 0L) (value shr 1) xor -7661587058870466123L else value shr 1 }
        value
    }

    private val folders = listOf("chatimg", "chatraw", "chatthumb")

    private fun crc(text: String): Long {
        var value = -1L
        text.forEach { char -> value = (value shr 8) xor table[((value xor char.code.toLong()) and 255L).toInt()] }
        return value
    }

    /**
     * 同一张图在各目录的文件名 CRC 只差一个与 MD5 无关的常量：CRC 对等长后缀（":" + 32 位 MD5）是线性的，
     * CRC(A:md5) xor CRC(B:md5) = CRC(A:0…0) xor CRC(B:0…0)。以 chatimg 为基准记下各目录的差值。
     */
    private val delta: Map<String, Long> = "0".repeat(32).let { zeros ->
        folders.associateWith { crc("chatimg:$zeros") xor crc("$it:$zeros") }
    }
    private val rawDelta = delta.getValue("chatraw")
    private val thumbDelta = delta.getValue("chatthumb")

    fun relativePaths(md5: String): List<String> {
        require(md5.matches(Regex("[0-9a-fA-F]{32}")))
        return folders.flatMap { folder ->
            val name = "Cache_" + java.lang.Long.toString(crc(folder + ":" + md5.uppercase(Locale.ROOT)), 16)
            val path = "$folder/${name.takeLast(3)}/$name"
            if (folder == "chatthumb") listOf(path, path + "_hd") else listOf(path)
        }
    }

    /**
     * 图片家族键：同一张 QQ 图片的 chatimg / chatraw / chatthumb（含 `_hd`、`.jpg`）以及 Temp 转码缩略图共用一个键
     * （即这张图在 chatimg 下的文件名 CRC），只凭路径算出，不需要读聊天数据库。
     * 不是 `folder:MD5` 命名的文件（旧缓存迁移、导入消息等）返回 null，按独立图片处理。
     *
     * 回填时要对几十万条路径逐个计算，这里直接按下标解析，不切分路径、不走正则。
     */
    fun familyKey(path: String): Long? {
        val nameAt = path.lastIndexOf('/') + 1
        if (nameAt == 0) return null
        val bucketAt = path.lastIndexOf('/', nameAt - 2) + 1
        val bucketLength = nameAt - 1 - bucketAt
        if (bucketLength == 4 && (path.startsWith("Temp", bucketAt) || path.startsWith("temp", bucketAt))) {
            return convertedThumbnailSource(path.substring(nameAt))?.let(::familyKey)
        }
        if (bucketAt == 0) return null
        val folderAt = path.lastIndexOf('/', bucketAt - 2) + 1
        val folderLength = bucketAt - 1 - folderAt
        val thumb = folderLength == 9 && path.startsWith("chatthumb", folderAt)
        val shift = when {
            thumb -> thumbDelta
            folderLength == 7 && path.startsWith("chatimg", folderAt) -> 0L
            folderLength == 7 && path.startsWith("chatraw", folderAt) -> rawDelta
            else -> return null
        }
        if (!path.startsWith("Cache_", nameAt)) return null
        val stemEnd = if (path.endsWith(".jpg")) path.length - 4 else path.length
        val hd = stemEnd >= 3 && path.regionMatches(stemEnd - 3, "_hd", 0, 3)
        if (hd && !thumb) return null
        val end = if (hd) stemEnd - 3 else stemEnd
        // 分桶目录是缓存名（不含 _hd / .jpg）的末 3 位
        if (bucketLength != 3 || !path.regionMatches(bucketAt, path, end - 3, 3)) return null
        val negative = path.getOrNull(nameAt + 6) == '-'
        val digitsAt = nameAt + if (negative) 7 else 6
        val digits = end - digitsAt
        // 只认 QQ 自己会写出的规范十六进制（Long.toString(crc, 16)：小写、无前导零、无 -0）
        if (digits !in 1..16 || path[digitsAt] == '0' && (digits > 1 || negative)) return null
        var magnitude = 0L
        for (index in digitsAt until end) {
            val char = path[index]
            val digit = when (char) {
                in '0'..'9' -> char - '0'
                in 'a'..'f' -> char - 'a' + 10
                else -> return null
            }
            magnitude = (magnitude shl 4) or digit.toLong()
        }
        // 16 位时不能越界：正数最大 7fff…，负数最小 -8000…
        val value = when {
            !negative -> magnitude.takeIf { it >= 0 } ?: return null
            magnitude >= 0 || magnitude == Long.MIN_VALUE -> -magnitude
            else -> return null
        }
        return value xor shift
    }

    /** 与 [path] 同一家族的全部候选缓存路径（同一根目录下，不保证存在；Temp 转码缩略图不在其列） */
    fun familyPaths(path: String): List<String> {
        val key = familyKey(path) ?: return emptyList()
        val parts = path.split('/')
        val depth = if (parts[parts.size - 2] == "Temp" || parts[parts.size - 2] == "temp") 2 else 3
        val base = parts.dropLast(depth).joinToString("/")
        return folders.flatMap { folder ->
            val name = "Cache_" + java.lang.Long.toString(key xor delta.getValue(folder), 16)
            val variant = "$base/$folder/${name.takeLast(3)}/$name"
            val variants = if (folder == "chatthumb") listOf(variant, variant + "_hd") else listOf(variant)
            variants.flatMap { listOf(it, "$it.jpg") }
        }
    }

    fun paths(root: String, picture: QqPictureElement): Set<String> = buildSet {
        val base = root.trimEnd('/')
        picture.md5s.forEach { md5 -> relativePaths(md5).forEach { add("$base/$it") } }
        picture.paths.forEach { path ->
            // 只关联已经配置的图片目录，不把任意聊天附件路径作为图片读取入口。
            if (path.startsWith("$base/") && path.removePrefix("$base/").split('/').none { it == ".." }) add(path)
        }
    }

    fun convertedThumbnailSource(filename: String): String? {
        val name = convertedThumbnail.matchEntire(filename)?.groupValues?.get(1) ?: return null
        val base = name.removeSuffix("_hd")
        return "chatthumb/${base.takeLast(3)}/$name"
    }
}
