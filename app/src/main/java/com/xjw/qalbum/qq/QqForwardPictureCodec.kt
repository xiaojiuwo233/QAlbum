package com.xjw.qalbum.qq

import com.google.protobuf.CodedInputStream
import com.google.protobuf.InvalidProtocolBufferException
import com.google.protobuf.WireFormat

/** QAlbum 的转发分类：跨群聊、私聊及账号使用同一个会话键，发送人仍按账号独立归类。 */
object QqForwardSource {
    const val CHAT_TYPE = 3
    const val KEY = "qalbum:qq:forwarded-records"
    const val NAME = "转发的聊天记录"
}

internal data class QqForwardPicture(val identity: String, val picture: QqPictureElement)
internal data class QqForwardPictures(val pictures: List<QqForwardPicture>, val incomplete: Boolean = false)

/**
 * 40900 = 缓存子消息集合。只解析消息定位、图片段与更深一层的子记录，忽略正文和内层人物。
 * 所有子记录共享输入数组，以免多层嵌套反复复制大 BLOB；身份始终由外层消息的导入器提供。
 */
internal object QqForwardPictureCodec {
    const val MAX_CACHE_BYTES = 8 * 1024 * 1024
    private const val MAX_DEPTH = 16
    private const val MAX_RECORDS = 10_000
    private const val MAX_PICTURES = 5_000

    fun decode(bytes: ByteArray): QqForwardPictures {
        if (bytes.size > MAX_CACHE_BYTES) return QqForwardPictures(emptyList(), incomplete = true)
        val context = Context(bytes)
        try {
            val input = CodedInputStream.newInstance(bytes)
            var index = 0
            while (!input.isAtEnd) {
                val tag = input.readTag()
                if (tag == 0) break
                if (WireFormat.getTagFieldNumber(tag) == 40900 && WireFormat.getTagWireType(tag) == 2) {
                    val length = input.readRawVarint32()
                    val offset = input.totalBytesRead
                    input.skipRawBytes(length)
                    context.record(offset, length, emptyList(), index++, 1)
                } else if (!input.skipField(tag)) break
            }
        } catch (_: InvalidProtocolBufferException) { context.incomplete = true }
        return QqForwardPictures(context.pictures, context.incomplete)
    }

    private data class Span(val offset: Int, val length: Int, val index: Int)

    private class Context(private val bytes: ByteArray) {
        val pictures = ArrayList<QqForwardPicture>()
        var incomplete = false
        private var records = 0

        fun record(offset: Int, length: Int, ancestors: List<String>, index: Int, depth: Int) {
            if (depth > MAX_DEPTH || ++records > MAX_RECORDS || pictures.size >= MAX_PICTURES) {
                incomplete = true
                return
            }
            try {
                val input = CodedInputStream.newInstance(bytes, offset, length)
                var messageId = ""
                val elements = ArrayList<Span>()
                val children = ArrayList<Span>()
                while (!input.isAtEnd) {
                    val tag = input.readTag()
                    if (tag == 0) break
                    val field = WireFormat.getTagFieldNumber(tag)
                    val wire = WireFormat.getTagWireType(tag)
                    when {
                        field == 40001 && wire == 0 -> messageId = java.lang.Long.toUnsignedString(input.readUInt64())
                        (field == 40800 || field == 40900) && wire == 2 -> {
                            val size = input.readRawVarint32()
                            val start = offset + input.totalBytesRead
                            input.skipRawBytes(size)
                            val spans = if (field == 40800) elements else children
                            val limit = if (field == 40800) MAX_PICTURES else MAX_RECORDS
                            if (spans.size < limit) spans += Span(start, size, spans.size) else incomplete = true
                        }
                        else -> if (!input.skipField(tag)) break
                    }
                }
                // 使用内层消息 ID 定位图片，永远不使用内层发送者构建来源。
                val path = ancestors + messageId.ifBlank { "node:$index" }
                for (element in elements) {
                    if (pictures.size >= MAX_PICTURES) { incomplete = true; break }
                    if (element.length > QqPictureCodec.MAX_MESSAGE_BYTES) { incomplete = true; continue }
                    try {
                        val picture = QqPictureCodec.decodeElement(bytes, element.offset, element.length) ?: continue
                        val identity = (path + picture.elementId.ifBlank { "element:${element.index}" }).joinToString("/")
                        pictures += QqForwardPicture(identity, picture)
                    } catch (_: InvalidProtocolBufferException) { incomplete = true }
                }
                for (child in children) record(child.offset, child.length, path, child.index, depth + 1)
            } catch (_: InvalidProtocolBufferException) { incomplete = true }
        }
    }
}
