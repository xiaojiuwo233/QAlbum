package com.xjw.qalbum.service.indexer

import com.xjw.qalbum.data.db.dao.ImageDao.ScanRow
import com.xjw.qalbum.domain.model.FileInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileClassifierTest {

    private val dir = "/sd/chatpic/chatimg/abc"

    private fun file(name: String, size: Long = 100, mtime: Long = 1_000_000) = FileInfo(
        path = "$dir/$name",
        name = name,
        size = size,
        lastModified = mtime,
        isDirectory = false,
    )

    private fun row(
        name: String,
        id: Long,
        size: Long = 100,
        mtime: Long = 1_000_000,
        status: String = "active",
        mime: String = "image/jpeg",
        isAnimated: Boolean = false,
    ) = ScanRow(
        id = id,
        path = "$dir/$name",
        size = size,
        mtime = mtime,
        status = status,
        mime = mime,
        isAnimated = isAnimated,
    )

    @Test
    fun `库里没有的文件归为新增`() {
        val r = FileClassifier.classify(listOf(file("Cache_a")), emptyList())
        assertEquals(1, r.newFiles.size)
        assertEquals(0, r.changedFiles.size)
    }

    @Test
    fun `size 或 mtime 变化归为 changed`() {
        val rows = listOf(row("Cache_a", 1), row("Cache_b", 2))
        val fs = listOf(
            file("Cache_a", size = 999),          // size 变
            file("Cache_b", mtime = 2_000_000),   // mtime 变
        )
        val r = FileClassifier.classify(fs, rows)
        assertEquals(2, r.changedFiles.size)
        assertTrue(r.newFiles.isEmpty())
    }

    @Test
    fun `完全未变化的文件只计数,不产生任何写操作`() {
        val r = FileClassifier.classify(listOf(file("Cache_a")), listOf(row("Cache_a", 1)))
        assertEquals(1, r.unchangedCount)
        assertTrue(r.newFiles.isEmpty())
        assertTrue(r.changedFiles.isEmpty())
        assertTrue(r.reviveIds.isEmpty())
        assertTrue(r.missingIds.isEmpty())
        assertTrue(r.unchangedPairs.isEmpty()) // 默认不收集
    }

    @Test
    fun `missing 行的文件以相同内容回归时仅恢复 active`() {
        val r = FileClassifier.classify(
            listOf(file("Cache_a")),
            listOf(row("Cache_a", 7, status = "missing")),
        )
        assertEquals(listOf(7L), r.reviveIds)
        assertTrue(r.changedFiles.isEmpty())
    }

    @Test
    fun `active 行的文件消失时标记 missing`() {
        val rows = listOf(row("Cache_a", 1), row("Cache_gone", 2))
        val r = FileClassifier.classify(listOf(file("Cache_a")), rows)
        assertEquals(listOf(2L), r.missingIds)
    }

    @Test
    fun `非 active 行(deleted-quarantined-thumbnail_trash)消失时不动`() {
        val rows = listOf(
            row("Cache_d", 1, status = "deleted"),
            row("Cache_q", 2, status = "quarantined"),
            row("Cache_t", 3, status = "thumbnail_trash"),
            row("Cache_m", 4, status = "missing"),
        )
        val r = FileClassifier.classify(emptyList(), rows)
        assertTrue(r.missingIds.isEmpty())
    }

    @Test
    fun `深度校验时收集未变化配对`() {
        val r = FileClassifier.classify(
            listOf(file("Cache_a")),
            listOf(row("Cache_a", 1)),
            collectUnchanged = true,
        )
        assertEquals(1, r.unchangedPairs.size)
        assertEquals(1L, r.unchangedPairs[0].second.id)
    }

    @Test
    fun `翻页竞态产生的重复文件项被去重`() {
        val r = FileClassifier.classify(
            listOf(file("Cache_a"), file("Cache_a")),
            emptyList(),
        )
        assertEquals(1, r.newFiles.size)
    }
}
