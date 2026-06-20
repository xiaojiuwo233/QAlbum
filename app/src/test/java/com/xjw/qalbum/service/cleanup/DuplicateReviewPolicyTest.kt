package com.xjw.qalbum.service.cleanup

import com.xjw.qalbum.data.db.entity.ImageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicateReviewPolicyTest {

    // ---- pickKeeper：组内保留图选择 ----

    @Test
    fun keepsRawOverCompressedChatImage() {
        val raw = img(1, source = "chatraw", w = 800, h = 800)
        val compressed = img(2, source = "chatimg", w = 800, h = 800)
        assertEquals(1L, DuplicateReviewPolicy.pickKeeper(listOf(compressed, raw))?.id)
    }

    @Test
    fun keepsHigherResolution() {
        val small = img(1, source = "chatimg", w = 400, h = 400)
        val large = img(2, source = "chatimg", w = 1200, h = 1200)
        assertEquals(2L, DuplicateReviewPolicy.pickKeeper(listOf(small, large))?.id)
    }

    @Test
    fun keepsSharperWhenSameResolution() {
        val blurry = img(1, source = "chatimg", w = 800, h = 800, blur = 50f)
        val sharp = img(2, source = "chatimg", w = 800, h = 800, blur = 500f)
        assertEquals(2L, DuplicateReviewPolicy.pickKeeper(listOf(blurry, sharp))?.id)
    }

    @Test
    fun keepsSmallestIdOnTie() {
        val a = img(5, source = "chatimg", w = 800, h = 800, blur = 100f)
        val b = img(3, source = "chatimg", w = 800, h = 800, blur = 100f)
        assertEquals(3L, DuplicateReviewPolicy.pickKeeper(listOf(a, b))?.id)
    }

    // ---- decide：默认勾选集合 ----

    @Test
    fun twoExactCopiesDeleteOneKeepOne() {
        // 回归「2 张重复全勾」：精确副本应删 1 留 1
        val a = img(1, source = "chatimg", w = 800, h = 800, xxhash = "H")
        val b = img(2, source = "chatimg", w = 800, h = 800, xxhash = "H")
        val d = DuplicateReviewPolicy.decide(listOf(a, b))!!
        assertEquals(1L, d.keepId)
        assertEquals(setOf(2L), d.deleteIds)
    }

    @Test
    fun fourExactCopiesDeleteThreeKeepOne() {
        // 回归「4 张重复全不勾」：精确副本应删 3 留 1
        val imgs = (1..4).map { img(it.toLong(), source = "chatimg", w = 800, h = 800, xxhash = "H") }
        val d = DuplicateReviewPolicy.decide(imgs)!!
        assertEquals(1L, d.keepId)
        assertEquals(setOf(2L, 3L, 4L), d.deleteIds)
    }

    @Test
    fun deletesCompressedChatImagesKeepsRaw() {
        val raw = img(1, source = "chatraw", w = 1200, h = 1200, xxhash = "RAW")
        val c1 = img(2, source = "chatimg", w = 800, h = 800, xxhash = "A")
        val c2 = img(3, source = "chatimg", w = 600, h = 600, xxhash = "B")
        val d = DuplicateReviewPolicy.decide(listOf(raw, c1, c2))!!
        assertEquals(1L, d.keepId)
        assertEquals(setOf(2L, 3L), d.deleteIds)
    }

    @Test
    fun deletesLowerResolutionCopy() {
        val a = img(1, source = "chatraw", w = 1000, h = 1000, blur = 300f, xxhash = "A")
        val b = img(2, source = "chatraw", w = 500, h = 500, blur = 300f, xxhash = "B")
        val d = DuplicateReviewPolicy.decide(listOf(a, b))!!
        assertEquals(1L, d.keepId)
        assertEquals(setOf(2L), d.deleteIds)
    }

    @Test
    fun keepsAllWhenQualityComparableAndNotExactCopies() {
        // 视觉相似但都是高质量原图、非精确副本、同分辨率同清晰度 → 默认不勾，交给用户
        val a = img(1, source = "chatraw", w = 1000, h = 1000, blur = 300f, xxhash = "A")
        val b = img(2, source = "chatraw", w = 1000, h = 1000, blur = 300f, xxhash = "B")
        val d = DuplicateReviewPolicy.decide(listOf(a, b))!!
        assertEquals(1L, d.keepId)
        assertTrue("质量相当且非精确副本不应预勾选：${d.deleteIds}", d.deleteIds.isEmpty())
    }

    @Test
    fun ignoresNonActiveMembers() {
        val a = img(1, source = "chatimg", w = 800, h = 800, xxhash = "H")
        val b = img(2, source = "chatimg", w = 800, h = 800, xxhash = "H")
        val deleted = img(3, source = "chatimg", w = 800, h = 800, xxhash = "H", status = "deleted")
        val d = DuplicateReviewPolicy.decide(listOf(a, b, deleted))!!
        assertEquals(1L, d.keepId)
        assertEquals(setOf(2L), d.deleteIds) // 3 已删除，不参与
    }

    private fun img(
        id: Long,
        source: String = "chatimg",
        w: Int = 800,
        h: Int = 800,
        blur: Float? = 200f,
        size: Long = 100_000,
        xxhash: String? = null,
        status: String = "active",
    ) = ImageEntity(
        id = id,
        path = "/tmp/$id",
        filename = "$id",
        parentDir = "/tmp",
        sourceType = source,
        cacheKey = "$id",
        size = size,
        mtime = 1L,
        firstSeen = 1L,
        lastSeen = 1L,
        mime = "image/jpeg",
        width = w,
        height = h,
        blurScore = blur,
        xxhash = xxhash,
        duplicateGroupId = 1L,
        status = status,
    )
}
