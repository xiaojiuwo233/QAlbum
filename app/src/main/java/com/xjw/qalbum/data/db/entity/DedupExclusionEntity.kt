package com.xjw.qalbum.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * 重复组人工拆分形成的「禁止再聚」约束。
 *
 * 用户在重复组详情页判定「差异过大」拆分时，组内全部成员写入同一个 [cohortId]；
 * 重新聚类时 [com.xjw.qalbum.service.analysis.DuplicateClusterer] 对共享任一 cohort 的两图禁止连边，
 * 确保它们不再被识别为同一重复组。表只存手动拆过的图，规模很小。
 */
@Entity(
    tableName = "dedup_exclusions",
    primaryKeys = ["cohort_id", "image_id"],
    indices = [Index("image_id")],
)
data class DedupExclusionEntity(
    @ColumnInfo(name = "cohort_id") val cohortId: Long,
    @ColumnInfo(name = "image_id") val imageId: Long,
)
