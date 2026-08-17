package com.xjw.qalbum.fileops

import com.xjw.qalbum.access.AccessException
import com.xjw.qalbum.access.FileAccessProvider
import com.xjw.qalbum.domain.model.AccessMode
import com.xjw.qalbum.domain.model.FileInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * 未绑定 AIDL（bound=null）时的降级路径：应走 provider 读并正确按 cap 截断 / 处理空文件。
 * 原生 fd 直读路径依赖特权进程与 ParcelFileDescriptor，无法在 JVM 单测覆盖，由编译期 + 真机保证。
 */
class PrivilegedImageReaderTest {

    @Test
    fun `bound 为 null 时降级 provider 读并按 cap 截断`() = runBlocking {
        val reader = PrivilegedImageReader(bound = null, provider = FakeProvider("hello world".toByteArray()))
        val bytes = reader.read("/x.jpg", cap = 5)
        assertEquals("hello", bytes?.let { String(it) })
    }

    @Test
    fun `cap 大于内容时读全量`() = runBlocking {
        val reader = PrivilegedImageReader(bound = null, provider = FakeProvider("abc".toByteArray()))
        val bytes = reader.read("/x.jpg", cap = 1024)
        assertEquals("abc", bytes?.let { String(it) })
    }

    @Test
    fun `空文件返回 null`() = runBlocking {
        val reader = PrivilegedImageReader(bound = null, provider = FakeProvider(ByteArray(0)))
        assertNull(reader.read("/x.jpg", cap = 1024))
    }

    @Test
    fun `单文件 IO 异常返回 null 不抛出`() = runBlocking {
        val reader = PrivilegedImageReader(bound = null, provider = ThrowingProvider())
        assertNull(reader.read("/x.jpg", cap = 1024))
    }

    @Test
    fun `基础设施失败 AccessException 向上抛出而非折叠成 null`() = runBlocking {
        // 若被折叠成 null，上层会把整轮剩余健康图片误标 is_corrupt（见 PrivilegedImageReader 失败语义）
        val reader = PrivilegedImageReader(bound = null, provider = InfraFailingProvider())
        try {
            reader.read("/x.jpg", cap = 1024)
            fail("应抛出 AccessException")
        } catch (expected: AccessException) {
        }
    }

    private open class FakeProvider(private val bytes: ByteArray) : FileAccessProvider {
        override fun getAccessMode(): AccessMode = AccessMode.SHIZUKU
        override suspend fun isAvailable(): Boolean = true
        override suspend fun exists(path: String): Boolean = true
        override fun listFiles(dir: String): Flow<FileInfo> = emptyFlow()
        override suspend fun readFile(path: String): InputStream = ByteArrayInputStream(bytes)
        override suspend fun readHeader(path: String, n: Int): ByteArray? = bytes.take(n).toByteArray()
        override suspend fun getFileInfo(path: String): FileInfo? = null
        override suspend fun deleteFile(path: String): Boolean = true
        override suspend fun moveFile(src: String, dst: String): Boolean = true
    }

    private class ThrowingProvider : FakeProvider(ByteArray(0)) {
        override suspend fun readFile(path: String): InputStream = throw java.io.IOException("boom")
    }

    private class InfraFailingProvider : FakeProvider(ByteArray(0)) {
        override suspend fun readFile(path: String): InputStream = throw AccessException("Shizuku 不可用")
    }
}
