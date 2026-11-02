package com.xjw.qalbum.analytics

import com.xjw.qalbum.CHATPIC
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException

/** 上传前脱敏：路径、uid、长数字、长十六进制都去掉，类型和栈帧保留；不用改的原样返回 */
class CrashSanitizerTest {

    private val qqPath = "$CHATPIC/chatimg/1a2/Cache_6d3f"

    @Test
    fun `消息里的路径、QQ 号、uid、十六进制串都换成占位符`() {
        val message = "open failed: $qqPath: ENOENT; uin 1234567890 uid u_AbCdEf123456 key 0123456789abcdef0123 " +
            "uri content://media/external/images/1"
        val clean = CrashSanitizer.sanitizeMessage(message)
        assertFalse(clean, "/storage" in clean)
        assertFalse(clean, "1234567890" in clean)
        assertFalse(clean, "u_AbCdEf123456" in clean)
        assertFalse(clean, "0123456789abcdef0123" in clean)
        assertFalse(clean, "content://" in clean)
        assertTrue(clean, clean.startsWith("open failed: <path>: ENOENT; uin <n> uid <uid> key <hex> uri <uri>"))
    }

    @Test
    fun `短数字和普通文字不动`() {
        val message = "Index 3 out of bounds for length 3 at col 1234"
        assertEquals(message, CrashSanitizer.sanitizeMessage(message))
    }

    @Test
    fun `没有要改的原样返回`() {
        val error = IllegalStateException("decode failed")
        assertSame(error, CrashSanitizer.sanitize(error))
    }

    @Test
    fun `按原类型重建，栈帧保留`() {
        val error = FileNotFoundException("$qqPath: open failed")
        val clean = CrashSanitizer.sanitize(error)
        assertEquals(FileNotFoundException::class.java, clean.javaClass)
        assertEquals("<path>: open failed", clean.message)
        assertArrayEquals(error.stackTrace, clean.stackTrace)
    }

    @Test
    fun `原因与附带的异常一起脱敏`() {
        val cause = IOException("/data/user/0/com.tencent.mobileqq/files/uid/123456789###u_abcdefgh")
        val error = RuntimeException("read failed", cause).apply { addSuppressed(IOException("group 987654321")) }
        val clean = CrashSanitizer.sanitize(error)
        assertEquals(RuntimeException::class.java, clean.javaClass)
        assertEquals("read failed", clean.message)
        assertEquals(IOException::class.java, clean.cause!!.javaClass)
        assertEquals("<path>", clean.cause!!.message)
        assertEquals("group <n>", clean.suppressed.single().message)
    }

    private class OnlyCause(cause: Throwable) : Exception(cause)

    @Test
    fun `没有 String 构造方法的包一层，消息里写原类型`() {
        val error = OnlyCause(IOException("$qqPath gone"))
        val clean = CrashSanitizer.sanitize(error)
        assertEquals(SanitizedException::class.java, clean.javaClass)
        assertTrue(clean.message!!, clean.message!!.startsWith(OnlyCause::class.java.name + ": "))
        assertFalse(clean.message!!, "/storage" in clean.message!!)
        assertEquals("<path> gone", clean.cause!!.message)
        assertArrayEquals(error.stackTrace, clean.stackTrace)
    }
}
