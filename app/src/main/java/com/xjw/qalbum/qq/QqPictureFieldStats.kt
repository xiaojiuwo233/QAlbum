package com.xjw.qalbum.qq

import com.google.protobuf.CodedInputStream
import com.google.protobuf.InvalidProtocolBufferException
import com.google.protobuf.WireFormat

/**
 * 读取来源时的诊断统计：图片消息段（45002 = 2）里各字段出现的次数与取值分布，用来在真机数据上核对
 * 表情子类型等还没确认的字段号。只记字段号、0–32 的小整数（按图片类型 45416 分列）和固定的系统摘要
 * （如「[动画表情]」），不记文本、MD5、路径或大数值，可以放进导出的日志。每次读取最多统计 [limit] 个图片段。
 */
internal class QqPictureFieldStats(private val limit: Int = 50_000) {
    private var pictures = 0
    private val present = HashMap<String, Int>()
    private val values = HashMap<String, HashMap<String, Int>>()

    /** 还要不要统计下一个图片段 */
    fun wants(): Boolean = pictures < limit

    /** 一个图片段：[varints] 为（字段号, 值），[blobs] 为长度字段的（字段号, 内容） */
    fun record(picType: Int, varints: List<Pair<Int, Long>>, blobs: List<Pair<Int, ByteArray>>) {
        pictures++
        for ((field, value) in varints) add(field.toString(), picType, value, raw = field == QqPictureCodec.PIC_TYPE_FIELD)
        for ((field, bytes) in blobs) {
            val name = field.toString()
            present.merge(name, 1, Int::plus)
            summary(bytes)?.let { bucket(name).merge("$picType/$it", 1, Int::plus) }
            // 嵌套消息只看一层整数字段
            nested(bytes)?.forEach { (child, value) -> add("$name.$child", picType, value, raw = false) }
        }
    }

    private fun add(name: String, picType: Int, value: Long, raw: Boolean) {
        present.merge(name, 1, Int::plus)
        val key = when {
            raw -> if (value in 0..99_999) value.toString() else "big"
            value in 0..32 -> "$picType/$value"
            value < 0 -> "$picType/neg"
            else -> "$picType/big"
        }
        bucket(name).merge(key, 1, Int::plus)
    }

    private fun bucket(name: String) = values.getOrPut(name) { HashMap() }

    /** 日志行：「字段×出现次数{图片类型/值=次数,…}」，每行十来个字段 */
    fun lines(): List<String> {
        if (pictures == 0) return emptyList()
        val fields = present.keys.sortedWith(compareBy<String> { it.substringBefore('.').toIntOrNull() ?: 0 }
            .thenBy { it.substringAfter('.', "").toIntOrNull() ?: -1 })
        val parts = fields.map { field ->
            val buckets = values[field].orEmpty().entries.sortedByDescending { it.value }.take(MAX_BUCKETS)
                .joinToString(",") { "${it.key}=${it.value}" }
            "$field×${present[field]}{$buckets}"
        }
        val chunks = parts.chunked(FIELDS_PER_LINE)
        return chunks.mapIndexed { index, chunk ->
            "QQ picture fields ${index + 1}/${chunks.size} (pictures=$pictures): " + chunk.joinToString(" ")
        }
    }

    private fun summary(bytes: ByteArray): String? {
        if (bytes.size > 64) return null
        val text = bytes.toString(Charsets.UTF_8)
        return text.takeIf { it in SUMMARIES }
    }

    /** 能完整解析成 protobuf 的短内容：返回其中的整数字段；不是嵌套消息时返回 null */
    private fun nested(bytes: ByteArray): List<Pair<Int, Long>>? {
        if (bytes.size < 2 || bytes.size > 256) return null
        return try {
            val input = CodedInputStream.newInstance(bytes)
            val out = ArrayList<Pair<Int, Long>>()
            while (!input.isAtEnd) {
                val tag = input.readTag()
                val field = WireFormat.getTagFieldNumber(tag)
                if (tag == 0 || field > 100_000) return null
                when (WireFormat.getTagWireType(tag)) {
                    WireFormat.WIRETYPE_VARINT -> out += field to input.readInt64()
                    WireFormat.WIRETYPE_LENGTH_DELIMITED, WireFormat.WIRETYPE_FIXED32, WireFormat.WIRETYPE_FIXED64 ->
                        if (!input.skipField(tag)) return null
                    else -> return null
                }
            }
            out
        } catch (_: InvalidProtocolBufferException) {
            null
        }
    }

    private companion object {
        const val MAX_BUCKETS = 16
        const val FIELDS_PER_LINE = 12

        /** QQ 自己写进消息的图片摘要（不是用户内容） */
        val SUMMARIES = setOf("[动画表情]", "[表情]", "[图片]", "[闪照]", "[GIF]", "[动图]", "[表情包]", "[超级表情]", "[商城表情]", "[自定义表情]")
    }
}
