package com.xjw.qalbum.ui.gallery

import com.xjw.qalbum.data.db.entity.ImageEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 校验图库「四项完成」角标的判定 [isFullyAnalyzed]：
 * hash 必做；未启用的阶段不计入要求（产品决策：已启用的项全完成才显示）。
 */
class GalleryAnalysisIconTest {

    private fun img(hash: Boolean, ocr: Boolean, ai: Boolean, emb: Boolean) = ImageEntity(
        path = "/p",
        filename = "f.jpg",
        parentDir = "/",
        sourceType = "chatimg",
        cacheKey = "k",
        size = 1L,
        mtime = 1L,
        firstSeen = 1L,
        lastSeen = 1L,
        mime = "image/jpeg",
        width = 100,
        height = 100,
        hashDone = hash,
        ocrDone = ocr,
        aiDone = ai,
        embeddingDone = emb,
    )

    @Test
    fun allEnabled_requiresAllFour() {
        val phases = EnabledPhases(ocr = true, aiTag = true, embedding = true)
        assertTrue(isFullyAnalyzed(img(true, true, true, true), phases))
        assertFalse(isFullyAnalyzed(img(true, true, true, false), phases))
        assertFalse(isFullyAnalyzed(img(true, false, true, true), phases))
        assertFalse(isFullyAnalyzed(img(false, true, true, true), phases))
    }

    @Test
    fun disabledPhases_notRequired() {
        // 仅 hash + OCR 启用：ai / 向量 未完成也算完成
        val phases = EnabledPhases(ocr = true, aiTag = false, embedding = false)
        assertTrue(isFullyAnalyzed(img(true, true, false, false), phases))
        assertFalse(isFullyAnalyzed(img(true, false, false, false), phases))
    }

    @Test
    fun hashAlwaysRequired() {
        val phases = EnabledPhases(ocr = false, aiTag = false, embedding = false)
        assertTrue(isFullyAnalyzed(img(true, false, false, false), phases))
        assertFalse(isFullyAnalyzed(img(false, false, false, false), phases))
    }
}
