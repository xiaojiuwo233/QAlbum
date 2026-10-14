package com.xjw.qalbum.data.db

/**
 * 按大小分组的 SQL 键，与图库的 sizeBucketId 一致：≥10MB 一组；1~9MB 每 1MB；100KB~1MB 每 100KB；10KB~100KB 每 10KB；<10KB 一组。
 * 图库、聊天来源、清理规则页共用。
 */
object SizeBucketSql {
    fun key(size: String): String =
        """CASE
            WHEN $size >= 10485760 THEN 100000
            WHEN $size >= 1048576 THEN 10000 + ($size / 1048576)
            WHEN $size >= 102400 THEN 1000 + ($size / 102400)
            WHEN $size >= 10240 THEN 100 + ($size / 10240)
            ELSE 0 END"""
}
