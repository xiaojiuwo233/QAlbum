package com.xjw.qalbum.ui.imageloading

import coil3.size.Dimension
import coil3.size.Size
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.qq.QqCachePaths
import org.junit.Assert.assertEquals
import org.junit.Test

/** 网格格子改读的同一张图的小版本 */
class SmallerVersionsTest {
    private val root = "/storage/emulated/0/Android/data/com.tencent.mobileqq/Tencent/MobileQQ/chatpic"
    private val paths = QqCachePaths.relativePaths("0".repeat(32)).map { "$root/$it" }
    private val img = paths[0]
    private val raw = paths[1]
    private val thumb = paths[2]
    private val hd = paths[3]
    private val tile = Size(Dimension(360), Dimension(360))
    private val tiny = Size(Dimension(180), Dimension(180))

    private fun entity(path: String, sourceType: String) = ImageEntity(
        path = path, filename = path.substringAfterLast('/'), parentDir = path.substringBeforeLast('/'),
        sourceType = sourceType, cacheKey = "k", size = 1, mtime = 1, firstSeen = 1, lastSeen = 1,
        mime = "image/jpeg", width = 1, height = 1,
    )

    @Test
    fun `原图格子先读高清缩略图再读预览图`() {
        assertEquals(listOf(hd, "$hd.jpg", img, "$img.jpg"), smallerVersions(entity(raw, "chatraw"), tile))
        assertEquals(listOf(hd, "$hd.jpg", img, "$img.jpg"), smallerVersions(entity("$raw.jpg", "chatraw"), tile))
    }

    @Test
    fun `预览图格子只读高清缩略图`() {
        assertEquals(listOf(hd, "$hd.jpg"), smallerVersions(entity(img, "chatimg"), tile))
    }

    @Test
    fun `格子很小时普通缩略图也可以用`() {
        assertEquals(listOf(thumb, "$thumb.jpg", hd, "$hd.jpg", img, "$img.jpg"), smallerVersions(entity(raw, "chatraw"), tiny))
    }

    @Test
    fun `缩略图本身与非 QQ 命名的图片不找其他版本`() {
        assertEquals(emptyList<String>(), smallerVersions(entity(hd, "chatthumb"), tile))
        assertEquals(emptyList<String>(), smallerVersions(entity("$root/temp/x.jpg", "temp"), tile))
    }
}
