package com.xjw.qalbum.ui.imageloading

import coil3.size.Dimension
import coil3.size.Size
import com.xjw.qalbum.CHATPIC
import com.xjw.qalbum.familyPaths
import com.xjw.qalbum.imageRow
import org.junit.Assert.assertEquals
import org.junit.Test

/** 网格格子改读的同一张图的小版本 */
class SmallerVersionsTest {
    private val paths = familyPaths("0".repeat(32))
    private val img = paths[0]
    private val raw = paths[1]
    private val thumb = paths[2]
    private val hd = paths[3]
    private val tile = Size(Dimension(360), Dimension(360))
    private val tiny = Size(Dimension(180), Dimension(180))

    private fun entity(path: String, sourceType: String) = imageRow(path, sourceType)

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
        assertEquals(emptyList<String>(), smallerVersions(entity("$CHATPIC/temp/x.jpg", "temp"), tile))
    }
}
