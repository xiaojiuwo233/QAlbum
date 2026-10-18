package com.xjw.qalbum.data.db

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.xjw.qalbum.domain.model.SourceSort
import com.xjw.qalbum.domain.model.TimelineSection

/** 视频页的查询：[source] 为 null 时不按来源筛选；日期按文件修改时间，大小按 [sizeKey] 分组 */
object VideoSql {

    private fun query(sql: String, args: List<Any>): SupportSQLiteQuery = SimpleSQLiteQuery(sql, args.toTypedArray())

    private fun where(source: String?, extra: String? = null): String {
        val parts = listOfNotNull(source?.let { "source = ?" }, extra)
        return if (parts.isEmpty()) "" else " WHERE " + parts.joinToString(" AND ")
    }

    fun page(sort: SourceSort, source: String?): SupportSQLiteQuery {
        val order = when (sort) {
            SourceSort.DATE_DESC -> "mtime DESC, id DESC"
            SourceSort.DATE_ASC -> "mtime, id"
            SourceSort.SIZE_DESC -> "size DESC, id DESC"
            SourceSort.SIZE_ASC -> "size, id"
        }
        return query("SELECT * FROM videos${where(source)} ORDER BY $order", listOfNotNull(source))
    }

    fun buckets(byDate: Boolean, source: String?): SupportSQLiteQuery = query(
        "SELECT ${if (byDate) "date_bucket" else sizeKey("size")} AS bucketKey, COUNT(*) AS count FROM videos${where(source)} GROUP BY bucketKey",
        listOfNotNull(source),
    )

    /** 全部（[section] 为 null）或某一天 / 某个大小区间的视频 id */
    fun ids(source: String?, section: TimelineSection? = null): SupportSQLiteQuery {
        val (extra, args) = when (section) {
            null -> null to emptyList()
            is TimelineSection.Day -> "mtime >= ? AND mtime < ?" to listOf(section.from, section.until)
            is TimelineSection.Size -> "size >= ? AND size < ?" to listOf(section.low, section.high)
        }
        return query("SELECT id FROM videos${where(source, extra)}", listOfNotNull(source) + args)
    }

    // ---- 大小分组：视频大多在 10 MB 以上，分得比图片粗 ----

    private const val MB = 1024L * 1024L

    /** 每组的下限（MB），从大到小 */
    private val LOWER_MB = longArrayOf(1024, 500, 200, 100, 50, 20, 10, 5, 1, 0)

    fun sizeKey(column: String): String = buildString {
        append("CASE")
        LOWER_MB.filter { it > 0 }.forEach { low -> append(" WHEN $column >= ${low * MB} THEN $low") }
        append(" ELSE 0 END")
    }

    /** 与 [sizeKey] 同一套分组：所在组的下限（MB） */
    fun sizeBucket(size: Long): Long = LOWER_MB.first { size >= it * MB }

    /** 组的区间 [low, high) */
    fun sizeSection(bucket: Long): TimelineSection.Size {
        val index = LOWER_MB.indexOf(bucket).coerceAtLeast(0)
        val high = if (index == 0) Long.MAX_VALUE else LOWER_MB[index - 1] * MB
        return TimelineSection.Size(bucket * MB, high)
    }

    fun sizeLabel(bucket: Long): String {
        val index = LOWER_MB.indexOf(bucket).coerceAtLeast(0)
        return when {
            index == 0 -> "1 GB 以上"
            bucket == 0L -> "1 MB 以下"
            LOWER_MB[index - 1] == 1024L -> "$bucket MB – 1 GB"
            else -> "$bucket–${LOWER_MB[index - 1]} MB"
        }
    }
}
