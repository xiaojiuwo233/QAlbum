package com.xjw.qalbum.qq

import com.google.protobuf.CodedInputStream
import com.google.protobuf.WireFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale

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

/** 只读取图片消息段；正文、链接、文件、视频、转发正文均不进入来源索引。 */
object QqPictureCodec {
    const val MAX_MESSAGE_BYTES = 1024 * 1024

    fun decode(blob: ByteArray): List<QqPictureElement> {
        require(blob.size <= MAX_MESSAGE_BYTES) { "消息段过大" }
        val input = CodedInputStream.newInstance(blob)
        val pictures = mutableListOf<QqPictureElement>()
        while (!input.isAtEnd) {
            val tag = input.readTag()
            if (tag == 0) break
            // 9.2.66 实测是重复 40800 消息段；旧版资料中也有 48000，均直接包含 MsgElement。
            if (WireFormat.getTagFieldNumber(tag) in setOf(40800, 48000) && WireFormat.getTagWireType(tag) == 2) {
                picture(input.readByteArray())?.let(pictures::add)
            } else if (!input.skipField(tag)) break
        }
        return pictures
    }

    private fun picture(bytes: ByteArray): QqPictureElement? {
        val input = CodedInputStream.newInstance(bytes)
        var type = 0
        var id = ""
        val md5s = linkedSetOf<String>()
        val paths = linkedSetOf<String>()
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
                else -> if (!input.skipField(tag)) break
            }
        }
        return if (type == 2 && md5s.isNotEmpty()) QqPictureElement(id, md5s, paths) else null
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

    fun relativePaths(md5: String): List<String> {
        require(md5.matches(Regex("[0-9a-fA-F]{32}")))
        return listOf("chatimg", "chatraw", "chatthumb").flatMap { folder ->
            var value = -1L
            (folder + ":" + md5.uppercase(Locale.ROOT)).forEach { char ->
                value = (value shr 8) xor table[((value xor char.code.toLong()) and 255L).toInt()]
            }
            val name = "Cache_" + java.lang.Long.toString(value, 16)
            val path = "$folder/${name.takeLast(3)}/$name"
            if (folder == "chatthumb") listOf(path, path + "_hd") else listOf(path)
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
