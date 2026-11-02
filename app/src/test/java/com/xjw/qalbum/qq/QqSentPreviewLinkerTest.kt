package com.xjw.qalbum.qq

import com.xjw.qalbum.data.db.dao.QqPreviewCandidate
import org.junit.Assert.assertEquals
import org.junit.Test

/** 自己发的图的预览配原图：时间紧挨着、宽高比一致的优先，一次发几张时各配各的 */
class QqSentPreviewLinkerTest {

    private fun candidate(preview: Long, previewTime: Long, raw: String, rawTime: Long, shape: Pair<Int, Int> = 4 to 3, rawShape: Pair<Int, Int> = 4 to 3) =
        QqPreviewCandidate(
            previewId = preview, previewPath = "chatimg/$preview", previewWidth = shape.first * 180, previewHeight = shape.second * 180,
            previewTime = previewTime, rawPath = raw, rawWidth = rawShape.first * 400, rawHeight = rawShape.second * 400, rawTime = rawTime,
        )

    private fun List<QqPreviewCandidate>.pairs() = QqSentPreviewLinker.pair(this).associate { it.previewId to it.rawPath }

    @Test
    fun `连发几张时，每份预览配它之前刚写入的原图`() {
        // 连发两张：原图 1、预览 1、原图 2、预览 2 依次写入
        val pairs = listOf(
            candidate(1, 1_200, "raw1", 1_000),
            candidate(1, 1_200, "raw2", 1_500),
            candidate(2, 1_700, "raw1", 1_000),
            candidate(2, 1_700, "raw2", 1_500),
        ).pairs()
        assertEquals(mapOf(1L to "raw1", 2L to "raw2"), pairs)
    }

    @Test
    fun `宽高比一致比时间近几百毫秒更优先`() {
        val pairs = listOf(
            candidate(1, 2_000, "portrait", 1_900, rawShape = 3 to 4),
            candidate(1, 2_000, "landscape", 1_100),
        ).pairs()
        assertEquals(mapOf(1L to "landscape"), pairs)
    }

    @Test
    fun `优先配预览之前写入的原图`() {
        val pairs = listOf(
            candidate(1, 1_000, "before", 800),
            candidate(1, 1_000, "after", 1_100),
        ).pairs()
        assertEquals(mapOf(1L to "before"), pairs)
    }

    @Test
    fun `一对一配剩的预览也归到最近的原图`() {
        // 同一次发送只写了一张原图却有两份预览：一对一只配上一份，另一份也归到这张原图
        val raws = QqSentPreviewLinker.rawsOf(
            listOf(
                candidate(1, 1_200, "raw", 1_000),
                candidate(2, 1_500, "raw", 1_000),
            ),
        )
        assertEquals(mapOf(1L to "raw", 2L to "raw"), raws)
    }

    @Test
    fun `不知道尺寸也能配上`() {
        val pairs = listOf(candidate(1, 1_000, "raw", 900).copy(previewWidth = 0, previewHeight = 0)).pairs()
        assertEquals(mapOf(1L to "raw"), pairs)
    }
}
