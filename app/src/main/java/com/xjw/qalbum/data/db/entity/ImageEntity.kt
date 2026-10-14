package com.xjw.qalbum.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant
import java.time.ZoneId

/**
 * 图片表 —— QAlbum 的核心实体。只记录扫描能直接拿到的文件信息，不做任何内容分析。
 *
 * 设计要点：
 * - `cache_key`：从 `Cache_{hash}` 文件名中提取的 hash 部分。
 * - `variant_group`：图片家族键——同一张 QQ 图片的 chatimg / chatraw / chatthumb 由文件名 CRC 推出同一个键
 *   （见 QqCachePaths.familyKey）；`variant_hidden` / `variant_count` / `variant_bytes` 由触发器维护：
 *   图库合并显示时只露出家族中最大的那张，它带上家族的版本数与总大小。
 * - `variant_bytes`：合并显示时的大小（代表图为整组合计，其余为自身大小），合并显示下按大小排序 / 分组用它。
 * - `width` / `height`：扫描时由特权进程解析文件头得到（不解码），读不到为 0。
 * - `status = thumbnail_trash`：历史状态名；现在表示 QQ 缩略图（chatthumb），不进图库时间线，可在缩略图规则与来源页清理。
 * - `is_protected`：归档。总是按整个家族设置，任何清理都不会删除。归档的图片不在图库显示时
 *   `status = archived`（只按 status 过滤的图库、来源、清理规则、缩略图页自然看不到它们），
 *   设置为照常显示时保持原 status（见 ArchiveManager）。
 * - `optimized`：原图压缩状态（0 未处理 / 1 已压缩 / 2 无需压缩）；文件被 QQ 重写时由扫描清零。
 */
@Entity(
    tableName = "images",
    indices = [
        // path 是扫描入库 diff 的高频查询列：updateFromScanByPath 按等值命中，
        // listScanRowsInPathRange / markMissingInPathRange 按 "dir/" 前缀做范围扫描，
        // 不加索引会退化成全表扫描。unique：insertAll 的 IGNORE 兜底依赖此唯一约束，
        // 否则极端并发下的双插会静默写入重复行且无任何约束拦截。
        Index(value = ["path"], unique = true),
        Index("variant_group"),
        // 合并显示下按大小排序（同 size 索引的用法：按序走索引、逐行核对条件）
        Index("variant_bytes"),
        Index("status"),
        Index("mtime"),
        // size 是「大到小」排序与清理规则聚合的高频排序列；25w 图无索引会退化成全表 filesort。
        Index("size"),
        // 分桶计数覆盖索引：observeTimelineDateBucketCounts / 归档页分桶
        // 只扫索引条目做 GROUP BY date_bucket，免 200w 行全表逐行 strftime。
        Index("status", "source_type", "date_bucket", "is_protected", "variant_hidden"),
        // 时间线定位覆盖索引：timelineRank* / timelineIds*（keyset 定位与窗口）只扫索引条目计数，
        // 不做 200w 次行回表；按时间的清理规则同样走它。
        Index("status", "source_type", "mtime", "variant_hidden"),
    ]
)
data class ImageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    // --- 文件基础信息 ---
    @ColumnInfo(name = "path") val path: String,
    @ColumnInfo(name = "filename") val filename: String,
    @ColumnInfo(name = "parent_dir") val parentDir: String,
    @ColumnInfo(name = "source_type") val sourceType: String,
    @ColumnInfo(name = "cache_key") val cacheKey: String,
    @ColumnInfo(name = "variant_group") val variantGroup: Long? = null,
    // 家族里的其他版本（图库合并显示时隐藏）；仅 active 的非缩略图可能为真
    @ColumnInfo(name = "variant_hidden", defaultValue = "0") val variantHidden: Boolean = false,
    // 家族代表图（最大的那张）记录家族现存版本数（≥2 才记），其余为 0；variant_bytes 见类注释
    @ColumnInfo(name = "variant_count", defaultValue = "0") val variantCount: Int = 0,
    @ColumnInfo(name = "variant_bytes", defaultValue = "0") val variantBytes: Long = 0,
    @ColumnInfo(name = "size") val size: Long,
    @ColumnInfo(name = "mtime") val mtime: Long,
    // 本地日分桶键（year*1000 + dayOfYear，与 strftime('%Y'/'%j', 'localtime') 同口径）：
    // 入库/扫描更新时预计算，分桶聚合走覆盖索引整列 GROUP BY，免逐行 strftime。
    // 时区变更后存量值仍是旧时区口径，由下一次扫描 updateFromScanByPath 逐步矫正（可接受的近似）。
    @ColumnInfo(name = "date_bucket", defaultValue = "0") val dateBucket: Long = computeDateBucket(mtime),
    @ColumnInfo(name = "first_seen") val firstSeen: Long,
    @ColumnInfo(name = "last_seen") val lastSeen: Long,

    // --- 图片元信息（文件头） ---
    @ColumnInfo(name = "mime") val mime: String,
    @ColumnInfo(name = "width") val width: Int,
    @ColumnInfo(name = "height") val height: Int,
    @ColumnInfo(name = "is_animated") val isAnimated: Boolean = false,

    // --- 状态 ---
    @ColumnInfo(name = "status") val status: String = "active",
    /** 已归档（列名沿用收藏时期的 is_protected） */
    @ColumnInfo(name = "is_protected") val isArchived: Boolean = false,
    /** 原图压缩：0 未处理 / 1 已压缩 / 2 无需压缩（见 OriginalCompressor） */
    @ColumnInfo(name = "optimized", defaultValue = "0") val optimized: Int = 0,

    // 永久删除时间戳：仅永久删除路径标记 deleted 时写入，用于首页「今日已清理」统计；
    // 功能上线前已删的历史行为 NULL，天然被任何「今日」窗口排除。
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
)

/**
 * 由 mtime 计算本地日分桶键（year*1000 + dayOfYear）。
 * 必须与 SQL 侧 `CAST(strftime('%Y', mtime/1000, 'unixepoch', 'localtime') AS INTEGER) * 1000
 * + CAST(strftime('%j', …) AS INTEGER)` 严格同口径（updateFromScanByPath 与 v10 迁移回填用后者）。
 */
fun computeDateBucket(mtime: Long): Long {
    val d = Instant.ofEpochMilli(mtime).atZone(ZoneId.systemDefault()).toLocalDate()
    return d.year * 1000L + d.dayOfYear
}
