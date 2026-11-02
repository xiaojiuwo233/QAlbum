package com.xjw.qalbum.qq

import com.google.protobuf.CodedOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** 解析消息里的图片段：图片类型、原图标记、表情子类型，以及只记字段号和小数值的诊断统计 */
class QqPictureCodecTest {
    private val md5 = ByteArray(16) { (it * 17).toByte() }
    private val md5Hex = md5.joinToString("") { "%02x".format(it) }

    private fun encode(block: (CodedOutputStream) -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        val stream = CodedOutputStream.newInstance(out)
        block(stream)
        stream.flush()
        return out.toByteArray()
    }

    /** 一个消息段：45001 ID、45002 类型、45406 MD5，再加图片类型 / 原图标记 / 其他整数字段 / 文本字段 */
    private fun element(
        id: Long,
        type: Int = 2,
        picType: Int? = null,
        original: Boolean? = null,
        varints: Map<Int, Long> = emptyMap(),
        texts: Map<Int, String> = emptyMap(),
    ) = encode { out ->
        out.writeUInt64(45001, id)
        out.writeInt32(45002, type)
        out.writeByteArray(45406, md5)
        picType?.let { out.writeInt32(QqPictureCodec.PIC_TYPE_FIELD, it) }
        original?.let { out.writeInt32(QqPictureCodec.ORIGINAL_FIELD, if (it) 1 else 0) }
        varints.forEach { (field, value) -> out.writeInt64(field, value) }
        texts.forEach { (field, text) -> out.writeByteArray(field, text.toByteArray()) }
    }

    private fun message(vararg elements: ByteArray) = encode { out -> elements.forEach { out.writeByteArray(40800, it) } }

    @Test
    fun `读出图片类型与原图标记`() {
        val pictures = QqPictureCodec.decode(message(element(1, picType = 2000, original = true), element(2)))
        assertEquals(listOf(2000, 0), pictures.map { it.picType })
        assertEquals(listOf(true, false), pictures.map { it.original })
        // 没写子类型字段：未知
        assertEquals(listOf(-1, -1), pictures.map { it.picSubType })
        assertEquals(setOf(md5Hex), pictures.first().md5s)
    }

    @Test
    fun `表情子类型取非 0 的值，摘要是动画表情的也算表情`() {
        fun subType(vararg values: Long, summary: String? = null): Int {
            val element = encode { out ->
                out.writeUInt64(45001, 1)
                out.writeInt32(45002, 2)
                out.writeByteArray(45406, md5)
                values.forEach { out.writeInt64(QqPictureCodec.PIC_SUB_TYPE_FIELD, it) }
                summary?.let { out.writeByteArray(QqPictureCodec.SUMMARY_FIELD, it.toByteArray()) }
            }
            return QqPictureCodec.decode(message(element)).single().picSubType
        }
        assertEquals(-1, subType())
        assertEquals(0, subType(0))
        assertEquals(7, subType(7))
        // 同一段里出现两次：0 不覆盖表情
        assertEquals(1, subType(0, 1))
        assertEquals(1, subType(1, 0))
        assertEquals(1, subType(summary = "[动画表情]"))
        assertEquals(1, subType(0, summary = "[动画表情]"))
        assertEquals(11, subType(11, summary = "[动画表情]"))
        assertEquals(0, subType(0, summary = ""))
    }

    @Test
    fun `诊断统计只记字段号和小数值`() {
        val stats = QqPictureFieldStats()
        QqPictureCodec.decodeElements(
            message(
                element(1, picType = 1000, varints = mapOf(45500 to 7L, 45501 to 123_456_789L), texts = mapOf(45815 to "[动画表情]", 45402 to "私密文件名.jpg")),
                element(2, picType = 2000, varints = mapOf(45500 to 1L)),
                // 文本消息段不统计
                element(3, type = 1, varints = mapOf(45500 to 9L)),
            ),
            stats,
        )
        val text = stats.lines().joinToString("\n")
        assertTrue(text, text.contains("pictures=2"))
        assertTrue(text, text.contains("45416×2{"))
        assertTrue(text, text.contains("45500×2{"))
        assertTrue(text, text.contains("1000/7=1"))
        assertTrue(text, text.contains("2000/1=1"))
        assertTrue(text, text.contains("45501×1{1000/big=1}"))
        assertTrue(text, text.contains("45815×1{1000/[动画表情]=1}"))
        assertTrue(text, text.contains("45402×1{}"))
        // 不记大数值、文件名、MD5，也不统计文本消息段
        assertFalse(text, text.contains("123456789"))
        assertFalse(text, text.contains("私密文件名"))
        assertFalse(text, text.contains(md5Hex))
        assertFalse(text, text.contains("/9="))
    }

    @Test
    fun `统计到上限就不再收集`() {
        val stats = QqPictureFieldStats(limit = 1)
        QqPictureCodec.decodeElements(message(element(1, picType = 1000), element(2, picType = 2000)), stats)
        val text = stats.lines().joinToString("\n")
        assertTrue(text, text.contains("pictures=1"))
        assertFalse(text, text.contains("2000"))
    }
}
