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
        // C039：视觉指纹（blockHash + featureGrid）已回填是「已分析」的必要条件，
        // 默认给非空值代表正常完成分析的图；缺失场景由专门测试覆盖。
        blockHash = ByteArray(32) { 1 },
        featureGrid = ByteArray(32) { 1 },
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

    @Test
    fun visualFingerprintRequired_unlessCorrupt() {
        // C039：blockHash / featureGrid 任一缺失都不算已分析（hashDone 为旧口径，可能没回填指纹）；
        // 损坏图被指纹回填跳过，故豁免。
        val phases = EnabledPhases(ocr = false, aiTag = false, embedding = false)
        assertFalse(isFullyAnalyzed(img(true, false, false, false).copy(blockHash = null), phases))
        assertFalse(isFullyAnalyzed(img(true, false, false, false).copy(featureGrid = null), phases))
        assertTrue(
            isFullyAnalyzed(
                img(true, false, false, false).copy(blockHash = null, featureGrid = null, isCorrupt = true),
                phases,
            ),
        )
    }

    @Test
    fun corruptImageExcusedFromOptionalPhases() {
        // 损坏图被 OCR/AI/向量阶段跳过，故 hash 完成即视为「已分析」（能拿到角标、计入进度）
        val phases = EnabledPhases(ocr = true, aiTag = true, embedding = true)
        val corrupt = img(hash = true, ocr = false, ai = false, emb = false).copy(isCorrupt = true)
        assertTrue(isFullyAnalyzed(corrupt, phases))
    }

    @Test
    fun tinyImageExcusedFromOcr() {
        // 过小图被 OCR 跳过：仅开 OCR 时，hash 完成即算已分析
        val phases = EnabledPhases(ocr = true, aiTag = false, embedding = false)
        val tiny = img(hash = true, ocr = false, ai = false, emb = false).copy(width = 32, height = 32)
        assertTrue(isFullyAnalyzed(tiny, phases))
    }
}
