package com.xjw.qalbum.service.cleanup.rules

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.xjw.qalbum.data.db.SizeBucketSql
import com.xjw.qalbum.data.db.dao.QqScopeOrder
import com.xjw.qalbum.domain.model.SourceSort

/** 把规则条件拼成具体查询；[part] 来自 [CleanupRule.where] */
object RuleSql {

    private fun query(sql: String, args: List<Any>): SupportSQLiteQuery = SimpleSQLiteQuery(sql, args.toTypedArray())

    /** 大小口径：整张图用家族合计，只处理单个文件的用文件自身大小 */
    private fun bytes(scope: RuleScope) = if (scope == RuleScope.PICTURE) "i.variant_bytes" else "i.size"

    /** 预览网格；排序与聊天来源页一致（日期 / 大小 × 正序 / 倒序） */
    fun page(part: SqlPart, scope: RuleScope, sort: SourceSort = SourceSort.DATE_DESC): SupportSQLiteQuery =
        query(
            "SELECT i.* FROM images i WHERE ${part.sql} ORDER BY ${QqScopeOrder.valueOf(sort.name).sql(scope == RuleScope.PICTURE)}",
            part.args,
        )

    fun stats(part: SqlPart, scope: RuleScope): SupportSQLiteQuery =
        query("SELECT COUNT(*) AS count, COALESCE(SUM(${bytes(scope)}), 0) AS bytes FROM images i WHERE ${part.sql}", part.args)

    fun buckets(part: SqlPart): SupportSQLiteQuery =
        query("SELECT i.date_bucket AS bucketKey, COUNT(*) AS count FROM images i WHERE ${part.sql} GROUP BY i.date_bucket", part.args)

    /** 按大小分组的数量（按大小排序时的分组标题与快速滚动条） */
    fun sizeBuckets(part: SqlPart, scope: RuleScope): SupportSQLiteQuery =
        query(
            "SELECT ${SizeBucketSql.key(bytes(scope))} AS bucketKey, COUNT(*) AS count FROM images i WHERE ${part.sql} GROUP BY bucketKey",
            part.args,
        )

    /** 按天汇总张数与大小（按时间清理页） */
    fun days(part: SqlPart, scope: RuleScope): SupportSQLiteQuery =
        query(
            "SELECT i.date_bucket AS bucketKey, COUNT(*) AS count, COALESCE(SUM(${bytes(scope)}), 0) AS bytes " +
                "FROM images i WHERE ${part.sql} GROUP BY i.date_bucket",
            part.args,
        )

    /** 点日期标题全选：这一天的全部命中项 */
    fun idsBetween(part: SqlPart, from: Long, until: Long): SupportSQLiteQuery =
        query("SELECT i.id FROM images i WHERE ${part.sql} AND i.mtime >= ? AND i.mtime < ?", part.args + from + until)

    /** 点大小分组标题全选：[low, high) 之间的全部命中项 */
    fun idsBySize(part: SqlPart, scope: RuleScope, low: Long, high: Long): SupportSQLiteQuery =
        query("SELECT i.id FROM images i WHERE ${part.sql} AND ${bytes(scope)} >= ? AND ${bytes(scope)} < ?", part.args + low + high)

    /** 右上角全选：全部命中项 */
    fun ids(part: SqlPart): SupportSQLiteQuery =
        query("SELECT i.id FROM images i WHERE ${part.sql} ORDER BY i.mtime DESC, i.id DESC", part.args)

    /** 按 id 分页取出（keyset） */
    fun idsAfter(part: SqlPart, afterId: Long, limit: Int): SupportSQLiteQuery =
        query("SELECT i.id FROM images i WHERE ${part.sql} AND i.id > ? ORDER BY i.id LIMIT ?", part.args + afterId + limit)
}
