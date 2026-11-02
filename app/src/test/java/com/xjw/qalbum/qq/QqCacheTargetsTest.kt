package com.xjw.qalbum.qq

import com.xjw.qalbum.service.cleanup.QqCacheCleaner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QQ 缓存页：清空前的核对只放行 QQ 清理缓存登记的目录，数据库、配置、聊天图片与视频碰不到；
 * 特权进程返回的目录内容与状态能正确解析。
 */
class QqCacheTargetsTest {

    private val internal = "/data/user/0/com.tencent.mobileqq"
    private val external = "/storage/emulated/0/Android/data/com.tencent.mobileqq"

    @Test
    fun `三个系统缓存目录及其中的内容可以清空`() {
        listOf("$internal/cache", "$internal/code_cache", "$external/cache", "$internal/cache/compressor/a.jpg", "/data/data/com.tencent.mobileqq/cache")
            .forEach { assertTrue(it, QqCacheTargets.isTarget(it)) }
        // 第二个用户（应用双开等）
        assertTrue(QqCacheTargets.isTarget("/data/user/999/com.tencent.mobileqq/code_cache/x"))
        assertTrue(QqCacheTargets.isTarget("/storage/emulated/10/Android/data/com.tencent.mobileqq/cache"))
    }

    @Test
    fun `旧版清理登记的目录只在登记的那一边可以清空`() {
        assertTrue(QqCacheTargets.isTarget("$internal/files/vas_material_folder"))
        assertTrue(QqCacheTargets.isTarget("$external/qzone/video_cache/1.mp4"))
        // files/head 登记在内部，外部同名目录不算
        assertFalse(QqCacheTargets.isTarget("$external/files/head"))
    }

    @Test
    fun `数据库、配置、聊天图片视频与根目录永远不能清空`() {
        listOf(
            internal,
            "$internal/",
            "$internal/databases",
            "$internal/databases/nt_db/nt_qq_x/nt_msg.db",
            "$internal/shared_prefs/a.xml",
            "$internal/files",
            "$internal/files/mmkv",
            "$internal/files/uid",
            "$internal/cachex",
            external,
            "$external/Tencent/MobileQQ/chatpic/chatimg/abc/Cache_1",
            "$external/Tencent/MobileQQ/shortvideo/a.mp4",
            "$external/Tencent/QQfile_recv/a.mp4",
            "$external/files",
            "/data/user_de/0/com.tencent.mobileqq/cache",
            "/data/user/0/com.tencent.mobileqqi/cache",
            "/storage/emulated/0/DCIM/cache",
        ).forEach { assertFalse(it, QqCacheTargets.isTarget(it)) }
    }

    @Test
    fun `相对路径与带点的路径一律拒绝`() {
        assertNull(QqCacheTargets.clearablePath("$internal/cache/../databases"))
        assertNull(QqCacheTargets.clearablePath("$internal/./cache"))
        assertNull(QqCacheTargets.clearablePath("cache"))
    }

    @Test
    fun `登记的目录互不包含`() {
        val all = QqCacheTargets.all
        all.forEach { outer ->
            all.filter { it !== outer && it.area == outer.area }.forEach { inner ->
                assertFalse("${inner.relative} 在 ${outer.relative} 里", inner.relative.startsWith(outer.relative + "/"))
            }
        }
        assertEquals(all.size, all.map { it.area to it.relative }.toSet().size)
    }

    @Test
    fun `内部与外部目录跟着图片目录所在的用户走`() {
        assertEquals(
            QqStorageRoots(0, internal, external),
            QqStorageRoots.fromChatpic("$external/Tencent/MobileQQ/chatpic"),
        )
        val second = QqStorageRoots.fromChatpic("/storage/emulated/999/Android/data/com.tencent.mobileqq/Tencent/MobileQQ/chatpic/")
        assertEquals("/data/user/999/com.tencent.mobileqq", second.internal)
        assertEquals("/storage/emulated/999/Android/data/com.tencent.mobileqq/Tencent/MobileQQ/shortvideo", second.shortVideo)
        assertEquals("/storage/emulated/999/Android/data/com.tencent.mobileqq/Tencent/QQfile_recv", second.fileRecv)
        // 别的写法当主用户
        assertEquals(external, QqStorageRoots.fromChatpic("/sdcard/Android/data/com.tencent.mobileqq/Tencent/MobileQQ/chatpic").external)
        assertEquals(external, QqStorageRoots.fromChatpic("/mnt/somewhere/chatpic").external)
    }

    @Test
    fun `特权进程列出的子项名字里有分隔符也能解析`() {
        assertEquals(QqCacheCleaner.Child("compressor", true, 12, 3456), QqCacheCleaner.parseChild("D|12|3456|compressor"))
        assertEquals(QqCacheCleaner.Child("a|b.jpg", false, 1, 9), QqCacheCleaner.parseChild("F|1|9|a|b.jpg"))
        assertNull(QqCacheCleaner.parseChild("D|x|1|name"))
        assertNull(QqCacheCleaner.parseChild("garbage"))
    }

    @Test
    fun `特权进程的状态码对应目录状态`() {
        assertEquals(QqCacheCleaner.State.PRESENT, QqCacheCleaner.stateOf(1))
        assertEquals(QqCacheCleaner.State.MISSING, QqCacheCleaner.stateOf(0))
        assertEquals(QqCacheCleaner.State.DENIED, QqCacheCleaner.stateOf(-1))
        assertEquals(QqCacheCleaner.State.ERROR, QqCacheCleaner.stateOf(-2))
    }
}
