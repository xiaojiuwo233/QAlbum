package com.xjw.qalbum.service.indexer

import com.xjw.qalbum.data.db.entity.DirSnapshotEntity
import com.xjw.qalbum.service.indexer.transport.DirStat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 增量扫描：按目录修改时间剪掉没变的目录，刚写入过的目录多扫一次 */
class DirDifferTest {

    private val base = "/sd/chatpic/chatimg"

    private fun dir(name: String, mtime: Long) = DirStat("$base/$name", mtime)

    private fun snap(name: String, mtime: Long, scannedAt: Long) =
        DirSnapshotEntity(dirPath = "$base/$name", mtime = mtime, scannedAt = scannedAt)

    @Test
    fun `新目录(无快照)进入扫描`() {
        val r = DirDiffer.diff(listOf(dir("abc", 1000_000)), emptyList())
        assertEquals(listOf("$base/abc"), r.toScan.map { it.path })
        assertTrue(r.deletedDirPaths.isEmpty())
    }

    @Test
    fun `mtime 未变且远离上次扫描时刻的目录被剪掉`() {
        // 目录 mtime=1000s,上次扫描发生在很久之后 → 无竞态风险 → 剪枝
        val fs = listOf(dir("abc", 1_000_000))
        val snaps = listOf(snap("abc", 1_000_000, scannedAt = 9_000_000))
        val r = DirDiffer.diff(fs, snaps)
        assertTrue(r.toScan.isEmpty())
    }

    @Test
    fun `mtime 变化的目录进入扫描`() {
        val fs = listOf(dir("abc", 2_000_000))
        val snaps = listOf(snap("abc", 1_000_000, scannedAt = 9_000_000))
        val r = DirDiffer.diff(fs, snaps)
        assertEquals(1, r.toScan.size)
    }

    @Test
    fun `快照 mtime 落在上次扫描的 slack 窗口内时强制重扫(整秒粒度竞态保护)`() {
        // 上次扫描时目录刚被写入(mtime ≈ scannedAt):同一秒内的后续写入可能不改变 mtime
        val fs = listOf(dir("abc", 8_999_500))
        val snaps = listOf(snap("abc", 8_999_500, scannedAt = 9_000_000))
        val r = DirDiffer.diff(fs, snaps, slackMs = 2_000)
        assertEquals(1, r.toScan.size)
    }

    @Test
    fun `活跃目录重扫一次后静止即收敛(不再反复重扫)`() {
        // 第一次重扫后快照变为 scannedAt=新时间、mtime 不变 → mtime 远离 scannedAt → 剪枝
        val fs = listOf(dir("abc", 8_999_500))
        val snaps = listOf(snap("abc", 8_999_500, scannedAt = 20_000_000))
        val r = DirDiffer.diff(fs, snaps, slackMs = 2_000)
        assertTrue(r.toScan.isEmpty())
    }

    @Test
    fun `磁盘上消失的目录被报告为 deleted`() {
        val fs = listOf(dir("abc", 1_000_000))
        val snaps = listOf(
            snap("abc", 1_000_000, 9_000_000),
            snap("zzz", 1_000_000, 9_000_000),
        )
        val r = DirDiffer.diff(fs, snaps)
        assertEquals(listOf("$base/zzz"), r.deletedDirPaths)
    }

    @Test
    fun `首扫(空快照)所有目录进入扫描`() {
        val fs = listOf(dir("abc", 1), dir("def", 2), dir("ghi", 3))
        val r = DirDiffer.diff(fs, emptyList())
        assertEquals(3, r.toScan.size)
        assertTrue(r.deletedDirPaths.isEmpty())
    }
}
