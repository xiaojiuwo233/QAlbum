package com.xjw.qalbum.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.data.db.entity.ImageQqOriginEntity
import com.xjw.qalbum.data.db.entity.QqOriginEntity
import com.xjw.qalbum.data.db.entity.QqSourceCacheEntity
import kotlinx.coroutines.flow.Flow

/**
 * 清理规则的查询（SQL 由 RuleSql 按规则拼出，作用在别名为 i 的 images 上）。
 * 按聊天的规则还查来源关联与读取时间，读取来源后这些表变化也要刷新。
 */
@Dao
interface CleanupRuleDao {

    @RawQuery(observedEntities = [ImageEntity::class, ImageQqOriginEntity::class, QqOriginEntity::class, QqSourceCacheEntity::class])
    fun paging(query: SupportSQLiteQuery): PagingSource<Int, ImageEntity>

    @RawQuery(observedEntities = [ImageEntity::class, ImageQqOriginEntity::class, QqOriginEntity::class, QqSourceCacheEntity::class])
    fun observeStats(query: SupportSQLiteQuery): Flow<RuleStats>

    @RawQuery
    suspend fun stats(query: SupportSQLiteQuery): RuleStats

    @RawQuery(observedEntities = [ImageEntity::class, ImageQqOriginEntity::class, QqOriginEntity::class, QqSourceCacheEntity::class])
    fun observeBuckets(query: SupportSQLiteQuery): Flow<List<ImageDao.BucketCountRow>>

    @RawQuery(observedEntities = [ImageEntity::class])
    fun observeDays(query: SupportSQLiteQuery): Flow<List<RuleDayRow>>

    @RawQuery
    suspend fun ids(query: SupportSQLiteQuery): List<Long>
}

/** 规则命中的张数与大小（整张图按全部版本合计，只处理单个文件的按文件自身大小） */
data class RuleStats(val count: Int, val bytes: Long)

/** 按天汇总（按时间清理页用，Kotlin 侧再合成月份） */
data class RuleDayRow(val bucketKey: Long, val count: Int, val bytes: Long)
