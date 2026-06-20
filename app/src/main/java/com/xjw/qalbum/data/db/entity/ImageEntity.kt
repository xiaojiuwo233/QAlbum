package com.xjw.qalbum.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 图片索引表 —— QAlbum 的核心实体。
 *
 * 设计要点：
 * - `cache_key`：从 `Cache_{hash}` 文件名中提取的 hash 部分。
 * - `variant_group` / `is_variant_primary`：仅保留历史 cache_key 变体能力，不再用来匹配缩略图与原图。
 * - `duplicate_group_id` / `is_duplicate_best`：基于 xxHash + pHash 检测出的"重复组"。
 * - `status = thumbnail_trash`：chatthumb 缩略图自动进入独立回收站，不参与图库和分析。
 */
@Entity(
    tableName = "images",
    indices = [
        // path 是扫描入库 diff 的高频查询列：updateFromScanByPath 按等值命中，
        // listScanRowsInPathRange / markMissingInPathRange 按 "dir/" 前缀做范围扫描，
        // 不加索引会退化成全表扫描。
        Index("path"),
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
    @ColumnInfo(name = "size") val size: Long,
    @ColumnInfo(name = "mtime") val mtime: Long,
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
)
