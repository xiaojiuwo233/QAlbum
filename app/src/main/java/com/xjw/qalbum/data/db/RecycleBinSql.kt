package com.xjw.qalbum.data.db

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.xjw.qalbum.domain.model.SourceSort
import com.xjw.qalbum.domain.model.TimelineSection

/**
 * 回收站页的查询。移入回收站的图按组（一次删除的同一张图的各版本）一行，[reasons] 为 null 时不按删除原因筛选；
 * 压缩前的原图每张一行。日期按移入回收站（压缩）的时间，大小按整组合计（原图备份的大小）。
 */
object RecycleBinSql {

    /** 每组一行，代表是组里最大的文件：只有一个 max() 聚合时，SQLite 的裸列取自最大的那一行 */
    private const val UNITS = """SELECT id AS rep, MAX(size) AS top, COUNT(*) AS files, SUM(size) AS bytes
        FROM quarantine WHERE state = 'ready' GROUP BY COALESCE(group_id, 'legacy:' || id)"""
    private const val TRASH = "FROM ($UNITS) u CROSS JOIN quarantine q ON q.id = u.rep CROSS JOIN images i ON i.id = q.image_id"
    private const val BACKUPS = """FROM original_backups b CROSS JOIN images i ON i.id = b.image_id
        WHERE b.state = 'ready' AND b.backup_path IS NOT NULL"""

    private const val TRASH_COLUMNS = """i.*, q.id AS q_id, q.original_path AS q_origin, q.group_id AS q_group, q.reason AS q_reason,
        q.quarantined_at AS q_at, q.expires_at AS q_expires, q.quarantine_path AS q_file, q.thumbnail_path AS q_thumb"""

    private fun query(sql: String, args: List<Any>): SupportSQLiteQuery = SimpleSQLiteQuery(sql, args.toTypedArray())

    /** 回收站条件：删除原因 + 额外条件 */
    private fun trashWhere(reasons: List<String>?, extra: String? = null): String {
        val parts = listOfNotNull(reasons?.let { "q.reason IN (${it.joinToString(",") { "?" }})" }, extra)
        return if (parts.isEmpty()) "" else " WHERE " + parts.joinToString(" AND ")
    }

    private fun day(column: String) = "CAST(strftime('%Y', $column / 1000, 'unixepoch', 'localtime') AS INTEGER) * 1000 + " +
        "CAST(strftime('%j', $column / 1000, 'unixepoch', 'localtime') AS INTEGER)"

    private fun order(sort: SourceSort, time: String, bytes: String, id: String): String = when (sort) {
        SourceSort.DATE_DESC -> "$time DESC, $id DESC"
        SourceSort.DATE_ASC -> "$time, $id"
        SourceSort.SIZE_DESC -> "$bytes DESC, $id DESC"
        SourceSort.SIZE_ASC -> "$bytes, $id"
    }

    // ---- 移入回收站的图 ----

    fun trashPage(sort: SourceSort, reasons: List<String>?): SupportSQLiteQuery = query(
        "SELECT $TRASH_COLUMNS, u.files AS q_files, u.bytes AS q_bytes $TRASH${trashWhere(reasons)} " +
            "ORDER BY ${order(sort, "q.quarantined_at", "u.bytes", "q.id")}",
        reasons.orEmpty(),
    )

    /** 一组里的各个版本（看图时切换），大的在前 */
    fun trashMembers(groupId: String): SupportSQLiteQuery = query(
        "SELECT $TRASH_COLUMNS, 1 AS q_files, q.size AS q_bytes FROM quarantine q CROSS JOIN images i ON i.id = q.image_id " +
            "WHERE q.group_id = ? AND q.state = 'ready' ORDER BY q.size DESC, q.id",
        listOf(groupId),
    )

    fun trashReasons(): SupportSQLiteQuery =
        query("SELECT q.reason AS reason, COUNT(*) AS count, COALESCE(SUM(u.bytes), 0) AS bytes $TRASH GROUP BY q.reason", emptyList())

    fun trashBuckets(byDate: Boolean, reasons: List<String>?): SupportSQLiteQuery = query(
        "SELECT ${if (byDate) day("q.quarantined_at") else SizeBucketSql.key("u.bytes")} AS bucketKey, COUNT(*) AS count " +
            "$TRASH${trashWhere(reasons)} GROUP BY bucketKey",
        reasons.orEmpty(),
    )

    /** 代表图的 id：全部（[section] 为 null）或某一天 / 某个大小区间 */
    fun trashIds(reasons: List<String>?, section: TimelineSection? = null): SupportSQLiteQuery {
        val (extra, args) = when (section) {
            null -> null to emptyList()
            is TimelineSection.Day -> "q.quarantined_at >= ? AND q.quarantined_at < ?" to listOf(section.from, section.until)
            is TimelineSection.Size -> "u.bytes >= ? AND u.bytes < ?" to listOf(section.low, section.high)
        }
        return query("SELECT i.id $TRASH${trashWhere(reasons, extra)}", reasons.orEmpty() + args)
    }

    // ---- 压缩前的原图 ----

    fun backupPage(sort: SourceSort): SupportSQLiteQuery = query(
        "SELECT i.*, b.created_at AS b_created, b.expires_at AS b_expires, b.original_size AS b_size $BACKUPS " +
            "ORDER BY ${order(sort, "b.created_at", "b.original_size", "b.id")}",
        emptyList(),
    )

    fun backupBuckets(byDate: Boolean): SupportSQLiteQuery = query(
        "SELECT ${if (byDate) day("b.created_at") else SizeBucketSql.key("b.original_size")} AS bucketKey, COUNT(*) AS count " +
            "$BACKUPS GROUP BY bucketKey",
        emptyList(),
    )

    fun backupIds(section: TimelineSection? = null): SupportSQLiteQuery = when (section) {
        null -> query("SELECT i.id $BACKUPS", emptyList())
        is TimelineSection.Day -> query("SELECT i.id $BACKUPS AND b.created_at >= ? AND b.created_at < ?", listOf(section.from, section.until))
        is TimelineSection.Size -> query("SELECT i.id $BACKUPS AND b.original_size >= ? AND b.original_size < ?", listOf(section.low, section.high))
    }
}
