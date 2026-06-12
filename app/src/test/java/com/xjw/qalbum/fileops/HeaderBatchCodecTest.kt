package com.xjw.qalbum.fileops

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class HeaderBatchCodecTest {

    @Test
    fun `普通-空-不可读混合的批次能完整还原`() {
        val headers = listOf(
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()),
            null,
            ByteArray(0),
            ByteArray(32) { it.toByte() },
        )
        val unpacked = HeaderBatchCodec.unpack(HeaderBatchCodec.pack(headers), headers.size)
        assertEquals(4, unpacked.size)
        assertArrayEquals(headers[0], unpacked[0])
        assertNull(unpacked[1])
        assertArrayEquals(ByteArray(0), unpacked[2])
        assertArrayEquals(headers[3], unpacked[3])
    }

    @Test
    fun `空批次打包为空数组`() {
        val packed = HeaderBatchCodec.pack(emptyList())
        assertEquals(0, packed.size)
        assertEquals(0, HeaderBatchCodec.unpack(packed, 0).size)
    }

    @Test
    fun `数据截断时抛出异常而不是静默错位`() {
        val packed = HeaderBatchCodec.pack(listOf(ByteArray(8) { 1 }))
        val truncated = packed.copyOf(packed.size - 2)
        assertThrows(IllegalArgumentException::class.java) {
            HeaderBatchCodec.unpack(truncated, 1)
        }
        // 期望条目数多于实际也要抛
        assertThrows(IllegalArgumentException::class.java) {
            HeaderBatchCodec.unpack(packed, 2)
        }
    }
}
