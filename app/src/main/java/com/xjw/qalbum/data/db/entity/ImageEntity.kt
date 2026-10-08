package com.xjw.qalbum.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant
import java.time.ZoneId

/**
 * 图片索引表 —— QAlbum 的核心实体。
 *
 * 设计要点：
 * - `cache_key`：从 `Cache_{hash}` 文件名中提取的 hash 部分。
 * - `variant_group`：图片家族键——同一张 QQ 图片的 chatimg / chatraw / chatthumb 由文件名 CRC 推出同一个键
 *   （见 QqCachePaths.familyKey）；`variant_hidden` / `variant_count` / `variant_bytes` 由触发器维护：
 *   图库合并显示时只露出家族中最大的那张，它带上家族的版本数与总大小。`is_variant_primary` 为历史遗留列，不再使用。
 * - `duplicate_group_id` / `is_duplicate_best`：基于 xxHash + pHash 检测出的"重复组"。
 * - `status = thumbnail_trash`：历史状态名；现在表示 QQ 缩略图（chatthumb），不进图库时间线，可在缩略图页与来源页清理。
 */
@Entity(
    tableName = "images",
    indices = [
        // path 是扫描入库 diff 的高频查询列：updateFromScanByPath 按等值命中，
        // listScanRowsInPathRange / markMissingInPathRange 按 "dir/" 前缀做范围扫描，
        // 不加索引会退化成全表扫描。unique：insertAll 的 IGNORE 兜底依赖此唯一约束，
        // 否则极端并发下的双插会静默写入重复行且无任何约束拦截。
        Index(value = ["path"], unique = true),
        Index("xxhash"),
        Index("phash"),
        Index("cache_key"),
        Index("variant_group"),
        Index("status"),
        Index("mtime"),
        // size 是「大到小」排序与 cleanup 聚合的高频排序列；25w 图无索引会退化成全表 filesort。
        Index("size"),
        Index("delete_score"),
        Index("info_score"),
        Index("duplicate_group_id"),
        // 分桶计数覆盖索引：observeTimelineDateBucketCounts / observeFavoriteDateBucketCounts
        // 只扫索引条目做 GROUP BY date_bucket，免 200w 行全表逐行 strftime。
        Index("status", "source_type", "date_bucket", "is_protected", "variant_hidden"),
        // 时间线定位覆盖索引：timelineRank* / timelineIds*（keyset 定位与窗口）只扫索引条目计数，
        // 不做 200w 次行回表。
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
    @ColumnInfo(name = "is_variant_primary") val isVariantPrimary: Boolean = false,
    // 家族里的其他版本（图库合并显示时隐藏）；仅 active 的非缩略图可能为真
    @ColumnInfo(name = "variant_hidden", defaultValue = "0") val variantHidden: Boolean = false,
    // 仅家族代表图（最大的那张）记录：家族现存版本数（≥2 才记）与总大小，其余为 0
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

    // --- 图片元信息 ---
    @ColumnInfo(name = "mime") val mime: String,
    @ColumnInfo(name = "width") val width: Int,
    @ColumnInfo(name = "height") val height: Int,
    @ColumnInfo(name = "is_animated") val isAnimated: Boolean = false,
    @ColumnInfo(name = "is_corrupt") val isCorrupt: Boolean = false,

    // --- Hash ---
    @ColumnInfo(name = "xxhash") val xxhash: String? = null,
    @ColumnInfo(name = "phash") val phash: Long? = null,
    // 256bit 分块指纹（视觉去重精筛，要求局部逐格一致）。null = 尚未回填。
    @ColumnInfo(name = "block_hash", typeAffinity = ColumnInfo.BLOB) val blockHash: ByteArray? = null,
    // 32 字节特征网格（16 亮度 + 16 边缘密度）：区分深/浅背景与版式的判别特征。null = 尚未回填。
    @ColumnInfo(name = "feature_grid", typeAffinity = ColumnInfo.BLOB) val featureGrid: ByteArray? = null,

    // --- 质量评估 ---
    @ColumnInfo(name = "blur_score") val blurScore: Float? = null,
    @ColumnInfo(name = "is_blank") val isBlank: Boolean = false,

    // --- 分析状态 ---
    @ColumnInfo(name = "hash_done") val hashDone: Boolean = false,
    @ColumnInfo(name = "ocr_done") val ocrDone: Boolean = false,
    @ColumnInfo(name = "ai_done") val aiDone: Boolean = false,
    @ColumnInfo(name = "embedding_done") val embeddingDone: Boolean = false,

    // --- 评分 ---
    @ColumnInfo(name = "info_score") val infoScore: Float = 0f,
    @ColumnInfo(name = "delete_score") val deleteScore: Float = 0f,

    // --- 重复组 ---
    @ColumnInfo(name = "duplicate_group_id") val duplicateGroupId: Long? = null,
    @ColumnInfo(name = "is_duplicate_best") val isDuplicateBest: Boolean = false,

    // --- 用户操作状态 ---
    @ColumnInfo(name = "status") val status: String = "active",
    @ColumnInfo(name = "is_protected") val isFavorite: Boolean = false,
    @ColumnInfo(name = "user_keep") val userKeep: Boolean? = null,

    // 永久删除时间戳：仅三条永久删除路径标记 deleted 时写入，用于首页「今日已清理」统计；
    // 功能上线前已删的历史行为 NULL，天然被任何「今日」窗口排除。
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
) {
    // blockHash 是 ByteArray，data class 默认 equals/hashCode 会按引用比较它，导致内容相同的两个实例
    // 不相等（污染 Compose 重组 / 集合去重）。故显式重写：blockHash 用 contentEquals，其余全字段比较，
    // hashCode 取 id（满足「equals 相等 → hashCode 相等」契约）。范式同 ImageEmbeddingEntity。
    // 注意：新增字段时需同步更新此 equals。
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ImageEntity) return false
        return id == other.id &&
            path == other.path &&
            filename == other.filename &&
            parentDir == other.parentDir &&
            sourceType == other.sourceType &&
            cacheKey == other.cacheKey &&
            variantGroup == other.variantGroup &&
            isVariantPrimary == other.isVariantPrimary &&
            variantHidden == other.variantHidden &&
            variantCount == other.variantCount &&
            variantBytes == other.variantBytes &&
            size == other.size &&
            mtime == other.mtime &&
            dateBucket == other.dateBucket &&
            firstSeen == other.firstSeen &&
            lastSeen == other.lastSeen &&
            mime == other.mime &&
            width == other.width &&
            height == other.height &&
            isAnimated == other.isAnimated &&
            isCorrupt == other.isCorrupt &&
            xxhash == other.xxhash &&
            phash == other.phash &&
            (blockHash?.contentEquals(other.blockHash) ?: (other.blockHash == null)) &&
            (featureGrid?.contentEquals(other.featureGrid) ?: (other.featureGrid == null)) &&
            blurScore == other.blurScore &&
            isBlank == other.isBlank &&
            hashDone == other.hashDone &&
            ocrDone == other.ocrDone &&
            aiDone == other.aiDone &&
            embeddingDone == other.embeddingDone &&
            infoScore == other.infoScore &&
            deleteScore == other.deleteScore &&
            duplicateGroupId == other.duplicateGroupId &&
            isDuplicateBest == other.isDuplicateBest &&
            status == other.status &&
            isFavorite == other.isFavorite &&
            userKeep == other.userKeep &&
            deletedAt == other.deletedAt
    }

    override fun hashCode(): Int = id.hashCode()
}

/**
 * 由 mtime 计算本地日分桶键（year*1000 + dayOfYear）。
 * 必须与 SQL 侧 `CAST(strftime('%Y', mtime/1000, 'unixepoch', 'localtime') AS INTEGER) * 1000
 * + CAST(strftime('%j', …) AS INTEGER)` 严格同口径（updateFromScanByPath 与 v10 迁移回填用后者）。
 */
fun computeDateBucket(mtime: Long): Long {
    val d = Instant.ofEpochMilli(mtime).atZone(ZoneId.systemDefault()).toLocalDate()
    return d.year * 1000L + d.dayOfYear
}
