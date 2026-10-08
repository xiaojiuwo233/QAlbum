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
    fun `同一张图的原图、预览图与缩略图得到同一个家族键`() {
        repeat(10_000) { index ->
            val paths = QqCachePaths.relativePaths(md5("qq-cache-synthetic-$index")).map { "$root/$it" }
            val keys = paths.map { QqCachePaths.familyKey(it) }
            assertNotNull(keys.first())
            assertEquals(1, keys.toSet().size)
            // 从任一版本都能推出完整的家族路径
            paths.forEach { path -> assertEquals(paths.toSet(), QqCachePaths.familyPaths(path).toSet()) }
        }
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
                listOf(plain, "${plain}_hd", "${plain}x", digits).forEach { name ->
                    val buckets = listOf(name.removeSuffix("_hd").takeLast(3), "abc", "")
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
        val match = Regex("^Cache_(-?[0-9a-f]{1,16})(_hd)?$").matchEntire(name) ?: return null
        val hd = match.groupValues[2].isNotEmpty()
        if (hd && folder != "chatthumb") return null
        val digits = match.groupValues[1]
        val value = digits.toLongOrNull(16) ?: return null
        if (java.lang.Long.toString(value, 16) != digits) return null
        if (name.removeSuffix("_hd").takeLast(3) != bucket) return null
        return value xor shift
    }

    /** 各目录相对 chatimg 的差值，从全零 MD5 的已知文件名反推 */
    private val deltas: Map<String, Long> = QqCachePaths.relativePaths("0".repeat(32)).filterNot { it.endsWith("_hd") }.associate { path ->
        val (folder, _, name) = path.split('/')
        folder to name.removePrefix("Cache_").toLong(16)
    }.let { names -> names.mapValues { (_, value) -> value xor names.getValue("chatimg") } }
}
