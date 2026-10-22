package com.xjw.qalbum.qq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.security.MessageDigest
import kotlin.random.Random

class QqCachePathsTest {
    private val root = "/storage/emulated/0/Android/data/com.tencent.mobileqq/Tencent/MobileQQ/chatpic"

    private fun md5(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    @Test
    fun `全零 MD5 的各目录文件名与调查工具一致`() {
        assertEquals(
            listOf(
                "chatimg/a2f/Cache_50539d06b4b71a2f",
                "chatraw/d32/Cache_-1bea11a7c0c83d32",
                "chatthumb/e4f/Cache_48a17898467bee4f",
                "chatthumb/e4f/Cache_48a17898467bee4f_hd",
            ),
            QqCachePaths.relativePaths("0".repeat(32)),
        )
    }

    @Test
    fun `由 MD5 算出的家族键与它各个缓存文件的家族键相同`() {
        repeat(200) { index ->
            val md5 = md5("family-$index")
            val family = QqCachePaths.md5Family(md5)
            assertNotNull(family)
            QqCachePaths.relativePaths(md5).forEach { assertEquals(it, family, QqCachePaths.familyKey("$root/$it")) }
            assertEquals(family, QqCachePaths.md5Family(md5.uppercase()))
            // 由家族键推回的 chatimg 路径就是这张图的预览图
            assertEquals("$root/" + QqCachePaths.relativePaths(md5).first(), QqCachePaths.familyPath(root, family!!))
        }
        assertNull(QqCachePaths.md5Family("not-a-md5"))
        assertNull(QqCachePaths.md5Family("g".repeat(32)))
    }

    @Test
    fun `一张图的各 MD5 去重后取家族键，推不出家族键的本地路径另记`() {
        val first = md5("first")
        val second = md5("second")
        val odd = "$root/chatimg/odd/not_a_cache_name"
        val standard = "$root/" + QqCachePaths.relativePaths(first)[1]
        val picture = QqPictureElement("1", linkedSetOf(first, second, first.uppercase()), linkedSetOf(standard, odd, "/sdcard/DCIM/a.jpg", "$root/../x"))
        val families = QqCachePaths.families(picture)
        assertEquals(listOf(QqCachePaths.md5Family(first), QqCachePaths.md5Family(second)), families)
        // 规范路径由家族键推出，目录外的、带 .. 的不收
        assertEquals(listOf(odd), QqCachePaths.extraPaths(root, picture, families))
    }

    @Test
    fun `同一张图的原图、预览图与缩略图得到同一个家族键`() {
        repeat(10_000) { index ->
            val paths = QqCachePaths.relativePaths(md5("qq-cache-synthetic-$index")).flatMap { listOf("$root/$it", "$root/$it.jpg") }
            val keys = paths.map { QqCachePaths.familyKey(it) }
            assertNotNull(keys.first())
            assertEquals(1, keys.toSet().size)
            // 从任一版本都能推出完整的家族路径
            paths.forEach { path -> assertEquals(paths.toSet(), QqCachePaths.familyPaths(path).toSet()) }
        }
    }

    @Test
    fun `chatraw 同名 jpg 使用扩展名前的分桶和家族键`() {
        val path = "$root/chatraw/1f9/Cache_3224b49bbd8391f9.jpg"
        assertNotNull(QqCachePaths.familyKey(path))
        assertEquals(QqCachePaths.familyKey(path.removeSuffix(".jpg")), QqCachePaths.familyKey(path))
        assertEquals(8, QqCachePaths.familyPaths(path).size)
        assertEquals(true, path in QqCachePaths.familyPaths(path))
    }

    @Test
    fun `按原文件名带后缀另存的版本（png、gif 等）也是同一家族`() {
        val paths = QqCachePaths.relativePaths(md5("forwarded")).map { "$root/$it" }
        val key = QqCachePaths.familyKey(paths.first())
        assertNotNull(key)
        listOf("png", "gif", "jpeg", "webp", "PNG", "Heic").forEach { ext ->
            paths.forEach { path -> assertEquals("$path.$ext", key, QqCachePaths.familyKey("$path.$ext")) }
        }
        // 不是图片的后缀、或者后缀前不是缓存名，不算
        assertNull(QqCachePaths.familyKey(paths.first() + ".txt"))
        assertNull(QqCachePaths.familyKey("$root/chatraw/png/Cache_abc.png"))
        val raw = paths.first { "/chatraw/" in it }
        assertEquals(raw, QqCachePaths.aliasStem("$raw.gif"))
        assertNull(QqCachePaths.aliasStem(raw))
        assertEquals("Cache_1_hd", QqCachePaths.stripAlias("Cache_1_hd.webp"))
        // 清理时连同这些别名一起找，读格子小图时只带 .jpg
        assertEquals(4 * (1 + QqCachePaths.ALIAS_EXTENSIONS.size), QqCachePaths.familyPaths(raw, QqCachePaths.ALIAS_EXTENSIONS).size)
        assertEquals(true, "$raw.png" in QqCachePaths.familyPaths(raw, QqCachePaths.ALIAS_EXTENSIONS))
        assertEquals(8, QqCachePaths.familyPaths("$raw.png").size)
    }

    @Test
    fun `不同图片的家族键不同`() {
        val keys = (0 until 10_000).map { QqCachePaths.familyKey("$root/" + QqCachePaths.relativePaths(md5("other-$it")).first()) }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `Temp 转码缩略图归入同一家族`() {
        val thumb = QqCachePaths.relativePaths(md5("converted")).first { it.startsWith("chatthumb/") && !it.endsWith("_hd") }
        val name = thumb.substringAfterLast('/')
        val converted = "$root/Temp/$name.thumb_198.tmp.from_orig_avif_aio.ready"
        assertEquals(QqCachePaths.familyKey("$root/$thumb"), QqCachePaths.familyKey(converted))
    }

    @Test
    fun `非 QQ 规范命名不参与合并`() {
        listOf(
            "$root/chatimg/abc/Cache_",
            "$root/chatimg/x-0/Cache_-0",
            "$root/chatimg/001/Cache_01",
            "$root/chatimg/fff/Cache_ffffffffffffffff",
            "$root/chatimg/_dp/Cache_1_dp",
            "$root/chatimg/1/Cache_1_hd",
            "$root/chatimg/zzz/Cache_50539d06b4b71a2f",
            "$root/chatraw/jpg/Cache_3224b49bbd8391f9.jpg",
            "$root/chatraw/1f9/Cache_3224b49bbd8391f9.jpg.jpg",
            "$root/chatraw/1f9/Cache_3224b49bbd8391f9_hd.jpg",
            "$root/chatraw/1f9/Cache_3224b49bbd8391f9.txt",
            "$root/temp/x1.jpg",
            "/sdcard/DCIM/IMG_0001.jpg",
        ).forEach { assertNull(it, QqCachePaths.familyKey(it)) }
    }

    @Test
    fun `逐字解析与按正则的规则完全一致`() {
        val random = Random(18)
        val folders = listOf("chatimg", "chatraw", "chatthumb", "chatpic", "Temp", "temp")
        val alphabet = "0123456789abcdefABX-_"
        val names = buildList {
            listOf("0", "-0", "00", "1", "-1", "7fffffffffffffff", "-7fffffffffffffff", "8000000000000000", "-8000000000000000",
                "-8000000000000001", "ffffffffffffffff", "10000000000000000", "0abc", "-0abc", "ABC", "abc").forEach { add(it) }
            repeat(20_000) {
                when (random.nextInt(3)) {
                    0 -> add(java.lang.Long.toString(random.nextLong(), 16))
                    1 -> add((1..random.nextInt(0, 19)).map { alphabet[random.nextInt(alphabet.length)] }.joinToString(""))
                    else -> add(java.lang.Long.toString(random.nextLong(0, 4096), 16))
                }
            }
        }
        var keyed = 0
        names.forEach { digits ->
            val plain = "Cache_$digits"
            folders.forEach { folder ->
                listOf(plain, "${plain}_hd", "$plain.jpg", "${plain}_hd.jpg", "${plain}x", digits).forEach { name ->
                    val buckets = listOf(name.removeSuffix(".jpg").removeSuffix("_hd").takeLast(3), "abc", "")
                    buckets.forEach { bucket ->
                        val path = if (folder.equals("temp", ignoreCase = true)) "$root/$folder/$name.thumb_1.tmp.from_orig_avif_aio.ready"
                            else "$root/$folder/$bucket/$name"
                        val expected = reference(path)
                        if (expected != null) keyed++
                        assertEquals(path, expected, QqCachePaths.familyKey(path))
                    }
                }
            }
        }
        assertEquals(true, keyed > 10_000)
        listOf("", "/", "a", "/a", "a/b", "Temp/x", "chatimg/a2f/Cache_50539d06b4b71a2f", "a//b", "//", "chatthumb/e4f/Cache_48a17898467bee4f_hd")
            .forEach { assertEquals(it, reference(it), QqCachePaths.familyKey(it)) }
    }

    /** 改写前基于切分与正则的实现，作为对照 */
    private fun reference(path: String): Long? {
        val parts = path.split('/')
        if (parts.size < 2) return null
        val (folder, bucket, name) = when {
            parts[parts.size - 2] == "Temp" || parts[parts.size - 2] == "temp" ->
                QqCachePaths.convertedThumbnailSource(parts.last())?.split('/')?.takeIf { it.size == 3 } ?: return null
            parts.size >= 3 -> parts.takeLast(3)
            else -> return null
        }
        val shift = deltas[folder] ?: return null
        val match = Regex("^Cache_(-?[0-9a-f]{1,16})(_hd)?(\\.jpg)?$").matchEntire(name) ?: return null
        val hd = match.groupValues[2].isNotEmpty()
        if (hd && folder != "chatthumb") return null
        val digits = match.groupValues[1]
        val value = digits.toLongOrNull(16) ?: return null
        if (java.lang.Long.toString(value, 16) != digits) return null
        if (name.removeSuffix(".jpg").removeSuffix("_hd").takeLast(3) != bucket) return null
        return value xor shift
    }

    /** 各目录相对 chatimg 的差值，从全零 MD5 的已知文件名反推 */
    private val deltas: Map<String, Long> = QqCachePaths.relativePaths("0".repeat(32)).filterNot { it.endsWith("_hd") }.associate { path ->
        val (folder, _, name) = path.split('/')
        folder to name.removePrefix("Cache_").toLong(16)
    }.let { names -> names.mapValues { (_, value) -> value xor names.getValue("chatimg") } }
}
